import type { Message, PartialMessage } from 'esbuild'
import type { ResolvedCliConfig, ResolvedPluginConfig } from '../utils/config.js'
import { resolve } from 'node:path'
import process from 'node:process'
import * as esbuild from 'esbuild'
import { configArgs, defineCommand } from '../utils/args.js'
import { color, fail, renderMessages, success, warn } from '../utils/log.js'
import { collectManifestWarnings } from '../utils/manifest.js'
import { loadProject } from '../utils/project.js'
import { typecheckProject } from '../utils/typecheck.js'
import { createBuildOptions } from './build.js'

interface BundleCheck {
  errors: Message[]
  warnings: Message[]
  /** absolute paths of every file the bundle read */
  inputs: string[]
}

/** Bundles in memory, which runs the routine compiler over every file the plugin reaches. */
async function checkBundle(config: ResolvedCliConfig, plugin: ResolvedPluginConfig): Promise<BundleCheck> {
  try {
    const result = await esbuild.build({ ...createBuildOptions(config, plugin, []), write: false, metafile: true })
    return {
      errors: [],
      warnings: result.warnings,
      inputs: Object.keys(result.metafile.inputs).map(input => resolve(config.root, input)),
    }
  } catch (error) {
    const errors = (error as { errors?: Message[] }).errors
    if (errors === undefined || errors.length === 0) throw error
    return { errors, warnings: [], inputs: [] }
  }
}

async function print(messages: PartialMessage[], kind: 'error' | 'warning') {
  for (const frame of await renderMessages(messages, kind)) process.stdout.write(frame)
}

export const checkCmd = defineCommand({
  meta: { name: 'check', description: 'validate manifests, routines and types without building' },
  args: {
    ...configArgs,
    names: {
      type: 'positional',
      required: false,
      description: 'plugins to check',
    },
    typecheck: {
      type: 'boolean',
      default: true,
      description: 'typecheck the project (--no-typecheck to skip)',
    },
  },
  run: async ({ args }) => {
    const { config, plugins } = await loadProject(args, { keepRefusedManifests: true })
    const bundles = await Promise.all(plugins.map(plugin => checkBundle(config, plugin)))

    let failed = false
    for (const [index, plugin] of plugins.entries()) {
      const name = color.bold(plugin.slug)
      const bundle = bundles[index]
      for (const issue of plugin.manifestIssues) fail(`${name} ${issue}`)
      for (const message of collectManifestWarnings(plugin.manifest, config.vocabulary)) {
        warn(`${name} ${message}`)
      }
      await print(bundle.errors, 'error')
      await print(bundle.warnings, 'warning')
      if (plugin.manifestIssues.length > 0 || bundle.errors.length > 0) {
        failed = true
        fail(`${name} did not check out`)
        continue
      }
      const api = plugin.manifest.pluginApi ?? config.vocabulary.catalog.pluginApi
      success(`${name} ${plugin.manifest.grants?.length ?? 0} grants, api ${api}`)
    }

    if (args.typecheck) {
      const files = args._.length > 0
        ? new Set([...plugins.map(plugin => plugin.entry), ...bundles.flatMap(bundle => bundle.inputs)])
        : undefined
      const { errors, warnings } = typecheckProject(config.root, files)
      await print(errors, 'error')
      await print(warnings, 'warning')
      if (errors.length > 0) {
        failed = true
        fail(`typecheck found ${errors.length} error${errors.length === 1 ? '' : 's'}`)
      } else {
        success('typecheck')
      }
    }

    if (failed) process.exitCode = 1
  },
})
