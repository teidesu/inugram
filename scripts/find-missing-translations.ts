import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { $ } from 'zx'
import { rootDir } from './config.js'

$.verbose = false

const iso = process.argv[2]
if (!iso) {
  throw new Error('ISO code required, e.g. `tsx scripts/find-missing-translations.ts ru`')
}

const baseFile = join(rootDir, 'src/res/values/strings_inu.xml')
const langFile = join(rootDir, `src/res/values-${iso}/strings_inu.xml`)

const stringRe = /<string\s+name="([^"]+)">([\s\S]*?)<\/string>/g
const stringNameRe = /<string\s+name="([^"]+)">/

async function parseStrings(file: string) {
  const text = await fs.readFile(file, 'utf8')
  const map = new Map<string, string>()
  for (const m of text.matchAll(stringRe)) {
    map.set(m[1], m[2])
  }
  return map
}

async function blameKeyTimes(file: string) {
  const rel = relative(rootDir, file)
  // blame the working tree (not HEAD) so uncommitted edits count as touched-now
  const out = (await $({ cwd: rootDir })`git blame --line-porcelain -w -M -C -- ${rel}`).stdout
  const map = new Map<string, number>()
  let time = 0
  for (const line of out.split('\n')) {
    if (line.startsWith('author-time ')) {
      time = Number.parseInt(line.slice(12), 10)
    } else if (line.startsWith('\t')) {
      const m = line.slice(1).match(stringNameRe)
      if (m) map.set(m[1], time)
    }
  }
  return map
}

const [base, lang, baseTimes, langTimes] = await Promise.all([
  parseStrings(baseFile),
  parseStrings(langFile),
  blameKeyTimes(baseFile),
  blameKeyTimes(langFile),
])

const PLURAL_SUFFIX_RE = /_(zero|one|two|few|many|other)$/
function pluralStem(key: string) {
  const m = key.match(PLURAL_SUFFIX_RE)
  return m ? key.slice(0, -m[0].length) : null
}

function pluralStems(keys: Iterable<string>) {
  const out = new Set<string>()
  for (const k of keys) {
    const stem = pluralStem(k)
    if (stem) out.add(stem)
  }
  return out
}

const baseStems = pluralStems(base.keys())
const langStems = pluralStems(lang.keys())

const missing: string[] = []
const extra: string[] = []
for (const key of base.keys()) {
  if (lang.has(key)) continue
  const stem = pluralStem(key)
  if (stem && langStems.has(stem)) continue
  missing.push(key)
}
for (const key of lang.keys()) {
  if (base.has(key)) continue
  const stem = pluralStem(key)
  if (stem && baseStems.has(stem)) continue
  extra.push(key)
}

console.log(`# Missing translations (${missing.length}) — present in values/ but not in values-${iso}/`)
console.log()
if (missing.length === 0) {
  console.log('(none)')
} else {
  for (const key of missing) {
    console.log(`<string name="${key}">${base.get(key)}</string>`)
  }
}

console.log()
console.log(`# Extra translations (${extra.length}) — present in values-${iso}/ but not in values/`)
console.log()
if (extra.length === 0) {
  console.log('(none)')
} else {
  for (const key of extra) {
    console.log(`<string name="${key}">${lang.get(key)}</string>`)
  }
}

const stale: string[] = []
for (const key of base.keys()) {
  if (!lang.has(key)) continue
  const bt = baseTimes.get(key)
  const lt = langTimes.get(key)
  if (bt !== undefined && lt !== undefined && bt > lt) stale.push(key)
}

console.log()
console.log(`# Stale translations (${stale.length}) — base line touched after translation, may need re-check`)
console.log()
if (stale.length === 0) {
  console.log('(none)')
} else {
  for (const key of stale) {
    console.log(`<!-- ${key} -->`)
    console.log(`<string name="${key}">${base.get(key)}</string>`)
    console.log(`<string name="${key}">${lang.get(key)}</string>`)
    console.log()
  }
}
