import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { parallelMap } from '@fuman/utils'
import { patchesDir, rootDir, worktreeDir } from './config.js'
import {
  cd,
  ensureDir,
  getAppliedPatchNames,
  getPatchCommitId,
  resolveFromRoot,
  step,
  success,
  warn,
  writeSeries,
} from './lib.js'

function parsePatchName(patchName: string) {
  const parts = patchName.split('__').map(part => part.trim()).filter(Boolean)
  if (parts.length !== 2) {
    throw new Error(`Patch name must use "group__name": ${patchName}`)
  }
  const [group, name] = parts
  return {
    group,
    name,
    seriesEntry: `${group}/${name}.patch`,
  }
}

async function listFilesRecursive(dir: string): Promise<string[]> {
  const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => [])
  const files: string[] = []

  for (const entry of entries) {
    const fullPath = join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...await listFilesRecursive(fullPath))
      continue
    }
    files.push(fullPath)
  }

  return files
}

async function clearExportedPatchFiles() {
  for (const file of await listFilesRecursive(patchesDir)) {
    if (file.endsWith('.patch')) {
      await fs.unlink(file)
    }
  }
}

async function exportPatchFile(repoDir: string, patchName: string) {
  const commitId = await getPatchCommitId(repoDir, patchName)
  const parsed = parsePatchName(patchName)
  const git = cd(repoDir)

  const files = (await git`git diff-tree --no-commit-id --name-only -r ${commitId}`)
    .stdout
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(Boolean)

  if (files.length === 0) {
    warn(`Patch ${patchName} is empty`)
  }

  const targetDir = join(patchesDir, parsed.group)
  const targetFile = join(targetDir, `${parsed.name}.patch`)
  await ensureDir(targetDir)

  step(`Exporting ${patchName} -> ${relative(rootDir, targetFile)}`)
  const patch = await git`git format-patch --stdout --zero-commit --no-signature --subject-prefix= -1 ${commitId}`
  const stable = patch.stdout.replace(/^index [0-9a-f]+\.\.[0-9a-f]+( \d+)?$/gm, 'index 0000000..0000000$1')
  await fs.writeFile(targetFile, stable)

  return parsed
}

const repoDir = resolveFromRoot(process.argv[2], worktreeDir)

const dirty = (await cd(repoDir)`git status --porcelain`).stdout.trim()
if (dirty) {
  warn(`Worktree has uncommitted changes:\n${dirty}`)
}

const patchNames = await getAppliedPatchNames(repoDir)
await clearExportedPatchFiles()

const entries = await parallelMap(patchNames, async (patchName) => {
  const patch = await exportPatchFile(repoDir, patchName)
  return patch.seriesEntry
})

await writeSeries(entries)

// warn again because it might get lost in logs
if (dirty) {
  warn(`Worktree has uncommitted changes:\n${dirty}`)
}

success(`Exported ${entries.length} ${entries.length === 1 ? 'patch' : 'patches'}`)
