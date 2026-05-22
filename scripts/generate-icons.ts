import type { SvgShape } from './svg-to-vector.js'
import fs from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { rootDir } from './config.js'
import { ensureDir, step, success } from './lib.js'
import {
  fmtNum,
  parseSvgBody,
  resolveFillColor,
  resolveStrokeColor,

} from './svg-to-vector.js'

// run manually after changing src/res/launcher SVGs; output is committed

const ADAPTIVE_SIZE = 108
const FG_SAFE = 72
// settings-list icon: 24dp render in a 72dp viewport, tinted white by
// SettingCell — mirrors stock settings_account/settings_chat/settings_privacy
const SETTINGS_DP = 24
const SETTINGS_VIEWPORT = 72
const SETTINGS_SAFE = 80
const BG_GRADIENT_FROM = '#FFD4A3FF'
const BG_GRADIENT_TO = '#FFD59EFF'
// debug badge: a small white square (only its top-left corner rounded) tucked
// into the bottom-right corner, holding a β. the white fill is framed with a
// background-coloured outline so it doesn't clash with the icon underneath.
const DEBUG_BADGE_COLOR = '#FFFFFFFF'
// the β fill and the badge outline both use the icon background colour
const DEBUG_MARK_COLOR = BG_GRADIENT_FROM
// β glyph from Material Design Icons (Apache-2.0), viewBox 0 0 24 24
const BETA_PATH = 'M9.23 17.59v5.53H6.88V6.72c0-1.45.43-2.59 1.28-3.44C9 2.43 10.17 2 11.61 2c1.39 0 2.46.34 3.26 1c.79.68 1.18 1.62 1.18 2.81c0 .82-.26 1.59-.78 2.3s-1.19 1.2-2.02 1.47v.04c1.25.2 2.22.65 2.88 1.38c.66.71.99 1.62.99 2.74c0 1.32-.46 2.4-1.37 3.23c-.92.83-2.12 1.24-3.62 1.24c-1.06 0-2.03-.21-2.9-.62m1.49-6.84V8.83c.87-.11 1.58-.43 2.15-.97c.56-.55.84-1.16.84-1.86c0-1.38-.71-2.08-2.11-2.08c-.76 0-1.35.24-1.76.73s-.61 1.17-.61 2.06v8.79c.91.53 1.8.79 2.66.79c.84 0 1.5-.22 1.97-.65c.47-.44.7-1.06.7-1.85c0-1.79-1.28-2.79-3.84-3.04'
const GLYPH_VIEWBOX = 24
// the β path isn't centered in its 24x24 viewBox — its ink box leans toward
// the bottom-right, so the badge centers on the box, not the viewBox
const BETA_BBOX = { x: 6.88, y: 2, width: 10.24, height: 21.12 }

// debug badge geometry, in the 108dp adaptive-icon viewport
const BADGE_SIZE = 26
const BADGE_CORNER_RADIUS = 12
// gap between the badge edge and the β glyph box
const BADGE_PADDING = 5
// bg-coloured frame; the stroke is centered on the edge, so ~half shows outside
const BADGE_OUTLINE_WIDTH = 4
// pin the badge's outer corner onto the launcher's circular icon mask (the
// inscribed circle of the viewport) so it sits flush in the bottom-right
// corner; the frame's outer tip clips against the mask, which is harmless
const MASK_RADIUS = ADAPTIVE_SIZE / 2
const BADGE_FAR = ADAPTIVE_SIZE / 2 + MASK_RADIUS / Math.SQRT2
const BADGE_NEAR = BADGE_FAR - BADGE_SIZE

// committed under src/res, synced into the worktree by forkSyncFiles
const GEN_DRAWABLE = 'src/res/launcher/generated/drawable'
const GEN_DEBUG_MIPMAP = 'src/res/launcher/generated/mipmap-debug'

