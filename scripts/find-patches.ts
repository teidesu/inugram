import { isAbsolute, relative, resolve } from 'node:path'
import { chalk } from 'zx'
import { worktreeDir } from './config.js'
import {
  cd,
  getAppliedPatchNames,
  getPatchCommitId,
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
const patches = await getAppliedPatchNames(worktreeDir)

interface Hit {
  patch: string
  added: number
  deleted: number
}

const hits: Hit[] = []
for (const patch of patches) {
  const commit = await getPatchCommitId(worktreeDir, patch)
  const out = (await git`git show --numstat --format= ${commit} -- ${repoRelative}`).stdout.trim()
  if (!out) continue

  for (const line of out.split(/\r?\n/)) {
    const [addRaw, delRaw] = line.split('\t')
    const added = addRaw === '-' ? 0 : Number.parseInt(addRaw, 10) || 0
    const deleted = delRaw === '-' ? 0 : Number.parseInt(delRaw, 10) || 0
    hits.push({ patch, added, deleted })
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
