import { $ } from 'zx'
import { worktreeDir } from './config.js'
import { cd, getPatchCommitId, resolvePatchName, step, success } from './lib.js'

const args = process.argv.slice(2)
let useIndex = false
const positional: string[] = []
for (const arg of args) {
  if (arg === '--index' || arg === '-i') {
    useIndex = true
  } else if (arg === '--help' || arg === '-h') {
    console.log('Usage: pnpm tsx scripts/append-to-patch.ts <patch-name> [--index]')
    process.exit(0)
  } else {
    positional.push(arg)
  }
}
if (positional.length !== 1) {
  throw new Error('Usage: append-to-patch <patch-name> [--index]')
}

const target = await resolvePatchName(worktreeDir, positional[0])
const repo = cd(worktreeDir)

const targetCommit = await getPatchCommitId(worktreeDir, target)
const targetMessage = (await repo`git log -1 --format=%B ${targetCommit}`).stdout.replace(/\n+$/, '')

const tempName = `_append_${Date.now()}`
step(`Creating temp patch ${tempName}`)
await repo`stg new --message ${`append to ${target}`} ${tempName}`

step(`Refreshing temp patch${useIndex ? ' (--index)' : ''}`)
const refreshArgs = useIndex ? ['--index'] : []
const refresh = await $({ cwd: worktreeDir, nothrow: true })`stg refresh ${refreshArgs}`
if (refresh.exitCode !== 0) {
  await repo`stg delete ${tempName}`
  console.error(refresh.stderr.trim() || refresh.stdout.trim())
  throw new Error('stg refresh failed')
}

step(`Squashing ${tempName} into ${target}`)
const squash = await $({ cwd: worktreeDir, nothrow: true, stdio: 'inherit' })`stg squash --name ${target} --message ${targetMessage} ${target} ${tempName}`
if (squash.exitCode !== 0) {
  console.error(`
stg squash failed — likely a conflict while reapplying patches above ${target}.
to recover:
  1. resolve conflicts in the worktree
  2. stg refresh           (folds the resolution into the conflicting patch)
  3. stg push -a           (push remaining patches back)
if you want to abort instead:
  stg undo                 (rolls the squash back; may need --hard)
`.trim())
  process.exit(squash.exitCode ?? 1)
}

success(`Appended changes to ${target}`)