function shapeToPathXml(shape: SvgShape, monochrome: boolean): string | null {
  const fill = resolveFillColor(shape.fill)
  const stroke = resolveStrokeColor(shape.stroke)
  if (!fill && !stroke) return null
  const attrs: string[] = [`android:pathData="${shape.d}"`]
  if (fill) attrs.push(`android:fillColor="${monochrome ? '#FFFFFFFF' : fill}"`)
  if (stroke) {
    attrs.push(`android:strokeColor="${monochrome ? '#FFFFFFFF' : stroke}"`)
    attrs.push(`android:strokeWidth="${fmtNum(Number(shape.strokeWidth ?? 1))}"`)
    if (shape.strokeLineCap) attrs.push(`android:strokeLineCap="${shape.strokeLineCap}"`)
    if (shape.strokeLineJoin) attrs.push(`android:strokeLineJoin="${shape.strokeLineJoin}"`)
  }
  return `        <path\n            ${attrs.join('\n            ')} />`
}

function buildDebugBadge(): string {
  const r = BADGE_CORNER_RADIUS
  // white square with only the top-left corner rounded, traced clockwise
  const badge = `M${fmtNum(BADGE_NEAR + r)},${fmtNum(BADGE_NEAR)}`
    + ` H${fmtNum(BADGE_FAR)} V${fmtNum(BADGE_FAR)} H${fmtNum(BADGE_NEAR)}`
    + ` V${fmtNum(BADGE_NEAR + r)} A${fmtNum(r)},${fmtNum(r)} 0 0 1`
    + ` ${fmtNum(BADGE_NEAR + r)},${fmtNum(BADGE_NEAR)} Z`
  // β: scale the 24x24 glyph viewBox to the padded badge interior, then center
  // the glyph's ink box (not the viewBox) on the badge
  const scale = (BADGE_SIZE - 2 * BADGE_PADDING) / GLYPH_VIEWBOX
  const badgeCenter = (BADGE_NEAR + BADGE_FAR) / 2
  const offsetX = badgeCenter - (BETA_BBOX.x + BETA_BBOX.width / 2) * scale
  const offsetY = badgeCenter - (BETA_BBOX.y + BETA_BBOX.height / 2) * scale
  return `\n    <path
        android:pathData="${badge}"
        android:fillColor="${DEBUG_BADGE_COLOR}"
        android:strokeColor="${DEBUG_MARK_COLOR}"
        android:strokeWidth="${fmtNum(BADGE_OUTLINE_WIDTH)}" />
    <group
        android:translateX="${fmtNum(offsetX)}"
        android:translateY="${fmtNum(offsetY)}"
        android:scaleX="${fmtNum(scale)}"
        android:scaleY="${fmtNum(scale)}">
        <path
            android:pathData="${BETA_PATH}"
            android:fillColor="${DEBUG_MARK_COLOR}" />
    </group>`
}

interface ScaledVectorOpts {
  widthDp: number
  viewport: number
  safe: number
  overlay?: string
}

// scale an SVG body to `safe` units, centered inside a `viewport`-sized canvas
function buildScaledVector(shapes: SvgShape[], srcW: number, srcH: number, monochrome: boolean, opts: ScaledVectorOpts): string {
  const inset = (opts.viewport - opts.safe) / 2
  const scale = opts.safe / Math.max(srcW, srcH)
  const offsetX = inset + (opts.safe - srcW * scale) / 2
  const offsetY = inset + (opts.safe - srcH * scale) / 2
  const paths = shapes
    .map(s => shapeToPathXml(s, monochrome))
    .filter((s): s is string => s !== null)
  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${fmtNum(opts.widthDp)}dp"
    android:height="${fmtNum(opts.widthDp)}dp"
    android:viewportWidth="${opts.viewport}"
    android:viewportHeight="${opts.viewport}">
    <group
        android:translateX="${fmtNum(offsetX)}"
        android:translateY="${fmtNum(offsetY)}"
        android:scaleX="${fmtNum(scale)}"
        android:scaleY="${fmtNum(scale)}">
${paths.join('\n')}
    </group>${opts.overlay ?? ''}
</vector>
`
}

function buildForegroundVector(shapes: SvgShape[], srcW: number, srcH: number, monochrome: boolean, debug = false): string {
  return buildScaledVector(shapes, srcW, srcH, monochrome, {
    widthDp: ADAPTIVE_SIZE,
    viewport: ADAPTIVE_SIZE,
    safe: FG_SAFE,
    overlay: debug ? buildDebugBadge() : undefined,
  })
}

function buildSettingsVector(shapes: SvgShape[], srcW: number, srcH: number): string {
  return buildScaledVector(shapes, srcW, srcH, true, {
    widthDp: SETTINGS_DP,
    viewport: SETTINGS_VIEWPORT,
    safe: SETTINGS_SAFE,
  })
}

function buildAdaptiveIcon(foreground: string): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/icon_background_inu" />
    <foreground android:drawable="@drawable/${foreground}" />
    <monochrome android:drawable="@drawable/icon_plane_inu" />
</adaptive-icon>
`
}

