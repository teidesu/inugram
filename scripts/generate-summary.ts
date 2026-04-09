import fs from 'node:fs/promises'
import { join } from 'node:path'
import { patchesDir, rootDir, seriesFile } from './config.js'
import { success } from './lib.js'

const kotlinDir = join(rootDir, 'src/kotlin')
const resDir = join(rootDir, 'src/res')

interface PatchInfo {
  entry: string
  subject: string
  added: number
  removed: number
}

function parseSubject(lines: string[]) {
  const idx = lines.findIndex(line => line.startsWith('Subject: '))
  if (idx === -1) return ''

  const parts = [lines[idx].slice('Subject: '.length)]
  for (let i = idx + 1; i < lines.length; i++) {
    const line = lines[i]
    if (line === '' || line.startsWith('---')) break
    if (!line.startsWith(' ') && !line.startsWith('\t')) break
    parts.push(line.trim())
  }
  return parts.join(' ').replace(/^\[PATCH(?:\s+\d+\/\d+)?\]\s*/, '').trim()
}

function countDiff(lines: string[]) {
  let added = 0
  let removed = 0
  let inHunk = false

  for (const line of lines) {
    if (line.startsWith('@@')) {
      inHunk = true
      continue
    }
    if (!inHunk) continue
    if (line.startsWith('diff --git')) {
      inHunk = false
      continue
    }
    if (line.startsWith('+++') || line.startsWith('---')) continue
    if (line.startsWith('+')) added++
    else if (line.startsWith('-')) removed++
  }

  return { added, removed }
}

async function readPatch(entry: string): Promise<PatchInfo> {
  const content = await fs.readFile(join(patchesDir, entry), 'utf8')
  const lines = content.split(/\r?\n/)
  const subject = parseSubject(lines)
  const { added, removed } = countDiff(lines)
  return { entry, subject, added, removed }
}

function escapeMarkdownCell(value: string) {
  return value.replaceAll('|', '\\|')
}

function formatSize(added: number, removed: number) {
  return `\${\\color{green}+${added}}$\u00a0\${\\color{red}-${removed}}$`
}

function formatTable(patches: PatchInfo[]) {
  const header = '| # | Patch | Size | Description |\n|---|---|---|---|'
  const rows = patches.map((patch, index) => {
    const name = patch.entry.replace(/\.patch$/, '')
    return `| ${index + 1} | \`${name}\` | ${formatSize(patch.added, patch.removed)} | ${escapeMarkdownCell(patch.subject)} |`
  })
  const totalAdded = patches.reduce((acc, p) => acc + p.added, 0)
  const totalRemoved = patches.reduce((acc, p) => acc + p.removed, 0)
  rows.push(`| | **Total** | ${formatSize(totalAdded, totalRemoved)} | |`)
  return `${header}\n${rows.join('\n')}\n`
}

async function walk(dir: string): Promise<string[]> {
  const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => [])
  const files: string[] = []
  for (const entry of entries) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) files.push(...await walk(full))
    else if (entry.isFile()) files.push(full)
  }
  return files
}

async function countKotlinSloc() {
  const files = (await walk(kotlinDir)).filter(f => f.endsWith('.kt'))
  let sloc = 0
  for (const file of files) {
    const content = await fs.readFile(file, 'utf8')
    for (const line of content.split(/\r?\n/)) {
      if (line.trim() !== '') sloc++
    }
  }
  return { files: files.length, sloc }
}

async function countAssets() {
  return (await walk(resDir)).length
}

const raw = await fs.readFile(seriesFile, 'utf8')
const entries = raw.split(/\r?\n/).map(line => line.trim()).filter(Boolean)

const patches = await Promise.all(entries.map(readPatch))
const kotlin = await countKotlinSloc()
const assets = await countAssets()

const extras = [
  '## Custom code',
  '',
  `- Kotlin SLOC: ${kotlin.sloc} (across ${kotlin.files} files)`,
  `- Extra static assets: ${assets}`,
  '',
].join('\n')

const out = `# Patch Summary\n\n${formatTable(patches)}\n${extras}`
await fs.writeFile(join(rootDir, 'SUMMARY.md'), out)

success(`Wrote SUMMARY.md (${patches.length} patches, ${kotlin.sloc} kotlin sloc, ${assets} assets)`)
