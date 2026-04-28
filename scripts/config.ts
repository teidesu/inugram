import type { IconifyJSON } from '@iconify-json/tabler'
import type { SvgToDrawableOptions } from './svg-to-vector.js'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { icons as tablerIcons } from '@iconify-json/tabler'

export const upstreamUrl = 'https://github.com/DrKLO/Telegram'
export const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const worktreeDir = join(rootDir, 'worktree')
export const patchesDir = join(rootDir, 'patches')
export const seriesFile = join(rootDir, 'series')
export const upstreamCommitFile = join(rootDir, 'upstream-commit')

export interface ForkSyncFile {
  source: string
  target: string
  directory?: boolean
  replace?: boolean
}

export const forkSyncFiles: ForkSyncFile[] = [
  // code
  {
    source: 'src/kotlin',
    target: 'TMessagesProj/src/main/kotlin/desu/inugram',
    directory: true,
  },
  {
    source: 'src/kotlin-app',
    target: 'TMessagesProj_App/src/main/kotlin/desu/inugram',
    directory: true,
  },
  {
    source: 'src/java/google_material',
    target: 'TMessagesProj/src/main/java/google_material',
    directory: true,
  },
  // assets
  {
    source: 'src/res/values/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values',
  },
  {
    source: 'src/res/drawable/icplaceholder.jpg',
    target: 'TMessagesProj/src/main/res/drawable',
    replace: true,
  },
  {
    source: 'src/res/drawable/sticker.webp',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/drawable-xxhdpi/*',
    target: 'TMessagesProj/src/main/res/drawable-xxhdpi',
  },
  {
    source: 'src/res/drawable/solar/*',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/assets/*',
    target: 'TMessagesProj/src/main/assets',
  },
]

export const ICON_SELECTION: { pack: IconifyJSON, icons: string[], options?: SvgToDrawableOptions }[] = [
  {
    pack: tablerIcons,
    options: { overrideStrokeWidth: 1.67, paddingInset: 1 }, // to match Telegram
    icons: [
      'copy',
      'clipboard',
      'scissors',
      'bold',
      'underline',
      'italic',
      'strikethrough',
      'background',
      'quote',
      'code',
      'link',
      'select-all',
      'clear-formatting',
      'filter',
    ],
  },
]
