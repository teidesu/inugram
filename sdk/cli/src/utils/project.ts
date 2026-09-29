import { loadConfig, refuse } from './config.js'
import { CliError } from './log.js'

/** Command arguments parsed by citty; `_` contains positional arguments. */
export interface ProjectArgs {
  config?: string
  _: string[]
}

export interface ProjectOptions {
  /** keep plugins whose manifests the app would refuse, for a caller that reports them itself */
  keepRefusedManifests?: boolean
}

export async function loadProject(args: ProjectArgs, options: ProjectOptions = {}) {
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

  if (!options.keepRefusedManifests) {
    const issues = plugins.flatMap(plugin => plugin.manifestIssues.map(issue => `  plugins.${plugin.slug}.${issue}`))
    if (issues.length > 0) refuse(config.configFile, issues)
  }

  return { config, plugins }
}
