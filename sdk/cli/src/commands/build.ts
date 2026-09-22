import type { BuildOptions, Plugin as EsbuildPlugin, Message } from 'esbuild'
import type { ResolvedCliConfig, ResolvedPluginConfig } from '../utils/config.js'
import { relative } from 'node:path'
import process from 'node:process'
import * as esbuild from 'esbuild'
import { compileRoutines } from '../routines/plugin.js'
import { configArgs, defineCommand } from '../utils/args.js'
import { readFileSize } from '../utils/fs.js'
import { color, fail, renderMessages, step, warn } from '../utils/log.js'
import { collectManifestWarnings, renderManifestHeader } from '../utils/manifest.js'
import { untilInterrupted } from '../utils/process.js'
import { loadProject } from '../utils/project.js'

export interface BuildOutcome {
  plugin: ResolvedPluginConfig
  ok: boolean
  bytes: number
  problems: Message[]
  warnings: Message[]
  /** what is wrong with the manifest, which is not in any source file to point at */
  notes: string[]
}

function optionsFor(
  config: ResolvedCliConfig,
  plugin: ResolvedPluginConfig,
  extra: EsbuildPlugin[],
): BuildOptions {
  let options: BuildOptions = {
    absWorkingDir: config.root,
    entryPoints: [plugin.entry],
    outfile: plugin.outFile,
    bundle: true,
    format: 'esm', // not really actually
    target: 'esnext',
    platform: 'neutral',
    mainFields: ['module', 'main'],
    sourcemap: false,
    minify: false,
    charset: 'utf8',
    banner: {
      js: renderManifestHeader(plugin.manifest, config.vocabulary.catalog.pluginApi),
    },
    logLevel: 'silent',
    plugins: [compileRoutines(), ...extra],
  }

  options = plugin.esbuild?.(options) ?? options
  options = config.esbuild?.(options, plugin) ?? options
  return options
}

export async function buildOnce(
  config: ResolvedCliConfig,
  plugin: ResolvedPluginConfig,
): Promise<BuildOutcome> {
  const warnings = collectManifestWarnings(plugin.manifest, config.vocabulary)

  try {
    const result = await esbuild.build(optionsFor(config, plugin, []))
    return {
      plugin,
      ok: true,
      bytes: await readFileSize(plugin.outFile),
      problems: [],
      warnings: result.warnings,
      notes: warnings,
    }
  } catch (error) {
    const messages = (error as { errors?: Message[] }).errors ?? []
    return {
      plugin,
      ok: false,
      bytes: 0,
      problems: messages,
      warnings: [],
      notes: messages.length === 0 ? [...warnings, String(error)] : warnings,
    }
  }
}

export async function reportOutcome(config: ResolvedCliConfig, outcome: BuildOutcome) {
  const name = color.bold(outcome.plugin.slug)
  if (outcome.problems.length > 0) fail(`${name} did not build`)
  for (const frame of await renderMessages(outcome.problems, 'error')) process.stdout.write(frame)
  for (const frame of await renderMessages(outcome.warnings, 'warning')) process.stdout.write(frame)
  for (const note of outcome.notes) warn(`${name} ${note}`)
  if (!outcome.ok) return
  const where = relative(config.root, outcome.plugin.outFile)
  console.log(`${color.green('ok')} ${name} ${color.gray(`${where} (${(outcome.bytes / 1024).toFixed(1)} kb)`)}`)
}

export interface Watcher {
  dispose(): Promise<void>
}

/**
 * one esbuild context per plugin, each rebuilding on its own. [onBuilt] runs after every rebuild,
 * the first one included, so a caller can push what changed and nothing else.
 */
export async function watchPlugins(options: {
  config: ResolvedCliConfig
  plugins: ResolvedPluginConfig[]
  onBuilt: (outcome: BuildOutcome) => void
}): Promise<Watcher> {
  const { config, plugins, onBuilt } = options

  const contexts = await Promise.all(plugins.map(async (plugin) => {
    const warnings = collectManifestWarnings(plugin.manifest, config.vocabulary)
    const notify: EsbuildPlugin = {
      name: 'inu-notify',
      setup(build) {
        build.onEnd(async (result) => {
          onBuilt({
            plugin,
            ok: result.errors.length === 0,
            bytes: result.errors.length === 0 ? await readFileSize(plugin.outFile) : 0,
            problems: result.errors,
            warnings: result.warnings,
            notes: warnings,
          })
        })
      },
    }
    const context = await esbuild.context(optionsFor(config, plugin, [notify]))
    await context.watch()
    return context
  }))

  return {
    async dispose() {
      await Promise.all(contexts.map(context => context.dispose()))
    },
  }
}

export const buildCmd = defineCommand({
  meta: {
    name: 'build',
    description: 'build all or some plugins',
  },
  args: {
    ...configArgs,
    names: {
      type: 'positional',
      required: false,
      description: 'plugins to build',
    },
    watch: {
      type: 'boolean',
      alias: 'w',
      default: false,
      description: 'watch mode',
    },
  },
  run: async ({ args }) => {
    const { config, plugins } = await loadProject(args)

    if (args.watch) {
      const watcher = await watchPlugins({
        config,
        plugins,
        onBuilt: (outcome) => { void reportOutcome(config, outcome) },
      })
      step(`watching ${plugins.map(plugin => plugin.slug).join(', ')} (ctrl-c to stop)`)
      await untilInterrupted()
      await watcher.dispose()
      return
    }

    const outcomes = await Promise.all(plugins.map(plugin => buildOnce(config, plugin)))
    for (const outcome of outcomes) await reportOutcome(config, outcome)
    if (outcomes.some(outcome => !outcome.ok)) process.exitCode = 1
  },
})
