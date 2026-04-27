import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { html, MemoryStorage, TelegramClient } from '@mtcute/node'
import { joinTextWithEntities } from '@mtcute/node/utils.js'

interface BuildInfo {
  verName: string
  verCode: number
  appVerCode: number
  buildNum: number
  apkFile: string
  commitSha: string
  commits: { sha: string, message: string }[]
  repo: string
  kind: 'canary' | 'release'
  tag?: string
}

const artifactDir = resolve(process.argv[2] ?? 'out')
const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
const apkPath = join(artifactDir, info.apkFile)
await fs.access(apkPath)

const apiId = Number(process.env.TELEGRAM_API_ID)
const apiHash = process.env.TELEGRAM_API_HASH
const botToken = process.env.TELEGRAM_BOT_TOKEN
const channel = process.env.TELEGRAM_CHANNEL ?? 'InugramCI'

if (!apiId || !apiHash || !botToken) {
  throw new Error('TELEGRAM_API_ID, TELEGRAM_API_HASH and TELEGRAM_BOT_TOKEN must be set')
}

const cachedSession = process.env.MTPROTO_SESSION || undefined
const ghVarsToken = process.env.GH_VARS_TOKEN
const ghRepo = process.env.GITHUB_REPOSITORY

const tg = new TelegramClient({
  apiId,
  apiHash,
  storage: new MemoryStorage(),
})

if (cachedSession) {
  await tg.importSession(cachedSession, true)
  await tg.connect()
} else {
  await tg.start({ botToken })
}

async function persistSession(session: string) {
  if (!ghVarsToken || !ghRepo) {
    console.warn('GH_VARS_TOKEN or GITHUB_REPOSITORY missing, skipping session persist')
    return
  }
  await new Promise<void>((res, rej) => {
    const p = spawn('gh', ['secret', 'set', 'MTPROTO_SESSION', '-R', ghRepo], {
      env: { ...process.env, GH_TOKEN: ghVarsToken },
      stdio: ['pipe', 'inherit', 'inherit'],
    })
    p.stdin.end(session)
    p.on('error', rej)
    p.on('exit', code => code === 0 ? res() : rej(new Error(`gh exited ${code}`)))
  })
}

try {
  const hashtag = info.kind === 'release' ? '#release' : '#canary'
  const headerUrl = info.kind === 'release' && info.tag
    ? `https://github.com/${info.repo}/releases/tag/${info.tag}`
    : `https://github.com/${info.repo}/commit/${info.commitSha}`
  const headerLabel = info.kind === 'release' && info.tag
    ? info.tag
    : info.commitSha.slice(0, 7)
  const commitLines = joinTextWithEntities(
    info.commits.map(c => html`<a href="https://github.com/${info.repo}/commit/${c.sha}">${c.sha.slice(0, 7)}</a>: ${c.message}`),
    '\n',
  )

  const caption = html`
    ${hashtag}
    <br/>
    <b>v${info.verName}</b> (build ${info.buildNum}, based on ${info.appVerCode})
    <br/>
    <a href="${headerUrl}">${headerLabel}</a>
    <br/><br/>
    <blockquote expandable>
      ${commitLines}
    </blockquote>
  `

  await tg.sendMedia(channel, {
    type: 'document',
    file: `file:${apkPath}`,
    fileName: info.apkFile,
    caption,
  })
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported)
  }
  await tg.destroy()
}
