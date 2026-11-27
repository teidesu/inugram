import type { BuildOptions } from 'esbuild'
import type { Vocabulary } from './catalog.js'
import type { Manifest } from './manifest.js'
import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build, context } from 'esbuild'
import * as v from 'valibot'
import { parseFile } from '../routines/find.js'
import { loadVocabulary } from './catalog.js'
import { NotALiteralError, readLiteral } from './literal.js'
import { CliError, color } from './log.js'
import { createManifestSchema, PartialManifestSchema } from './manifest.js'
import { customFn, describeIssue } from './schema.js'

const PluginConfigSchema = v.object({
  /** plugin entrypoint */
  entry: v.string(),
  /**
   * plugin build target file
   *
   * @default `{outDir}/{key}.inu.js`
   */
  outFile: v.optional(v.string()),
  /** plugin manifest, overridden key by key by the entry's `export const manifest` */
  manifest: v.optional(PartialManifestSchema),
  esbuild: v.optional(customFn<(options: BuildOptions) => BuildOptions | void>()),
})
export type PluginConfig = v.InferInput<typeof PluginConfigSchema>

const InuCliConfigSchema = v.object({
  /**
   * build target directory
   *
   * @default `dist`
   */
  outDir: v.optional(v.string()),
  /** manifest defaults for every plugin */
  manifest: v.optional(PartialManifestSchema),
  /**
   * Plugins built by this repo, keyed by short names used to select them in `dev`, `build`,
   * and other commands.
   *
   * A plain string is a shorthand for `{ entry: '...' }`
   */
  plugins: v.record(v.string(), v.union([v.string(), PluginConfigSchema])),
  /** Customizes the esbuild configuration for all plugins. */
  esbuild: v.optional(customFn<(options: BuildOptions, plugin: ResolvedPluginConfig) => BuildOptions | void>()),
})
export type InuCliConfig = v.InferInput<typeof InuCliConfigSchema>

export interface ResolvedPluginConfig {
  /** plugin slug */
  slug: string
  /** plugin entrypoint (absolute) */
  entry: string
  /** plugin output file (absolute) */
  outFile: string
  /** manifest of the plugin */
  manifest: Manifest
  /** the app's reasons to refuse [manifest], relative to this plugin; empty when it would install */
  manifestIssues: string[]
  /** manifest of the plugin read from entrypoint's `export const manifest` */
  entryManifest: EntryManifest
  /** Customizes the esbuild configuration for this plugin. */
  esbuild?: (options: BuildOptions) => BuildOptions | void
}

export interface ResolvedCliConfig {
  /** repo root, absolute */
  root: string
  /** the grant catalogue and tl names the project's `@inugram/plugin-types` ships */
  vocabulary: Vocabulary
  /** config file name */
  configFile: string
  /** output directory (absolute) */
  outDir: string
  /** registered plugins */
  plugins: ResolvedPluginConfig[]
  /** esbuild finalizer */
  esbuild?: InuCliConfig['esbuild']
}

export const CONFIG_NAMES = ['inu.config.ts', 'inu.config.mts', 'inu.config.js', 'inu.config.mjs']

function findConfig(cwd: string, explicit?: string): string {
  if (explicit) {
    const path = isAbsolute(explicit) ? explicit : resolve(cwd, explicit)
    if (!existsSync(path)) throw new CliError(`no config at ${path}`)
    return path
  }
  for (const name of CONFIG_NAMES) {
    const path = join(cwd, name)
    if (existsSync(path)) return path
  }
  throw new CliError(`inu.config.ts was not found in ${cwd} - run ${color.blue('inu init')} to start a project`)
}

export function refuse(configFile: string, issues: string[]): never {
  throw new CliError(`${configFile} is not a valid config:\n${issues.join('\n')}`)
}

export interface EntryManifest { manifest?: v.InferOutput<typeof PartialManifestSchema>, issues?: string[] }

