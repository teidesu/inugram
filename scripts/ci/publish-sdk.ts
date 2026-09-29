import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import { join } from 'node:path'
import { parallelMap } from '@fuman/utils'
import { glob } from 'tinyglobby'
import { $, chalk } from 'zx'
import { rootDir } from '../config.js'
import { step, success, warn } from '../lib.js'

// publishes @inugram/plugin-types and @inugram/cli. the typings are generated from the worktree, so
// this runs where `pnpm run setup` has, or, in release.yml, over the files the build job produced.
// the registry is npm's own config, so `npm_config_registry` points it elsewhere.
//
// version: `<build number>.0.0`, the app's own version code, so a package names the build it came
// out of. stock's version name says nothing about the plugin api. both packages always publish
// together: `inu init` from cli@N depends on plugin-types@^N.0.0.

$.verbose = false

interface Package {
  dir: string
  name: string
  /** what a republish is worth: if none of it changed in either package, the last version still describes this build */
  contents: string[]
  /**
   * puts the package in its published shape and answers with the directory to publish from, and
   * with what puts the working tree back once the publish is over
   */
  prepare: (version: string) => Promise<Prepared>
}

interface Prepared {
  dir: string
  restore: () => Promise<void>
}

const typesDir = join(rootDir, 'sdk/types')
const cliDir = join(rootDir, 'sdk/cli')

const PACKAGES: Package[] = [
  {
    dir: typesDir,
    name: '@inugram/plugin-types',
    contents: ['*.d.ts', 'grants.json', 'tl-names.txt', 'tsconfig.json', 'tsconfig.js.json', 'README.md'],
    async prepare(version) {
      const path = join(typesDir, 'package.json')
      const original = await fs.readFile(path, 'utf8')
      const manifest = JSON.parse(original) as { version: string }
      manifest.version = version
      await fs.writeFile(path, `${JSON.stringify(manifest, null, 2)}\n`)
      return { dir: typesDir, restore: () => fs.writeFile(path, original) }
    },
  },
  {
    dir: cliDir,
    name: '@inugram/cli',
    contents: ['src/**/*', 'vite.config.ts', 'package.json', 'README.md'],
    async prepare(version) {
      // fuman-build writes the published package.json itself, version included, so nothing in the
      // working tree is touched
      await $({ cwd: cliDir, verbose: true, env: { ...process.env, INU_SDK_VERSION: version } })`pnpm run build`
      return { dir: join(cliDir, 'dist'), restore: async () => {} }
    },
  },
]

function readVersion(): string {
  const build = process.env.INU_BUILD ?? '1'
  if (!/^[1-9]\d*$/.test(build)) throw new Error(`invalid INU_BUILD: ${build}`)
  return `${build}.0.0`
}

/** over the published contents, in a stable order, path included so a rename counts as a change */
async function hashPublishedContents(): Promise<string> {
  const digest = createHash('sha256')
  for (const pkg of PACKAGES) {
    const files = (await glob(pkg.contents, { cwd: pkg.dir, dot: true })).sort()
    if (files.length === 0) throw new Error(`${pkg.name} would publish nothing; did \`pnpm run setup\` run?`)
    const bodies = await parallelMap(files, file => fs.readFile(join(pkg.dir, file)))
    for (const [index, file] of files.entries()) {
      digest.update(`${pkg.name}/${file}`)
      digest.update(bodies[index])
    }
  }
  return digest.digest('hex')
}

async function storeHash(hash: string) {
  if (!process.env.GH_TOKEN) {
    warn('no GH_TOKEN, so SDK_HASH was not updated; the next run will publish again')
    return
  }
  await $`gh variable set SDK_HASH --body ${hash}`
}

const version = readVersion()
const dryRun = process.argv.includes('--dry-run')
step(`sdk version ${chalk.bold(version)}${dryRun ? ' (dry run)' : ''}`)

// the typings' hash covers their own package.json, which carries the version we are about to
// write, so it is taken before the bump: otherwise every run would look changed
const hash = await hashPublishedContents()
if (process.env.SDK_HASH === hash) {
  step('sdk unchanged since the last publish, skipping')
  process.exit(0)
}

for (const pkg of PACKAGES) {
  const published = await $({ nothrow: true })`npm view ${pkg.name}@${version} version`
  if (published.stdout.trim() === version) {
    step(`${pkg.name}@${version} is already on the registry, skipping`)
    continue
  }
  if (dryRun) {
    success(`${pkg.name}@${version} would be published`)
    continue
  }
  const prepared = await pkg.prepare(version)
  try {
    await $({ cwd: prepared.dir, verbose: true })`pnpm publish --access public --no-git-checks`
  } finally {
    await prepared.restore()
  }
  success(`${pkg.name}@${version} published`)
}
if (!dryRun) await storeHash(hash)
