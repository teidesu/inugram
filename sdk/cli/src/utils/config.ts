import type { BuildOptions } from 'esbuild'
import type { Vocabulary } from './catalog.js'
import type { Manifest } from './manifest.js'
import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'esbuild'
import * as v from 'valibot'
import { loadVocabulary } from './catalog.js'
import { CliError, color } from './log.js'
import { createManifestSchema, ManifestSchema } from './manifest.js'
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
  /** plugin manifest */
  manifest: ManifestSchema,
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
  /**
   * Plugins built by this repo, keyed by short names used to select them in `dev`, `build`,
   * and other commands.
   */
  plugins: v.record(v.string(), PluginConfigSchema),
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

function refuse(configFile: string, issues: string[]): never {
  throw new CliError(`${configFile} is not a valid config:\n${issues.join('\n')}`)
}

/**
 * Validates each manifest using the app's rules. This runs after schema validation
 * because Valibot cannot receive the grant catalogue during parsing.
 */
function checkManifests(configFile: string, config: InuCliConfig, vocabulary: Vocabulary) {
  const schema = createManifestSchema(vocabulary)
  const issues: string[] = []

  for (const [slug, plugin] of Object.entries(config.plugins)) {
    const parsed = v.safeParse(schema, plugin.manifest)
    if (parsed.success) continue
    for (const issue of parsed.issues) issues.push(describeIssue(issue, `plugins.${slug}.manifest`))
  }

  if (issues.length > 0) refuse(configFile, issues)
}

/**
 * Keep the bundle inside the project so Node can resolve the config's bare imports,
 * including `@inugram/cli`, relative to it.
 */
async function importConfig(configFile: string): Promise<InuCliConfig> {
  const bundled = join(dirname(configFile), `.inu.config.${Date.now()}-${Math.random().toString(36).slice(2)}.mjs`)
  await build({
    entryPoints: [configFile],
    outfile: bundled,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node20',
    packages: 'external',
    sourcemap: false,
    logLevel: 'silent',
  })
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

export async function loadConfig(cwd: string, explicit?: string): Promise<ResolvedCliConfig> {
  const configFile = findConfig(cwd, explicit)
  const root = dirname(configFile)
  const vocabulary = await loadVocabulary(root)
  const config = await importConfig(configFile)
  checkManifests(configFile, config, vocabulary)

  const entries = Object.entries(config.plugins ?? {})
  if (entries.length === 0) throw new CliError(`${configFile} declares no plugins`)
  const outDir = resolve(root, config.outDir ?? 'dist')

  const plugins = entries.map(([slug, plugin]): ResolvedPluginConfig => ({
    slug,
    entry: resolve(root, plugin.entry),
    outFile: plugin.outFile ? resolve(root, plugin.outFile) : join(outDir, `${slug}.inu.js`),
    manifest: plugin.manifest,
    esbuild: plugin.esbuild,
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
