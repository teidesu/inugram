import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { userInfo } from 'node:os'
import { basename, join, relative, resolve } from 'node:path'
import { templatesDir, version } from '../meta.js'
import { defineCommand } from '../utils/args.js'
import { CONFIG_NAMES } from '../utils/config.js'
import { copyTree } from '../utils/fs.js'
import { CliError, color, step, success } from '../utils/log.js'

const TEMPLATE_DIR = join(templatesDir, 'default')

function dependencyRange(): string {
  return version === 'dev' || version === '0.0.0' ? 'latest' : `^${version}`
}

export const initCmd = defineCommand({
  meta: {
    name: 'init',
    description: 'scaffold a plugin project',
  },
  args: {
    dir: {
      type: 'positional',
      required: false,
      description: 'directory to scaffold in (defaults to cwd)',
    },
    author: {
      type: 'string',
      default: (() => {
        try {
          return userInfo().username
        } catch {
          return 'you'
        }
      })(),
      description: 'author',
    },
  },
  run: async ({ args }) => {
    const root = resolve(args.dir ?? process.cwd())
    for (const name of CONFIG_NAMES) {
      if (existsSync(join(root, name))) throw new CliError(`${join(root, name)} already exists`)
    }
    if (existsSync(join(root, 'package.json'))) {
      throw new CliError(`${join(root, 'package.json')} already exists. Scaffolding into an existing project is currently not supported`)
    }

    const range = dependencyRange()
    const name = basename(root).replace(/[^a-z0-9-]+/gi, '-').toLowerCase() || 'inu-plugins'

    step(`Scaffolding ${color.bold(name)} in ${root}`)
    await fs.mkdir(root, { recursive: true })
    await copyTree({
      from: TEMPLATE_DIR,
      into: root,
      // crutch for npm that strips .gitignore
      rename: name => name === 'gitignore' ? '.gitignore' : name,
      substitute: body => body.replace(/__AUTHOR__/g, args.author),
    })

    const packageJson = {
      name,
      private: true,
      type: 'module',
      scripts: {
        build: 'inu build',
        check: 'inu check',
        dev: 'inu dev',
        typecheck: 'tsc --noEmit',
      },
      devDependencies: {
        '@inugram/cli': range,
        '@inugram/plugin-types': range,
        'typescript': '^5.9.3',
      },
    }
    await fs.writeFile(join(root, 'package.json'), `${JSON.stringify(packageJson, null, 2)}\n`)

    success(`created ${relative(process.cwd(), root) || '.'}`)
    console.log()
    console.log('next:')
    if (root !== process.cwd()) console.log(color.gray(`  cd ${relative(process.cwd(), root)}`))
    console.log(color.gray('  pnpm install'))
    console.log(color.gray('  pnpm build'))
    console.log(color.gray('  pnpm dev      # with the app running and developer mode on'))
  },
})
