import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import { join } from 'node:path'
import { parallelMap } from '@fuman/utils'
import { glob } from 'tinyglobby'
import { $, chalk } from 'zx'
import { rootDir } from '../config.js'
import { step, success, warn } from '../lib.js'

// publishes @inugram/plugin-types and @inugram/cli. the typings and the grant catalogue are
// generated from the worktree, so this only runs where `pnpm run setup` has: see release.yml.
//
// version: `<app major>.<app minor>.<build number>`. the major and minor track the app release the
// typings came out of; the patch is the build number, which is what keeps it monotonic when two
// releases share an app version name.

$.verbose = false

interface Package {
  dir: string
  name: string
  /** the github variable holding the hash of what was published last */
  hashVar: string
  /** what a republish is worth: if none of it changed, the last version still describes this build */
  contents: string[]
  /** puts the package in its published shape and answers with the directory to publish from */
  prepare: (version: string) => Promise<string>
}

const typesDir = join(rootDir, 'sdk/types')
const cliDir = join(rootDir, 'sdk/cli')

const PACKAGES: Package[] = [
  {
    dir: typesDir,
    name: '@inugram/plugin-types',
    hashVar: 'SDK_TYPES_HASH',
    contents: ['*.d.ts', 'grants.json', 'tl-names.txt', 'tsconfig.json', 'tsconfig.js.json', 'README.md'],
    async prepare(version) {
      await setVersion(typesDir, version)
      return typesDir
    },
  },
  {
    dir: cliDir,
    name: '@inugram/cli',
    hashVar: 'SDK_CLI_HASH',
    contents: ['src/**/*', 'vite.config.ts', 'package.json', 'README.md'],
    async prepare(version) {
      // fuman-build writes the published package.json itself, version included, so nothing in the
      // working tree is touched
      await $({ cwd: cliDir, verbose: true, env: { ...process.env, INU_SDK_VERSION: version } })`pnpm run build`
      return join(cliDir, 'dist')
    },
  },
]

async function readVersion(): Promise<string> {
  const props = await fs.readFile(join(rootDir, 'worktree/gradle.properties'), 'utf8')
  const appVerName = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
  if (!appVerName) throw new Error('failed to read APP_VERSION_NAME')
  const [major, minor] = appVerName.split('.')
  const build = process.env.INU_BUILD ?? '1'
  if (!/^\d+$/.test(build)) throw new Error(`invalid INU_BUILD: ${build}`)
  if (!/^\d+$/.test(major) || !/^\d+$/.test(minor)) throw new Error(`cannot read a version out of ${appVerName}`)
  return `${major}.${minor}.${build}`
}

/** over the published contents, in a stable order, path included so a rename counts as a change */
async function hashOf(pkg: Package): Promise<string> {
  const files = (await glob(pkg.contents, { cwd: pkg.dir, dot: true })).sort()
  if (files.length === 0) throw new Error(`${pkg.name} would publish nothing; did \`pnpm run setup\` run?`)
  const bodies = await parallelMap(files, file => fs.readFile(join(pkg.dir, file)))
  const digest = createHash('sha256')
  for (const [index, file] of files.entries()) {
    digest.update(file)
    digest.update(bodies[index])
  }
  return digest.digest('hex')
}

async function setVersion(dir: string, version: string) {
  const path = join(dir, 'package.json')
  const manifest = JSON.parse(await fs.readFile(path, 'utf8')) as { version: string }
  manifest.version = version
  await fs.writeFile(path, `${JSON.stringify(manifest, null, 2)}\n`)
}

async function storeHash(pkg: Package, hash: string) {
  if (!process.env.GH_TOKEN) {
    warn(`no GH_TOKEN, so ${pkg.hashVar} was not updated; the next run will publish again`)
    return
  }
  await $`gh variable set ${pkg.hashVar} --body ${hash}`
}

const version = await readVersion()
const dryRun = process.argv.includes('--dry-run')
step(`sdk version ${chalk.bold(version)}${dryRun ? ' (dry run)' : ''}`)

for (const pkg of PACKAGES) {
  const hash = await hashOf(pkg)
  // the typings' hash covers their own package.json, which carries the version we are about to
  // write, so it is taken before the bump: otherwise every run would look changed
  if (process.env[pkg.hashVar] === hash) {
    step(`${pkg.name} unchanged since the last publish, skipping`)
    continue
  }
  if (dryRun) {
    success(`${pkg.name}@${version} would be published`)
    continue
  }
  const publishDir = await pkg.prepare(version)
  await $({ cwd: publishDir, verbose: true })`pnpm publish --access public --no-git-checks`
  await storeHash(pkg, hash)
  success(`${pkg.name}@${version} published`)
}
