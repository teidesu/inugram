import process from 'node:process'
import { sleep } from '@fuman/utils'
import { configArgs, defineCommand, deviceArgs } from '../utils/args.js'
import { Device } from '../utils/device.js'
import { CliError, fail, step } from '../utils/log.js'
import { loadProject } from '../utils/project.js'
import { buildOnce, reportOutcome } from './build.js'
import { LogChannels, reportInstall, requireNamedPlugins } from './dev.js'

export const pushCmd = defineCommand({
  meta: { name: 'push', description: 'build, push and reload once, then print the logs for a while' },
  args: {
    ...configArgs,
    ...deviceArgs,
    names: {
      type: 'positional',
      required: false,
      description: 'plugins to push',
    },
    logs: {
      type: 'string',
      default: '3',
      description: 'seconds to print the plugin log for after the push, 0 to skip',
    },
  },
  run: async ({ args }) => {
    const seconds = Number(args.logs)
    if (!Number.isFinite(seconds) || seconds < 0) throw new CliError(`--logs must be a number of seconds, got '${args.logs}'`)

    const project = await loadProject(args)
    requireNamedPlugins('push', args, project)
    const { config, plugins } = project
    const device = new Device(args)
    const pid = await device.requireRunning()
    const ping = await device.ping()
    if (!ping.engineEnabled) throw new CliError('plugins are disabled in settings, your code would not run')
    if (ping.safeMode) throw new CliError('plugins are in safe mode, your code would not run')

    const outcomes = await Promise.all(plugins.map(plugin => buildOnce(config, plugin)))
    for (const outcome of outcomes) await reportOutcome(config, outcome)
    if (outcomes.some(outcome => !outcome.ok)) {
      process.exitCode = 1
      return
    }

    const channels = new LogChannels(project)
    const logsSince = await device.getLogTime()

    for (const plugin of plugins) {
      for (const install of await device.install([plugin.outFile])) {
        reportInstall(install)
        if (install.ok) channels.addPlugin(install.plugin, plugin.slug)
        if (!install.ok || install.plugin.failure !== undefined) process.exitCode = 1
      }
    }
    if (seconds === 0) return

    step(`logs for ${seconds}s`)
    await sleep(seconds * 1000)
    let errors = 0
    for (const { level, tag, message } of await device.dumpLogs(logsSince, pid)) {
      if (channels.print(level, tag, message) && (level === 'E' || level === 'F')) errors++
    }

    if (errors > 0) {
      fail(`${errors} error line${errors === 1 ? '' : 's'} logged`)
      process.exitCode = 1
    }
  },
})
