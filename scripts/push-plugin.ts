import { createHash } from 'node:crypto'
import { watch } from 'node:fs'
import fs from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { chalk } from 'zx'
import { reportInstall, reportPluginAction } from '../sdk/cli/src/commands/dev.js'
import { Device } from '../sdk/cli/src/utils/device.js'
import { debugAppId, releaseAppId } from './config.js'
import { step, warn } from './lib.js'

// pushes a plain `.inu.js` file to a device, for the plugins kept in this repo. everything that
// talks to the app lives in the cli's `Device`, so there is one implementation of the dev protocol.

// long enough to swallow an editor's multi-step save, short enough to feel immediate
const WATCH_DEBOUNCE_MS = 300

const args = process.argv.slice(2)
const watchMode = args.includes('--watch') || args.includes('-w')
const logsMode = args.includes('--logs')
const listMode = args.includes('--list')
const serialIdx = args.indexOf('-s')
const serial = serialIdx === -1 ? undefined : args[serialIdx + 1]
const appIdx = args.indexOf('--app')
const appId = appIdx !== -1 ? args[appIdx + 1] : args.includes('--release') ? releaseAppId : debugAppId
const removeIdx = args.indexOf('--remove')
const removeName = removeIdx === -1 ? null : args[removeIdx + 1]
// the value of every flag that takes one, so it is not mistaken for a file to push
const consumed = new Set([serialIdx, appIdx, removeIdx].filter(idx => idx !== -1).map(idx => idx + 1))
const files = args
  .filter((arg, idx) => !arg.startsWith('-') && !consumed.has(idx))
  .map(file => resolve(file))

const device = new Device({ serial, appId })

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
  for (const install of await device.install(changed)) reportInstall(install)
}

const levelColor: Record<string, (s: string) => string> = {
  E: chalk.red,
  W: chalk.yellow,
  I: chalk.white,
  D: chalk.gray,
  V: chalk.gray,
}

await device.requireRunning()

if (listMode) {
  for (const plugin of await device.list()) {
    const flags = [plugin.enabled ? 'enabled' : 'disabled', plugin.dev ? 'dev' : null].filter(Boolean)
    console.log(`${chalk.bold(plugin.name)} ${chalk.gray(flags.join(', '))}${plugin.failure ? chalk.red(` ${plugin.failure}`) : ''}`)
  }
} else if (removeName !== null) {
  const removed = await device.remove(removeName)
  reportPluginAction(removed.action, removed.plugin)
} else {
  if (files.length === 0) {
    throw new Error('usage: push-plugin <file.js...> [--watch] [--logs] [--list] [--remove <name>] [--release|--app <id>] [-s <serial>]')
  }
  for (const file of files) await fs.access(file)

  const ping = await device.ping()
  if (!ping.engineEnabled) warn('plugins are switched off in settings — pushes install but will not run')
  if (ping.safeMode) warn('safe mode: nothing runs until the app is restarted')

  await push(files)

  const aborter = new AbortController()
  if (logsMode) {
    device
      .tailLogs((level, tag, message) => {
        console.log(`${(levelColor[level] ?? chalk.gray)(tag)} ${message}`)
      }, aborter.signal)
      .catch(err => console.error(chalk.red(String(err))))
  }

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
