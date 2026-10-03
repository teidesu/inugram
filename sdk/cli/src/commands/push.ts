import process from 'node:process'
import { configArgs, defineCommand, deviceArgs } from '../utils/args.js'
import { Device } from '../utils/device.js'
import { CliError, color, fail, step } from '../utils/log.js'
import { loadProject } from '../utils/project.js'
import { buildOnce, reportOutcome } from './build.js'
import { addInstallChannel, addLogChannels, labelLogLine, printLogLine, reportInstall, requireNamedPlugins } from './dev.js'

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
    await device.requireRunning()
    const ping = await device.ping()
    if (!ping.engineEnabled) throw new CliError('plugins are disabled in settings, your code would not run')
    if (ping.safeMode) throw new CliError('plugins are in safe mode, your code would not run')

    const outcomes = await Promise.all(plugins.map(plugin => buildOnce(config, plugin)))
    for (const outcome of outcomes) await reportOutcome(config, outcome)
    if (outcomes.some(outcome => !outcome.ok)) {
      process.exitCode = 1
      return
    }

    const channels = new Map<string, string>()
    addLogChannels(channels, project)
    const logsSince = await device.getLogTime()

    for (const plugin of plugins) {
      for (const install of await device.install([plugin.outFile])) {
        reportInstall(install)
        addInstallChannel(channels, install, plugin.slug)
        if (!install.ok || install.plugin.failure !== undefined) process.exitCode = 1
      }
    }
    if (seconds === 0) return

    step(`logs for ${seconds}s`)
    const aborter = new AbortController()
    const timer = setTimeout(() => aborter.abort(), seconds * 1000)
    let errors = 0
    await device.tailLogs(logsSince, (level, tag, message) => {
      const label = labelLogLine(channels, level, tag)
      if (label === undefined) return
      printLogLine(label, level, message)
      if (level === 'E' || level === 'F') errors++
    }, (reason) => {
      console.log(color.gray(`logcat stopped (${reason}), reconnecting`))
    }, aborter.signal).finally(() => clearTimeout(timer))

    if (errors > 0) {
      fail(`${errors} error line${errors === 1 ? '' : 's'} logged`)
      process.exitCode = 1
    }
  },
})
