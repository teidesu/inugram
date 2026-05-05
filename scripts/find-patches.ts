import { isAbsolute, relative, resolve } from 'node:path'
import { chalk } from 'zx'
import { worktreeDir } from './config.js'
import {
  cd,
  getAllPatchCommitIds,
  getAppliedPatchNames,
} from './lib.js'

const input = process.argv[2]
if (!input) {
  throw new Error('File path (absolute or worktree-relative) required')
}

const absPath = isAbsolute(input) ? input : resolve(process.cwd(), input)
const repoRelative = relative(worktreeDir, absPath).replaceAll('\\', '/')
if (!repoRelative || repoRelative.startsWith('..')) {
  throw new Error(`Path is not inside worktree: ${absPath}`)
}

const git = cd(worktreeDir)
const [patches, allCommitIds] = await Promise.all([
  getAppliedPatchNames(worktreeDir),
  getAllPatchCommitIds(worktreeDir),
])

interface Hit {
  patch: string
  added: number
  deleted: number
}

const shaToPatch = new Map<string, string>()
const commits: string[] = []
for (const patch of patches) {
  const sha = allCommitIds.get(patch)
  if (!sha) continue
  shaToPatch.set(sha, patch)
  commits.push(sha)
}

const hits: Hit[] = []
if (commits.length > 0) {
  const range = `${commits[0]}^..${commits[commits.length - 1]}`
  const out = (await git`git log --numstat --format=%H ${range} -- ${repoRelative}`).stdout
  let currentPatch: string | null = null
  for (const line of out.split(/\r?\n/)) {
    if (!line) continue
    if (/^[0-9a-f]{40}$/.test(line)) {
      currentPatch = shaToPatch.get(line) ?? null
      continue
    }
    if (!currentPatch) continue
    const [addRaw, delRaw] = line.split('\t')
    const added = addRaw === '-' ? 0 : Number.parseInt(addRaw, 10) || 0
    const deleted = delRaw === '-' ? 0 : Number.parseInt(delRaw, 10) || 0
    hits.push({ patch: currentPatch, added, deleted })
  }
}

if (hits.length === 0) {
  console.log(`No applied patches touch ${repoRelative}`)
  process.exit(0)
}

const nameWidth = Math.max(...hits.map(h => h.patch.length))
for (const hit of hits) {
  console.log(
    `${hit.patch.padEnd(nameWidth)}  ${chalk.green(`+${hit.added}`)} ${chalk.red(`-${hit.deleted}`)}`,
  )
}
