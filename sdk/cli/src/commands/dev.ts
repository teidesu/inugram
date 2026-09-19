import type { DevInstall, DevPlugin } from '../utils/device.js'
import type { BuildOutcome } from './build.js'
import { basename } from 'node:path'
import { AsyncLock } from '@fuman/utils'
import { configArgs, defineCommand, deviceArgs } from '../utils/args.js'
import { createDevice } from '../utils/device.js'
import { readFileHash } from '../utils/fs.js'
import { CliError, color, fail, step, success, warn } from '../utils/log.js'
import { untilInterrupted } from '../utils/process.js'
import { loadProject } from '../utils/project.js'
import { reportOutcome, watchPlugins } from './build.js'

export function reportPluginAction(action: string, plugin: DevPlugin) {
  const failure = plugin.failure ? color.red(` (${plugin.failure})`) : ''
  success(`${action} ${color.bold(plugin.name)}${failure}`)
}

export function reportInstall(install: DevInstall) {
  if (!install.ok) {
    fail(install.error)
    return
  }
  reportPluginAction(install.action, install.plugin)
}

const LEVEL_COLOR: Record<string, (text: string) => string> = {
  E: color.red,
  W: color.yellow,
  I: color.white,
  D: color.gray,
  V: color.gray,
}

export const devCmd = defineCommand({
  meta: { name: 'dev', description: 'build, push and reload on every save' },
  args: {
    ...configArgs,
    ...deviceArgs,
    names: {
      type: 'positional',
      required: false,
      description: 'plugins to push',
    },
    logs: {
      type: 'boolean',
      default: true,
      description: 'tail the plugin log while watching',
      negativeDescription: 'do not tail the plugin log',
    },
  },
  run: async ({ args }) => {
    const { config, plugins } = await loadProject(args)
    // a dev session reloads what it pushes, so it is never all of them by accident
    if (args._.length === 0 && config.plugins.length > 1) {
      const known = config.plugins.map(plugin => `- ${color.bold(plugin.slug)}: ${plugin.manifest.name}`)
      throw new CliError(`there's more than one plugin, please specify one with ${color.blue('inu dev <name>')}:\n${known.join('\n')}`)
    }
    const device = createDevice(args)

    await device.requireRunning()
    const ping = await device.ping()
    if (!ping.engineEnabled) warn('plugins are disabled in settings, your code will not run')
    if (ping.safeMode) warn('plugins are in safe mode, your code will not run')

    const pushed = new Map<string, string>()
    const queue = new AsyncLock()

    const push = async (outcome: BuildOutcome) => {
      const file = outcome.plugin.outFile
      const hash = await readFileHash(file)
      if (hash === null) return
      if (pushed.get(file) === hash) {
        console.log(color.gray(`unchanged, not pushed: ${basename(file)}`))
        return
      }
      pushed.set(file, hash)
      try {
        for (const install of await device.install([file])) reportInstall(install)
      } catch (error) {
        // a failed push must not stick: the next rebuild has to try again
        pushed.delete(file)
        fail((error as Error).message)
      }
    }

    const watcher = await watchPlugins({
      config,
      plugins,
      onBuilt: (outcome) => {
        reportOutcome(config, outcome)
        if (!outcome.ok) return
        queue.with(() => push(outcome)).catch((error: unknown) => { fail(String(error)) })
      },
    })

    const aborter = new AbortController()
    if (args.logs) {
      device
        .tailLogs((level, tag, message) => {
          console.log(`${(LEVEL_COLOR[level] ?? color.gray)(tag)} ${message}`)
        }, aborter.signal)
        .catch((error: Error) => fail(error.message))
    }

    step(`watching ${plugins.map(plugin => plugin.slug).join(', ')} (ctrl-c to stop)`)
    await untilInterrupted()
    aborter.abort()
    await watcher.dispose()
  },
})
