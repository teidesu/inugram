import { loadConfig } from './config.js'
import { CliError } from './log.js'

/** what a command was pointed at, as citty parsed it: `_` holds every positional */
export interface ProjectArgs {
  config?: string
  _: string[]
}

export async function loadProject(args: ProjectArgs) {
  const config = await loadConfig(process.cwd(), args.config)

  let plugins = config.plugins
  if (args._.length > 0) {
    plugins = args._.map((name) => {
      const found = config.plugins.find(plugin => plugin.slug === name)
      if (!found) {
        const known = config.plugins.map(plugin => plugin.slug).join(', ')
        throw new CliError(`no plugin named '${name}' in ${config.configFile} (known: ${known})`)
      }
      return found
    })
  }

  return { config, plugins }
}
