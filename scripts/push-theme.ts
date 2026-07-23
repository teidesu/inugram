import { watch } from 'node:fs'
import fs from 'node:fs/promises'
import { join } from 'node:path'
import { $, chalk } from 'zx'
import {
  assetsDir,
  debugAppId,
} from './config.js'
import { step, success, warn } from './lib.js'

const themeOverrideDirName = 'theme-override'
const themeReloadAction = 'desu.inugram.RELOAD_THEME'

$.verbose = false

const THEMES: Record<string, string> = {
  light: 'monet_light.attheme',
  dark: 'monet_dark.attheme',
  amoled: 'monet_amoled.attheme',
}

const args = process.argv.slice(2)
const watchMode = args.includes('--watch') || args.includes('-w')
const clearMode = args.includes('--clear')
const serialIdx = args.indexOf('-s')
const serial = serialIdx === -1 ? null : args[serialIdx + 1]
const names = args.filter((arg, idx) => !arg.startsWith('-') && idx !== serialIdx + 1)

const unknown = names.filter(name => !(name in THEMES))
if (unknown.length > 0) {
  throw new Error(`Unknown theme(s): ${unknown.join(', ')} (expected ${Object.keys(THEMES).join('/')})`)
}

const assets = (names.length > 0 ? names : Object.keys(THEMES)).map(name => THEMES[name])
const remoteDir = `/sdcard/Android/data/${debugAppId}/files/${themeOverrideDirName}`
const adb = serial ? ['-s', serial] : []

async function isRunning() {
  const out = await $({ nothrow: true })`adb ${adb} shell pidof ${debugAppId}`
  return out.exitCode === 0 && out.stdout.trim().length > 0
}

async function broadcast() {
  const running = await isRunning()
  if (!running) {
    warn(`${debugAppId} is not running — override applies on next launch`)
    return
  }
  await $`adb ${adb} shell am broadcast -a ${themeReloadAction} -p ${debugAppId}`
  success('theme reloaded')
}

async function push(targets: string[]) {
  await $`adb ${adb} shell mkdir -p ${remoteDir}`
  for (const asset of targets) {
    await $`adb ${adb} push ${join(assetsDir, asset)} ${remoteDir}/${asset}`
  }
  step(`pushed ${targets.join(', ')}`)
  await broadcast()
}

if (clearMode) {
  await $`adb ${adb} shell rm -rf ${remoteDir}`
  step('removed override, back to bundled assets')
  await broadcast()
} else {
  for (const asset of assets) {
    await fs.access(join(assetsDir, asset))
  }
  await push(assets)

  if (watchMode) {
    step(`watching ${assets.join(', ')} (ctrl-c to stop)`)
    const dirty = new Set<string>()
    let pending: NodeJS.Timeout | null = null
    let flushing: Promise<unknown> = Promise.resolve()

    for (const asset of assets) {
      watch(join(assetsDir, asset), () => {
        dirty.add(asset)
        // editors write in several steps (truncate, write, rename); coalesce them
        if (pending) clearTimeout(pending)
        pending = setTimeout(() => {
          pending = null
          const targets = [...dirty]
          dirty.clear()
          flushing = flushing
            .then(() => push(targets))
            .catch(err => console.error(chalk.red(String(err))))
        }, 150)
      })
    }
    await new Promise(() => {})
  }
}
