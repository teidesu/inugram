import { createHash } from 'node:crypto'
import { watch } from 'node:fs'
import fs from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { $, chalk } from 'zx'
import { debugAppId, releaseAppId } from './config.js'
import { step, success, warn } from './lib.js'

const dropDirName = 'plugin-dev'
const devAction = 'desu.inugram.plugins.DEV'
// what PluginManager.logConsole and its neighbours tag with; logcat filterspecs have no wildcards
const logTagPrefix = 'InuPlugin'
// long enough to swallow an editor's multi-step save, short enough to feel immediate
const WATCH_DEBOUNCE_MS = 300

$.verbose = false

const args = process.argv.slice(2)
const watchMode = args.includes('--watch') || args.includes('-w')
const logsMode = args.includes('--logs')
const listMode = args.includes('--list')
const serialIdx = args.indexOf('-s')
const serial = serialIdx === -1 ? null : args[serialIdx + 1]
const appIdx = args.indexOf('--app')
const appId = appIdx !== -1 ? args[appIdx + 1] : args.includes('--release') ? releaseAppId : debugAppId
const removeIdx = args.indexOf('--remove')
const removeName = removeIdx === -1 ? null : args[removeIdx + 1]
// the value of every flag that takes one, so it is not mistaken for a file to push
const consumed = new Set([serialIdx, appIdx, removeIdx].filter(idx => idx !== -1).map(idx => idx + 1))
const files = args
  .filter((arg, idx) => !arg.startsWith('-') && !consumed.has(idx))
  .map(file => resolve(file))

const remoteDir = `/sdcard/Android/data/${appId}/files/${dropDirName}`
const adb = serial ? ['-s', serial] : []

interface DevResult {
  ok: boolean
  error?: string
  action?: string
  file?: string
  plugin?: { name: string, enabled: boolean, dev: boolean, failure?: string }
  results?: DevResult[]
  [key: string]: unknown
}

async function pid() {
  const out = await $({ nothrow: true })`adb ${adb} shell pidof ${appId}`
  const value = out.stdout.trim().split(/\s+/)[0]
  return out.exitCode === 0 && value ? value : null
}

/**
 * `am broadcast` prints the ordered result the receiver set, which is the only thing that says
 * whether an install worked. No data at all means nothing received it: dev mode is off.
 */
async function send(extras: Record<string, string>): Promise<DevResult> {
  const flags = Object.entries(extras).flatMap(([key, value]) => ['--es', key, value])
  const out = await $({ nothrow: true })`adb ${adb} shell am broadcast -a ${devAction} -p ${appId} ${flags}`
  if (out.exitCode !== 0) throw new Error(out.stderr.trim() || out.stdout.trim())
  // "Broadcast completed: result=0, data="{...}"" — am does not escape the quotes inside data,
  // so the payload runs to the last one rather than to the first
  const at = out.stdout.indexOf('data="')
  const rest = at === -1 ? '' : out.stdout.slice(at + 'data="'.length)
  const end = rest.lastIndexOf('"')
  if (end === -1) {
    throw new Error(`no reply from ${appId} — turn on developer mode in Settings > Plugins`)
  }
  return JSON.parse(rest.slice(0, end)) as DevResult
}

function report(result: DevResult) {
  for (const one of result.results ?? [result]) {
    if (!one.ok) {
      console.log(`${chalk.red('fail')} ${one.error ?? 'unknown error'}`)
      continue
    }
    const plugin = one.plugin
    const name = plugin?.name ?? one.file ?? ''
    const suffix = plugin?.failure ? chalk.red(` (${plugin.failure})`) : ''
    success(`${one.action ?? 'ok'} ${chalk.bold(name)}${suffix}`)
  }
}

/** null when the file cannot be read or is empty, which is what a save caught mid-write looks like */
async function digest(file: string) {
  const body = await fs.readFile(file).catch(() => null)
  if (!body || body.length === 0) return null
  return createHash('sha256').update(body).digest('hex')
}

// what the device is running, per file. A dev install has no "already installed" check of its own,
// so an editor that saves an unchanged buffer would otherwise reload the plugin for nothing
const pushed = new Map<string, string>()

