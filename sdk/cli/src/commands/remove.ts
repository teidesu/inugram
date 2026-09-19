import { defineCommand, deviceArgs } from '../utils/args.js'
import { createDevice } from '../utils/device.js'
import { reportPluginAction } from './dev.js'

export const removeCmd = defineCommand({
  meta: { name: 'remove', description: 'uninstall a dev plugin by file name' },
  args: {
    ...deviceArgs,
    file: {
      type: 'positional',
      required: true,
      description: 'the file the plugin was pushed as',
    },
  },
  run: async ({ args }) => {
    const device = createDevice(args)
    await device.requireRunning()
    const removed = await device.remove(args.file)
    reportPluginAction(removed.action, removed.plugin)
  },
})
