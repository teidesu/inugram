import type { BuildOptions, Plugin as EsbuildPlugin, Message } from 'esbuild'
import type { ResolvedCliConfig, ResolvedPluginConfig } from '../utils/config.js'
import type { Project, ProjectArgs } from '../utils/project.js'
import { relative } from 'node:path'
import process from 'node:process'
import { AsyncLock } from '@fuman/utils'
import * as esbuild from 'esbuild'
import { compileRoutines } from '../routines/plugin.js'
import { configArgs, defineCommand } from '../utils/args.js'
import { watchConfig } from '../utils/config.js'
import { readFileSize } from '../utils/fs.js'
import { color, fail, printMessages, step, warn } from '../utils/log.js'
import { collectManifestWarnings, renderManifestHeader } from '../utils/manifest.js'
import { untilInterrupted } from '../utils/process.js'
import { loadProject } from '../utils/project.js'

export interface BuildOutcome {
  plugin: ResolvedPluginConfig
  ok: boolean
  bytes: number
  problems: Message[]
  warnings: Message[]
  /** A manifest error without a source-file location. */
  notes: string[]
}

export function createBuildOptions(
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
    const result = await esbuild.build(createBuildOptions(config, plugin, []))
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
  await printMessages(outcome.problems, 'error')
  await printMessages(outcome.warnings, 'warning')
  for (const note of outcome.notes) warn(`${name} ${note}`)
  if (!outcome.ok) return
  const where = relative(config.root, outcome.plugin.outFile)
  console.log(`${color.green('ok')} ${name} ${color.gray(`${where} (${(outcome.bytes / 1024).toFixed(1)} kb)`)}`)
}

export interface Watcher {
  dispose(): Promise<void>
}

/**
 * Creates one independent esbuild context per plugin. Calls [onBuilt] after every build,
 * including the first, so callers can push only the changed plugin.
 */
async function watchPlugins(project: Project, onBuilt: (config: ResolvedCliConfig, outcome: BuildOutcome) => void): Promise<Watcher> {
  const { config, plugins } = project
  const contexts = await Promise.all(plugins.map(async (plugin) => {
    const warnings = collectManifestWarnings(plugin.manifest, config.vocabulary)
    const notify: EsbuildPlugin = {
      name: 'inu-notify',
      setup(build) {
        build.onEnd(async (result) => {
          onBuilt(config, {
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
    const context = await esbuild.context(createBuildOptions(config, plugin, [notify]))
    await context.watch()
    return context
  }))

  return {
    async dispose() {
      await Promise.all(contexts.map(context => context.dispose()))
    },
  }
}

/**
 * Watches [project]'s plugins, and reloads it from [args] when its config changes. A config that
 * fails to load, or that [onReload] throws for, is reported and the previous one keeps building.
 */
export async function watchProject(options: {
  args: ProjectArgs
  project: Project
  onReload?: (project: Project) => void
  onBuilt: (config: ResolvedCliConfig, outcome: BuildOutcome) => void
}): Promise<Watcher> {
  const { args, onReload, onBuilt } = options
  let plugins = await watchPlugins(options.project, onBuilt)
  const lock = new AsyncLock()
  const reload = async () => {
    let project: Project
    try {
      project = await loadProject(args)
      onReload?.(project)
    } catch (error) {
      fail(`config not reloaded: ${error instanceof Error ? error.message : String(error)}`)
      return
    }
    await plugins.dispose()
    plugins = await watchPlugins(project, onBuilt)
    step(`config reloaded, watching ${project.plugins.map(plugin => plugin.slug).join(', ')}`)
  }
  const config = await watchConfig(options.project.config.configFile, () => {
    lock.with(reload).catch((error: unknown) => { fail(String(error)) })
  })

  return {
    async dispose() {
      await config.dispose()
      await lock.with(() => plugins.dispose())
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
    const project = await loadProject(args)
    const { config, plugins } = project

    if (args.watch) {
      const watcher = await watchProject({
        args,
        project,
        onBuilt: (config, outcome) => { void reportOutcome(config, outcome) },
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
