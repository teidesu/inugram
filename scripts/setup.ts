import fs from 'node:fs/promises'
import { join } from 'node:path'
import { ICON_SELECTION, patchesDir, worktreeDir } from './config.js'
import { generateLauncherIcon } from './launcher-icons.js'
import {
  cd,
  cloneUpstream,
  ensureDir,
  ensureGitExclude,
  ensureUpstreamRemote,
  getAllPatchNames,
  getAppliedPatchNames,
  hasGitRepo,
  hasLocalBranch,
  hasStgitStack,
  linkForkSource,
  patchNameFromSeriesEntry,
  readPinnedUpstreamCommit,
  readSeries,
  step,
  success,
} from './lib.js'
import { svgBodyToVectorDrawable } from './svg-to-vector.js'

const BRANCH = 'inugram'

function sameOrder(actual: string[], expected: string[]) {
  return actual.length === expected.length && actual.every((value, index) => value === expected[index])
}

async function ensureWorktree(commit: string) {
  if (!hasGitRepo(worktreeDir)) {
    await cloneUpstream(worktreeDir, commit)
    return
  }
  step(`Reusing existing checkout in ${worktreeDir}`)
  await ensureUpstreamRemote(worktreeDir)
}

async function ensureBranch(commit: string) {
  const repo = cd(worktreeDir)
  const existed = await hasLocalBranch(worktreeDir, BRANCH)

  if (existed) {
    step(`Checking out ${BRANCH}`)
    await repo`git checkout ${BRANCH}`
  } else {
    step(`Creating local work branch at ${commit}`)
    await repo`git checkout -B ${BRANCH} ${commit}`
  }

  return existed
}

async function ensureStgitStack(commit: string, branchExisted: boolean) {
  const repo = cd(worktreeDir)
  await repo`git config stgit.namelength 120`
  await repo`git config commit.gpgsign false`
  await repo`git config tag.gpgsign false`

  if (hasStgitStack(worktreeDir, BRANCH)) {
    step('Stgit stack already initialized')
    return
  }

  if (branchExisted) {
    const head = (await repo`git rev-parse HEAD`).stdout.trim()
    if (head !== commit) {
      throw new Error(`Branch ${BRANCH} exists without StGit metadata and is not at ${commit}`)
    }
  }

  step('Initializing stgit')
  await repo`stg init`
}

async function generateIconDrawables(repoDir: string) {
  const outDir = join(repoDir, 'TMessagesProj/src/main/res/drawable')
  await ensureDir(outDir)
  let dirty = false
  for (const { pack, icons, options } of ICON_SELECTION) {
    for (const name of icons) {
      const icon = pack.icons[name]
      if (!icon) throw new Error(`Icon not found: ${pack}/${name}`)

      const xml = svgBodyToVectorDrawable(icon.body, pack.width!, pack.height!, options)
      const fileName = `inu_${pack.prefix}_${name.replaceAll('-', '_')}.xml`
      const absPath = join(outDir, fileName)
      const stat = await fs.lstat(absPath).catch(() => null)

      if (stat?.isSymbolicLink()) {
        await fs.unlink(absPath)
      }

      const current = stat?.isFile() ? await fs.readFile(absPath, 'utf8').catch(() => null) : null
      if (current !== xml) {
        step(`Generating ${fileName}`)
        await fs.writeFile(absPath, xml)
        dirty = true
      }
      await ensureGitExclude(repoDir, `TMessagesProj/src/main/res/drawable/${fileName}`)
    }
  }
  return dirty
}

async function importSeries(seriesEntries: string[]) {
  const repo = cd(worktreeDir)
  for (const entry of seriesEntries) {
    const patchName = patchNameFromSeriesEntry(entry)
    step(`Importing ${entry} as ${patchName}`)
    await repo`stg import -n ${patchName} ${join(patchesDir, entry)}`
  }
}

function isPrefix(prefix: string[], full: string[]) {
  return prefix.length <= full.length && prefix.every((name, i) => name === full[i])
}

async function ensurePatches(expected: string[], seriesEntries: string[]) {
  const repo = cd(worktreeDir)
  const existing = await getAllPatchNames(worktreeDir)

  if (existing.length === 0) {
    await importSeries(seriesEntries)
  } else if (sameOrder(existing, expected)) {
    // up to date
  } else if (isPrefix(existing, expected)) {
    const applied = await getAppliedPatchNames(worktreeDir)
    if (!sameOrder(applied, existing)) {
      step('Pushing existing patches before import')
      await repo`stg push -a`
    }
    await importSeries(seriesEntries.slice(existing.length))
  } else {
    throw new Error('Existing StGit stack diverges from series (use --force to reset)')
  }

  const applied = await getAppliedPatchNames(worktreeDir)
  if (!sameOrder(applied, expected)) {
    step('Pushing remaining patches')
    await repo`stg push -a`
  }
}

async function forceReimportPatches(seriesEntries: string[]) {
  const repo = cd(worktreeDir)

  const dirty = (await repo`git status --porcelain`).stdout.trim()
  if (dirty) {
    throw new Error(`Refusing to force-reimport: worktree is dirty: ${dirty}`)
  }

  const existing = await getAllPatchNames(worktreeDir)
  const applied = await getAppliedPatchNames(worktreeDir)
  if (applied.length !== existing.length) {
    throw new Error(`Refusing to force-reimport: ${existing.length - applied.length} patch(es) above top not applied`)
  }

  if (existing.length > 0) {
    step(`Popping ${existing.length} patch(es)`)
    await repo`stg pop -a`
    for (const name of existing) {
      step(`Deleting ${name}`)
      await repo`stg delete ${name}`
    }
  }

  await importSeries(seriesEntries)
}

const args = process.argv.slice(2)
const force = args.includes('--force')
const noStgit = args.includes('--no-stgit')

const commit = await readPinnedUpstreamCommit()
const seriesEntries = await readSeries()

if (noStgit) {
  if (hasGitRepo(worktreeDir)) {
    throw new Error(`--no-stgit needs a fresh worktree (${worktreeDir} already exists)`)
  }
  await cloneUpstream(worktreeDir, commit)
  const repo = cd(worktreeDir)
  for (const entry of seriesEntries) {
    step(`Applying ${entry}`)
    await repo`git apply ${join(patchesDir, entry)}`
  }
  await linkForkSource(worktreeDir)
  await generateIconDrawables(worktreeDir)
  await generateLauncherIcon(worktreeDir)
  success('Flat setup complete')
} else {
  const expectedPatches = seriesEntries.map(patchNameFromSeriesEntry)
  await ensureWorktree(commit)
  const branchExisted = await ensureBranch(commit)
  await ensureStgitStack(commit, branchExisted)
  if (force) {
    await forceReimportPatches(seriesEntries)
  } else {
    await ensurePatches(expectedPatches, seriesEntries)
  }
  const linkedAny = await linkForkSource(worktreeDir)
  const generatedAny = await generateIconDrawables(worktreeDir)
  const launcherAny = await generateLauncherIcon(worktreeDir)
  success(linkedAny || generatedAny || launcherAny ? 'Setup complete' : 'Up to date')
}