/** Reads `export const manifest = { ... }` without running the entry, which only works in the app. */
export async function readEntryManifest(root: string, entry: string): Promise<EntryManifest> {
  const source = await fs.readFile(entry, 'utf8').catch(() => null)
  if (source === null || !source.includes('manifest')) return {}
  const parsed = parseFile(entry, source)
  // esbuild reports these with more context
  if (parsed.errors.length > 0) return {}

  const at = (start: number, text: string) => {
    const before = source.slice(0, start)
    const column = start - before.lastIndexOf('\n')
    return `${relative(root, entry)}:${before.split('\n').length}:${column}: ${text}`
  }
  for (const statement of parsed.program.body) {
    if (statement.type !== 'ExportNamedDeclaration') continue
    const declaration = statement.declaration
    if (declaration?.type !== 'VariableDeclaration') {
      const reexport = statement.specifiers.find(it => it.exported.type === 'Identifier' && it.exported.name === 'manifest')
      if (reexport) return { issues: [at(reexport.start, 'declare the manifest as `export const manifest = { ... }`')] }
      continue
    }
    const declarator = declaration.declarations.find(it => it.id.type === 'Identifier' && it.id.name === 'manifest')
    if (!declarator) continue

    let literal: unknown
    try {
      literal = readLiteral(declarator.init)
    } catch (error) {
      if (!(error instanceof NotALiteralError)) throw error
      return { issues: [at(error.start ?? declarator.start, 'the manifest must be a literal, the cli reads it without running the entry')] }
    }
    const layer = v.safeParse(PartialManifestSchema, literal)
    if (!layer.success) {
      return { issues: layer.issues.map(issue => at(declarator.start, describeIssue(issue, 'manifest').trim())) }
    }
    return { manifest: layer.output }
  }
  return {}
}

/**
 * Merges the manifest layers and validates the result using the app's rules. This runs after
 * schema validation because Valibot cannot receive the grant catalogue during parsing.
 */
function resolveManifest(
  schema: ReturnType<typeof createManifestSchema>,
  layers: (v.InferOutput<typeof PartialManifestSchema> | undefined)[],
  entry: EntryManifest,
): { manifest: Manifest, issues: string[] } {
  const present = [...layers, entry.manifest].filter(layer => layer !== undefined)
  const merged: Record<string, unknown> = Object.assign({}, ...present)
  const extra = Object.assign({}, ...present.map(layer => layer.extra))
  if (Object.keys(extra).length > 0) merged.extra = extra
  if (entry.issues) return { manifest: merged as Manifest, issues: entry.issues }

  const parsed = v.safeParse(schema, merged)
  if (parsed.success) return { manifest: parsed.output, issues: [] }
  return { manifest: merged as Manifest, issues: parsed.issues.map(issue => describeIssue(issue, 'manifest').trim()) }
}

/**
 * Keep the bundle inside the project so Node can resolve the config's bare imports,
 * including `@inugram/cli`, relative to it.
 */
function createConfigBuildOptions(configFile: string): BuildOptions {
  return {
    entryPoints: [configFile],
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node20',
    packages: 'external',
    sourcemap: false,
    logLevel: 'silent',
  }
}

async function importConfig(configFile: string): Promise<InuCliConfig> {
  const bundled = join(dirname(configFile), `.inu.config.${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`)
  await build({ ...createConfigBuildOptions(configFile), outfile: bundled })
  try {
    const loaded = await import(pathToFileURL(bundled).href) as { default?: unknown }
    if (!loaded.default) throw new CliError(`${configFile} has no default export`)
    const parsed = v.safeParse(InuCliConfigSchema, loaded.default)
    if (!parsed.success) refuse(configFile, parsed.issues.map(issue => describeIssue(issue)))
    return parsed.output
  } finally {
    await fs.rm(bundled, { force: true })
  }
}

/** Calls [onChange] whenever the config or a local file it imports changes. */
export async function watchConfig(configFile: string, onChange: () => void) {
  let initial = true
  const watcher = await context({
    ...createConfigBuildOptions(configFile),
    write: false,
    plugins: [{
      name: 'inu-config-watch',
      setup(build) {
        build.onEnd(() => {
          if (initial) initial = false
          else onChange()
        })
      },
    }],
  })
  await watcher.watch()
  return watcher
}

export async function loadConfig(cwd: string, explicit?: string): Promise<ResolvedCliConfig> {
  const configFile = findConfig(cwd, explicit)
  const root = dirname(configFile)
  const vocabulary = await loadVocabulary(root)
  const config = await importConfig(configFile)

  const entries = Object.entries(config.plugins)
  if (entries.length === 0) throw new CliError(`${configFile} declares no plugins`)
  const outDir = resolve(root, config.outDir ?? 'dist')
  const schema = createManifestSchema(vocabulary)

  const plugins = await Promise.all(entries.map(async ([slug, declared]): Promise<ResolvedPluginConfig> => {
    const plugin = typeof declared === 'string' ? { entry: declared } : declared
    const entry = resolve(root, plugin.entry)
    const entryManifest = await readEntryManifest(root, entry)
    const { manifest, issues } = resolveManifest(schema, [config.manifest, plugin.manifest], entryManifest)
    return {
      slug,
      entry,
      outFile: plugin.outFile ? resolve(root, plugin.outFile) : join(outDir, `${slug}.inu.js`),
      manifest,
      manifestIssues: issues,
      entryManifest,
      esbuild: plugin.esbuild,
    }
  }))

  return {
    root,
    vocabulary,
    configFile,
    outDir,
    plugins,
    esbuild: config.esbuild,
  }
}
