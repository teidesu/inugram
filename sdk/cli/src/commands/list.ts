import { defineCommand, deviceArgs } from '../utils/args.js'
import { createDevice } from '../utils/device.js'
import { color } from '../utils/log.js'

export const listCmd = defineCommand({
  meta: { name: 'list', description: 'list the installed plugins' },
  args: deviceArgs,
  run: async ({ args }) => {
    const device = createDevice(args)
    await device.requireRunning()

    const plugins = await device.list()
    if (plugins.length === 0) {
      console.log(color.gray('nothing installed'))
      return
    }

    const devPlaceholder = plugins.some(it => it.dev) ? '    ' : ''

    for (const plugin of plugins) {
      const status = plugin.enabled ? color.green('enabled ') : color.red('disabled')
      const dev = plugin.dev ? color.blue('dev ') : devPlaceholder
      const failure = plugin.failure ? color.red(` ${plugin.failure}`) : ''
      console.log(`${status} ${dev}${color.bold(plugin.name)} ${failure}`)
    }
  },
})
