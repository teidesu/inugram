import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { parallelMap } from '@fuman/utils'
import { chalk } from 'zx'
import { patchesDir, worktreeDir } from '../config.js'
import {
  generateStablePatchFromCommit,
  getAllPatchCommitIds,
  getAppliedPatchNames,
  parsePatchName,
} from '../lib.js'

if (process.env.SKIP_PATCH_CHECK) {
  process.exit(0)
}

async function listExportedPatchFiles(): Promise<string[]> {
  const out: string[] = []
  const walk = async (dir: string) => {
    const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => [])
    for (const entry of entries) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        await walk(full)
      } else if (entry.name.endsWith('.patch')) {
        out.push(full)
      }
    }
  }
  await walk(patchesDir)
  return out
}

const [applied, commitIds] = await Promise.all([
  getAppliedPatchNames(worktreeDir),
  getAllPatchCommitIds(worktreeDir),
])
const expected = new Map<string, { patchName: string, commitId: string }>() // seriesEntry -> info
for (const name of applied) {
  const commitId = commitIds.get(name)
  if (!commitId) throw new Error(`No commit id for applied patch ${name}`)
  expected.set(parsePatchName(name).seriesEntry, { patchName: name, commitId })
}

const exportedFiles = await listExportedPatchFiles()
const exportedEntries = new Set(
  exportedFiles.map(f => relative(patchesDir, f).replaceAll('\\', '/')),
)

const missing: string[] = []
const orphaned: string[] = []
const drifted: string[] = []

const checks = await parallelMap(
  [...expected],
  async ([entry, { patchName, commitId }]): Promise<{ kind: 'missing' | 'drift' | 'ok', entry: string, patchName: string }> => {
    if (!exportedEntries.has(entry)) {
      return { kind: 'missing', entry, patchName }
    }
    const [actual, stable] = await Promise.all([
      fs.readFile(join(patchesDir, entry), 'utf8'),
      generateStablePatchFromCommit(worktreeDir, commitId),
    ])
    return { kind: actual === stable ? 'ok' : 'drift', entry, patchName }
  },
)

for (const r of checks) {
  if (r.kind === 'missing') missing.push(`${r.patchName} (expected patches/${r.entry})`)
  else if (r.kind === 'drift') drifted.push(`${r.patchName} (patches/${r.entry})`)
}

for (const entry of exportedEntries) {
  if (!expected.has(entry)) {
    orphaned.push(`patches/${entry}`)
  }
}

if (missing.length === 0 && orphaned.length === 0 && drifted.length === 0) {
  process.exit(0)
}

function print(label: string, items: string[]) {
  if (items.length === 0) return
  console.error(chalk.red(`${label}:`))
  for (const item of items) console.error(`  - ${item}`)
}

console.error(chalk.red('patches/ is out of sync with the stgit stack:'))
print('missing from patches/', missing)
print('not in stgit stack', orphaned)
print('content drift', drifted)
console.error()
console.error(chalk.yellow('hint: run `pnpm export` to refresh,'))
console.error(chalk.yellow('      or skip this check entirely via --no-verify'))
process.exit(1)