async function push(targets: string[]) {
  const changed: string[] = []
  for (const target of targets) {
    const hash = await digest(target)
    // not recorded: the next event re-checks, rather than this half-written state sticking
    if (hash === null || pushed.get(target) === hash) continue
    pushed.set(target, hash)
    changed.push(target)
  }
  if (changed.length === 0) {
    console.log(chalk.gray(`unchanged, not pushed: ${targets.map(f => basename(f)).join(', ')}`))
    return
  }

  await $`adb ${adb} shell mkdir -p ${remoteDir}`
  for (const target of changed) {
    await $`adb ${adb} push ${target} ${remoteDir}/${basename(target)}`
  }
  for (const target of changed) {
    report(await send({ cmd: 'install', file: basename(target) }))
  }
}

/**
 * logcat's tag filters are exact, so the whole process is read and filtered here. The pid is
 * re-resolved when the tail ends, which is how an app restart is picked up.
 */
async function tailLogs() {
  const levelColor: Record<string, (s: string) => string> = {
    E: chalk.red,
    W: chalk.yellow,
    I: chalk.white,
    D: chalk.gray,
    V: chalk.gray,
  }
  for (;;) {
    const current = await pid()
    if (!current) {
      await new Promise(r => setTimeout(r, 1000))
      continue
    }
    const proc = $`adb ${adb} logcat -v brief --pid ${current}`
    for await (const chunk of proc.stdout) {
      for (const line of String(chunk).split('\n')) {
        // brief format: "D/InuPlugin/name(  pid): message"
        const match = line.match(/^([VDIWEF])\/([^(]+)\(\s*\d+\):\s?(.*)$/)
        if (!match) continue
        const [, level, tag, message] = match
        if (!tag.trim().startsWith(logTagPrefix)) continue
        const paint = levelColor[level] ?? chalk.gray
        console.log(`${paint(tag.trim())} ${message}`)
      }
    }
    await proc.catch(() => {})
  }
}

const running = await pid()
if (!running) {
  throw new Error(`${appId} is not running — start it first (the dev receiver lives in the app)`)
}

if (listMode) {
  const result = await send({ cmd: 'list' })
  for (const plugin of (result.plugins as DevResult['plugin'][] | undefined) ?? []) {
    if (!plugin) continue
    const flags = [plugin.enabled ? 'enabled' : 'disabled', plugin.dev ? 'dev' : null].filter(Boolean)
    console.log(`${chalk.bold(plugin.name)} ${chalk.gray(flags.join(', '))}${plugin.failure ? chalk.red(` ${plugin.failure}`) : ''}`)
  }
} else if (removeName !== null) {
  report(await send({ cmd: 'remove', file: basename(removeName) }))
} else {
  if (files.length === 0) {
    throw new Error('usage: push-plugin <file.js...> [--watch] [--logs] [--list] [--remove <name>] [--release|--app <id>] [-s <serial>]')
  }
  for (const file of files) await fs.access(file)

  const ping = await send({ cmd: 'ping' })
  if (!ping.engineEnabled) warn('plugins are switched off in settings — pushes install but will not run')
  if (ping.safeMode) warn('safe mode: nothing runs until the app is restarted')

  await push(files)

  if (logsMode) tailLogs().catch(err => console.error(chalk.red(String(err))))

  if (watchMode) {
    step(`watching ${files.map(f => basename(f)).join(', ')} (ctrl-c to stop)`)
    const dirty = new Set<string>()
    let pending: NodeJS.Timeout | null = null
    let flushing: Promise<unknown> = Promise.resolve()

    // the directory, not the file: an editor that saves atomically writes a temp file and renames
    // it over the target, and a watch on the old inode goes deaf the first time that happens
    const targets = new Set(files)
    for (const dir of new Set(files.map(f => dirname(f)))) {
      watch(dir, (_event, name) => {
        if (!name) return
        const file = resolve(dir, String(name))
        if (!targets.has(file)) return
        dirty.add(file)
        // one save is several events (truncate, write, rename, attribute change); collapse the
        // burst into one push, which the hash check then drops entirely if nothing really changed
        if (pending) clearTimeout(pending)
        pending = setTimeout(() => {
          pending = null
          const batch = [...dirty]
          dirty.clear()
          flushing = flushing
            .then(() => push(batch))
            .catch(err => console.error(chalk.red(String(err))))
        }, WATCH_DEBOUNCE_MS)
      })
    }
    await new Promise(() => {})
  } else if (logsMode) {
    await new Promise(() => {})
  }
}
