import type { InputRichMessageMedia } from '@mtcute/node'
import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { InputMedia, MemoryStorage, TelegramClient } from '@mtcute/node'

interface BuildInfo {
  verName: string
  verCode: number
  appVerCode: number
  buildNum: number
  apkFiles: { full: string, pluginless: string }
  baseTag: string | null
  commitSha: string
  repo: string
}

const artifactDir = resolve(process.argv[2] ?? 'out')
const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
const variants = ['full', 'pluginless'] as const
for (const variant of variants) {
  await fs.access(join(artifactDir, info.apkFiles[variant]))
}

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
  const notes = await fs.readFile(`changelogs/${info.buildNum}.md`, 'utf8').catch(() => '')
  const fullChangelog = info.baseTag
    ? `[Full changelog: ${info.baseTag}...${info.commitSha.slice(0, 7)}](https://github.com/${info.repo}/compare/${info.baseTag}...${info.commitSha})`
    : `[Full changelog](https://github.com/${info.repo}/commits/${info.commitSha})`

  const attachments: Record<string, InputRichMessageMedia> = {}
  for (const variant of variants) {
    // @ts-expect-error mtcute 0.32.3 omits document inputs from its rich-media type
    attachments[variant] = InputMedia.document(`file:${join(artifactDir, info.apkFiles[variant])}`, {
      fileName: info.apkFiles[variant],
      fileMime: 'application/vnd.android.package-archive',
    })
  }

  await tg.sendRichMessage(channel, {
    content: {
      type: 'markdown',
      // tg://document links are undocumented, but work in image syntax
      content: [
        `## Inugram v${info.buildNum}`,
        `<footer>#release v${info.verName} (build ${info.buildNum}, based on ${info.appVerCode})</footer>`,
        ...variants.map(variant => `![${variant}](tg://document?id=${variant})`),
        '<details><summary>Changelog</summary>',
        notes.trim(),
        fullChangelog,
        '</details>',
      ].filter(Boolean).join('\n\n'),
      attachments,
    },
  })
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported).catch(e => console.warn('failed to persist session:', e))
  }
  await tg.destroy()
}
