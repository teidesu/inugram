import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { glob } from 'tinyglobby'
import { $, chalk } from 'zx'
import {
  forkSyncFiles,
  rootDir,
  seriesFile,
  upstreamCommitFile,
  upstreamUrl,
} from './config.js'

$.verbose = false

export function step(message: string) {
  console.log(`${chalk.blue('==>')} ${message}`)
}

export function success(message: string) {
  console.log(`${chalk.green('ok')} ${message}`)
}

export function warn(message: string) {
  console.log(`${chalk.yellow('warn')} ${message}`)
}

export function cd(cwd: string) {
  return $({ cwd })
}

export function resolveFromRoot(input: string | undefined, fallback?: string) {
  if (!input) {
    if (fallback) {
      return fallback
    }
    throw new Error('Missing required path argument')
  }
  return isAbsolute(input) ? input : resolve(rootDir, input)
}

export async function readPinnedUpstreamCommit() {
  const value = (await fs.readFile(upstreamCommitFile, 'utf8')).trim()
  if (!/^[0-9a-f]{7,40}$/i.test(value)) {
    throw new Error(`Set ${relative(rootDir, upstreamCommitFile)} to a real commit hash first`)
  }
  return value
}

export async function writePinnedUpstreamCommit(commit: string) {
  await fs.writeFile(upstreamCommitFile, `${commit}\n`)
}

export async function ensureEmptyCloneTarget(targetDir: string) {
  if (!existsSync(targetDir)) {
    return
  }
  const entries = await fs.readdir(targetDir)
  if (entries.length > 0 && !entries.includes('.git')) {
    throw new Error(`Target exists and is not empty: ${targetDir}`)
  }
}

export async function ensureDir(dir: string) {
  await fs.mkdir(dir, { recursive: true })
}

export async function cloneUpstream(targetDir: string, commit: string) {
  await ensureEmptyCloneTarget(targetDir)
  if (!existsSync(join(targetDir, '.git'))) {
    step(`Cloning upstream into ${targetDir}`)
    await $`git clone ${upstreamUrl} ${targetDir}`
  } else {
    step(`Reusing existing checkout in ${targetDir}`)
  }
  await ensureUpstreamRemote(targetDir)
  const git = cd(targetDir)
  step(`Checking out ${commit}`)
  await git`git checkout ${commit}`
}

export async function ensureUpstreamRemote(repoDir: string) {
  const git = cd(repoDir)
  const remotes = (await git`git remote`)
    .stdout
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(Boolean)

  if (remotes.includes('upstream')) {
    return
  }

  if (remotes.includes('origin')) {
    step('Renaming origin remote to upstream')
    await git`git remote rename origin upstream`
    return
  }

  step('Adding upstream remote')
  await git`git remote add upstream ${upstreamUrl}`
}

export function hasGitRepo(repoDir: string) {
  return existsSync(join(repoDir, '.git'))
}

export function hasStgitStack(repoDir: string, branch: string) {
  return existsSync(join(repoDir, '.git', 'refs', 'stacks', branch))
}

export async function getCurrentBranch(repoDir: string) {
  const result = await $({ cwd: repoDir, nothrow: true })`git rev-parse --abbrev-ref HEAD`
  if (result.exitCode !== 0) {
    return null
  }
  return result.stdout.trim()
}

export async function hasLocalBranch(repoDir: string, branch: string) {
  const result = await $({ cwd: repoDir, nothrow: true })`git show-ref --verify --quiet refs/heads/${branch}`
  return result.exitCode === 0
}

async function isTrackedPath(repoDir: string, repoRelativePath: string) {
  const result = await $({ cwd: repoDir, nothrow: true })`git ls-files --error-unmatch -- ${repoRelativePath}`
  return result.exitCode === 0
}

async function ensureSkipWorktree(repoDir: string, repoRelativePath: string) {
  if (!await isTrackedPath(repoDir, repoRelativePath)) {
    return false
  }

  const status = (await $({ cwd: repoDir })`git ls-files -v -- ${repoRelativePath}`).stdout.trim()
  if (status.startsWith('S ')) {
    return true
  }

  step(`Marking ${repoRelativePath} as skip-worktree`)
  await $({ cwd: repoDir })`git update-index --skip-worktree -- ${repoRelativePath}`
  return true
}

async function ensureSymlink(targetPath: string, srcPath: string, type: 'dir' | 'file') {
  await ensureDir(dirname(targetPath))

  const stat = await fs.lstat(targetPath).catch(() => null)
  if (stat) {
    if (stat.isSymbolicLink()) {
      const currentTarget = resolve(dirname(targetPath), await fs.readlink(targetPath))
      if (currentTarget === srcPath) return false
    }

    await fs.rm(targetPath, { recursive: true, force: true })
  }

  await fs.symlink(relative(dirname(targetPath), srcPath), targetPath, type)
  return true
}

