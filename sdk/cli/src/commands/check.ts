import { configArgs, defineCommand } from '../utils/args.js'
import { color, success, warn } from '../utils/log.js'
import { collectManifestWarnings } from '../utils/manifest.js'
import { loadProject } from '../utils/project.js'

export const checkCmd = defineCommand({
  meta: { name: 'check', description: 'validate manifests without building' },
  args: {
    ...configArgs,
    names: {
      type: 'positional',
      required: false,
      description: 'plugins to check',
    },
  },
  run: async ({ args }) => {
    // loading the project already refused everything the app would refuse, so only the
    // warnings are left to report
    const { config, plugins } = await loadProject(args)

    for (const plugin of plugins) {
      const name = color.bold(plugin.slug)
      for (const message of collectManifestWarnings(plugin.manifest, config.vocabulary)) {
        warn(`${name} ${message}`)
      }
      const api = plugin.manifest.pluginApi ?? config.vocabulary.catalog.pluginApi
      success(`${name} ${plugin.manifest.grants?.length ?? 0} grants, api ${api}`)
    }
  },
})
