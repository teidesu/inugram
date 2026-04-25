import fs from 'node:fs/promises'

const mode = process.argv[2]
if (mode !== 'canary' && mode !== 'release') {
  throw new Error(`usage: version.ts canary|release (got ${mode})`)
}

const props = await fs.readFile('worktree/gradle.properties', 'utf8')
const appVerName = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
if (!appVerName) throw new Error('failed to read APP_VERSION_NAME')

const buildNum = Number(process.env.INU_BUILD ?? '1')
if (!Number.isInteger(buildNum) || buildNum < 1) {
  throw new Error(`invalid INU_BUILD: ${process.env.INU_BUILD}`)
}

const sha = process.env.GITHUB_SHA ?? ''
const shortSha = sha.slice(0, 7)
const verName = `${appVerName}-${shortSha}`
const verCode = buildNum
const apkName = `inugram-${verName}-${verCode}.apk`
const tag = mode === 'release' ? `v${appVerName}-${buildNum}` : ''

const out = {
  'app-ver-name': appVerName,
  'build-num': String(buildNum),
  'ver-name': verName,
  'ver-code': String(verCode),
  'apk-name': apkName,
  tag,
}

const githubOutput = process.env.GITHUB_OUTPUT
if (githubOutput) {
  const lines = `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`
  await fs.appendFile(githubOutput, lines)
}

console.log(JSON.stringify(out, null, 2))
