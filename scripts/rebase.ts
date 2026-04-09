import { $ } from 'zx'
import { worktreeDir } from './config.js'
import {
  cd,
  getAllPatchNames,
  step,
  success,
  warn,
  writePinnedUpstreamCommit,
} from './lib.js'

const target = process.argv[2]
if (!target) {
  throw new Error("New upstream commit or 'latest' required")
}

const repo = cd(worktreeDir)
step('Fetching upstream')
await repo`git fetch upstream`

const resolvedCommit = target === 'latest'
  ? (await repo`git rev-parse upstream/HEAD`).stdout.trim()
  : (await repo`git rev-parse ${target}^{commit}`).stdout.trim()

await writePinnedUpstreamCommit(resolvedCommit)

step(`Rebasing stack onto ${resolvedCommit}`)
const result = await $({ cwd: worktreeDir, nothrow: true })`stg rebase ${resolvedCommit}`

if (result.exitCode !== 0) {
  const patchNames = await getAllPatchNames(worktreeDir).catch(() => [])
  const conflicts = patchNames.filter(patchName =>
    result.stdout.includes(patchName) || result.stderr.includes(patchName),
  )

  if (conflicts.length > 0) {
    warn(`Conflicts in: ${conflicts.join(', ')}`)
  } else {
    warn('Rebase reported conflicts')
  }

  if (result.stderr.trim()) {
    console.error(result.stderr.trim())
  }

  process.exit(result.exitCode)
}

success(`Rebased onto ${resolvedCommit}`)