function buildBackgroundVector(): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${ADAPTIVE_SIZE}dp"
    android:height="${ADAPTIVE_SIZE}dp"
    android:viewportWidth="${ADAPTIVE_SIZE}"
    android:viewportHeight="${ADAPTIVE_SIZE}">
    <path android:pathData="M0,0h${ADAPTIVE_SIZE}v${ADAPTIVE_SIZE}h-${ADAPTIVE_SIZE}z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="0"
                android:endY="${ADAPTIVE_SIZE}">
                <item android:offset="0" android:color="${BG_GRADIENT_FROM}" />
                <item android:offset="1" android:color="${BG_GRADIENT_TO}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
`
}

async function writeGenerated(relPath: string, content: string): Promise<boolean> {
  const absPath = join(rootDir, relPath)
  await ensureDir(dirname(absPath))
  const current = await fs.readFile(absPath, 'utf8').catch(() => null)
  if (current === content) return false
  step(`Generating ${relPath}`)
  await fs.writeFile(absPath, content)
  return true
}

async function loadSvg(relPath: string): Promise<{ shapes: SvgShape[], srcW: number, srcH: number }> {
  const svgPath = join(rootDir, relPath)
  const svg = await fs.readFile(svgPath, 'utf8')
  const viewBox = svg.match(/viewBox\s*=\s*"\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*"/)
  if (!viewBox) throw new Error(`${relPath} missing viewBox starting at 0 0`)
  return { shapes: parseSvgBody(svg), srcW: Number(viewBox[1]), srcH: Number(viewBox[2]) }
}

const fg = await loadSvg('src/res/launcher/icon.svg')
const mono = await loadSvg('src/res/launcher/icon-mono.svg')

const foreground = buildForegroundVector(fg.shapes, fg.srcW, fg.srcH, false)
const foregroundDebug = buildForegroundVector(fg.shapes, fg.srcW, fg.srcH, false, true)
const monochrome = buildForegroundVector(mono.shapes, mono.srcW, mono.srcH, true)
const settingsIcon = buildSettingsVector(mono.shapes, mono.srcW, mono.srcH)
const background = buildBackgroundVector()
const debugIcon = buildAdaptiveIcon('icon_foreground_inu_debug')

// the *_inu drawables back stock @mipmap/ic_launcher{,_round} (rewired by
// misc__branding); the mipmap-debug wrappers override it for the debug build
const targets: [string, string][] = [
  [`${GEN_DRAWABLE}/icon_background_inu.xml`, background],
  [`${GEN_DRAWABLE}/icon_plane_inu.xml`, monochrome],
  [`${GEN_DRAWABLE}/icon_foreground_inu.xml`, foreground],
  [`${GEN_DRAWABLE}/icon_foreground_inu_round.xml`, foreground],
  [`${GEN_DRAWABLE}/icon_foreground_inu_debug.xml`, foregroundDebug],
  [`${GEN_DRAWABLE}/icon_settings_inu.xml`, settingsIcon],
  [`${GEN_DEBUG_MIPMAP}/ic_launcher.xml`, debugIcon],
  [`${GEN_DEBUG_MIPMAP}/ic_launcher_round.xml`, debugIcon],
]

let dirty = false
for (const [rel, content] of targets) {
  if (await writeGenerated(rel, content)) dirty = true
}

success(dirty ? 'Launcher icons generated' : 'Launcher icons already up to date')