interface ResolvedLink {
  sourcePath: string
  repoRelativeTarget: string
  type: 'dir' | 'file'
  replace?: boolean
}

async function linkForkEntry(repoDir: string, entry: ResolvedLink) {
  const targetPath = join(repoDir, entry.repoRelativeTarget)
  const created = await ensureSymlink(targetPath, entry.sourcePath, entry.type)
  if (created) {
    step(`Symlinking ${targetPath}`)
  }

  if (entry.replace && await ensureSkipWorktree(repoDir, entry.repoRelativeTarget)) {
    return created
  }

  await ensureGitExclude(repoDir, entry.repoRelativeTarget)
  return created
}

export async function linkForkSource(repoDir: string) {
  let dirty = false

  for (const entry of forkSyncFiles) {
    if (entry.directory) {
      const created = await linkForkEntry(repoDir, {
        sourcePath: resolve(rootDir, entry.source),
        repoRelativeTarget: entry.target,
        type: 'dir',
        replace: entry.replace,
      })
      dirty ||= created
      continue
    }

    const matches = await glob(entry.source, { cwd: rootDir, absolute: true, onlyFiles: true })
    for (const sourcePath of matches) {
      const created = await linkForkEntry(repoDir, {
        sourcePath,
        repoRelativeTarget: join(entry.target, basename(sourcePath)),
        type: 'file',
        replace: entry.replace,
      })
      dirty ||= created
    }
  }

  return dirty
}

export async function ensureGitExclude(repoDir: string, repoRelativePath: string) {
  const excludeFile = join(repoDir, '.git', 'info', 'exclude')
  const entry = repoRelativePath.replaceAll('\\', '/')
  const current = await fs.readFile(excludeFile, 'utf8').catch(() => '')
  const lines = current.split(/\r?\n/)

  if (lines.includes(entry)) {
    return
  }

  step(`Adding ${entry} to .git/info/exclude`)
  const next = current.length === 0 || current.endsWith('\n')
    ? `${current}${entry}\n`
    : `${current}\n${entry}\n`
  await fs.writeFile(excludeFile, next)
}

function normalizeSeriesLine(line: string) {
  const trimmed = line.trim()
  if (!trimmed) {
    return ''
  }
  return trimmed.replace(/^[+>!-]\s+/, '')
}

async function getPatchNames(repoDir: string, mode: '--applied' | '--all') {
  const stg = cd(repoDir)
  const out = await stg`stg series ${mode}`
  return out.stdout
    .split(/\r?\n/)
    .map(normalizeSeriesLine)
    .map(line => line.trim())
    .filter(Boolean)
}

export async function getAppliedPatchNames(repoDir: string) {
  return await getPatchNames(repoDir, '--applied')
}

export async function getAllPatchNames(repoDir: string) {
  return await getPatchNames(repoDir, '--all')
}

export function patchNameFromSeriesEntry(entry: string) {
  const normalized = entry.trim().replaceAll('\\', '/')
  const match = normalized.match(/^([^/]+)\/(.+)\.patch$/)
  if (!match) {
    throw new Error(`Invalid series entry: ${entry}`)
  }
  return `${match[1]}__${match[2]}`
}

export async function getTopPatch(repoDir: string) {
  const result = await $({ cwd: repoDir, nothrow: true })`stg top`
  if (result.exitCode !== 0) {
    return null
  }
  return result.stdout.trim()
}

export async function getPatchCommitId(repoDir: string, patchName: string) {
  return (await cd(repoDir)`stg id ${patchName}`).stdout.trim()
}

export async function getPatchSubject(repoDir: string, patchName: string) {
  const commitId = await getPatchCommitId(repoDir, patchName)
  return (await cd(repoDir)`git log -1 --format=%s ${commitId}`).stdout.trim()
}

export async function writeSeries(entries: string[]) {
  await fs.writeFile(seriesFile, entries.length > 0 ? `${entries.join('\n')}\n` : '')
  step(`Wrote ${entries.length} ${entries.length === 1 ? 'entry' : 'entries'} to series`)
}

export async function readSeries() {
  const raw = await fs.readFile(seriesFile, 'utf8').catch(() => '')
  return raw
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(Boolean)
}

export async function resolvePatchName(repoDir: string, identifier: string) {
  const patchNames = await getAllPatchNames(repoDir)
  const direct = patchNames.find(patchName => patchName === identifier)
  if (direct) {
    return direct
  }

  const fallback = identifier.includes('/')
    ? patchNames.find(patchName => patchName === identifier.replace('/', '__'))
    : null

  if (fallback) {
    return fallback
  }

  throw new Error(`Unknown patch identifier: ${identifier}`)
}
