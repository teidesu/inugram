import fs from 'node:fs/promises'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { rootDir, worktreeDir } from './config.js'

const RES_DIR = join(worktreeDir, 'TMessagesProj/src/main/res')
const OUT_FILE = join(rootDir, 'out/icons.html')

const TAG_RE = /<(\/?)([\w-]+)((?:\s+[\w:]+="[^"]*")*)\s*(\/?)>/g
const ATTR_RE = /([\w:]+)="([^"]*)"/g

function toSvgColor(value: string | undefined): string | undefined {
  if (value == null) return undefined
  // theme and resource references are tinted at runtime anyway
  if (!value.startsWith('#')) return 'currentColor'
  const hex = value.slice(1)
  if (hex.length === 8) return `#${hex.slice(2)}${hex.slice(0, 2)}`
  if (hex.length === 4) return `#${hex.slice(1)}${hex[0]}`
  return value
}

// ponytail: gradients (aapt:attr) and clip-paths are dropped, parse them if those icons matter
function convertVectorToSvg(xml: string): string | null {
  let svg = ''
  for (const [, closing, tag, rawAttrs, selfClosing] of xml.matchAll(TAG_RE)) {
    const attrs: Record<string, string> = {}
    for (const [, key, value] of rawAttrs.matchAll(ATTR_RE)) attrs[key.replace('android:', '')] = value
    if (tag === 'vector') {
      if (closing) return `${svg}</svg>`
      svg += `<svg viewBox="0 0 ${attrs.viewportWidth} ${attrs.viewportHeight}">`
    } else if (tag === 'group') {
      if (closing) {
        svg += '</g>'
        continue
      }
      const pivotX = attrs.pivotX ?? 0
      const pivotY = attrs.pivotY ?? 0
      const scaleX = attrs.scaleX ?? 1
      const scaleY = attrs.scaleY ?? 1
      svg += `<g transform="translate(${attrs.translateX ?? 0} ${attrs.translateY ?? 0}) rotate(${attrs.rotation ?? 0} ${pivotX} ${pivotY}) translate(${pivotX} ${pivotY}) scale(${scaleX} ${scaleY}) translate(${-pivotX} ${-pivotY})">${selfClosing ? '</g>' : ''}`
    } else if (tag === 'path' && !closing && attrs.pathData) {
      svg += `<path d="${attrs.pathData}" fill="${toSvgColor(attrs.fillColor) ?? 'none'}" fill-rule="${attrs.fillType === 'evenOdd' ? 'evenodd' : 'nonzero'}"`
      if (attrs.fillAlpha) svg += ` fill-opacity="${attrs.fillAlpha}"`
      if (attrs.strokeColor) {
        svg += ` stroke="${toSvgColor(attrs.strokeColor)}" stroke-width="${attrs.strokeWidth ?? 0}" stroke-linecap="${attrs.strokeLineCap ?? 'butt'}" stroke-linejoin="${attrs.strokeLineJoin ?? 'miter'}"`
        if (attrs.strokeAlpha) svg += ` stroke-opacity="${attrs.strokeAlpha}"`
      }
      svg += '/>'
    }
  }
  return null
}

const icons = new Map<string, string>()
for (const density of ['drawable-xxhdpi', 'drawable-xhdpi', 'drawable-hdpi', 'drawable-mdpi', 'drawable-nodpi', 'drawable']) {
  const dir = join(RES_DIR, density)
  for (const file of await fs.readdir(dir)) {
    const [name, extension] = file.split('.')
    if (icons.has(name)) continue
    if (extension === 'xml') {
      const svg = convertVectorToSvg(await fs.readFile(join(dir, file), 'utf8'))
      if (svg) icons.set(name, svg)
    } else if (extension === 'webp' || extension === 'png') {
      icons.set(name, `<img loading="lazy" src="${pathToFileURL(join(dir, file)).href}">`)
    }
  }
}

const cells = [...icons].sort(([a], [b]) => a.localeCompare(b)).map(([name, markup]) => `<figure data-name="${name}">${markup}<figcaption>${name}</figcaption></figure>`)

await fs.mkdir(join(rootDir, 'out'), { recursive: true })
await fs.writeFile(OUT_FILE, `<!doctype html>
<meta charset="utf-8">
<title>icons</title>
<style>
  body { margin: 0; padding: 16px; font: 11px/1.3 system-ui; background: #222; color: #ccc; }
  body.light { background: #ddd; color: #333; }
  header { position: sticky; top: 0; padding: 8px 0; background: inherit; display: flex; gap: 8px; }
  input { flex: 1; font-size: 14px; padding: 6px 10px; }
  main { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 4px; }
  figure { margin: 0; padding: 8px 4px; text-align: center; cursor: pointer; border-radius: 6px; overflow: hidden; }
  figure:hover { background: #8884; }
  figure > :first-child { width: 36px; height: 36px; object-fit: contain; }
  figcaption { margin-top: 6px; word-break: break-all; }
</style>
<header>
  <input type="search" placeholder="filter ${cells.length} icons, click to copy the name" autofocus>
  <button onclick="document.body.classList.toggle('light')">background</button>
</header>
<main>${cells.join('\n')}</main>
<script>
  const figures = [...document.querySelectorAll('figure')]
  document.querySelector('input').addEventListener('input', (e) => {
    const query = e.target.value.toLowerCase()
    for (const figure of figures) figure.hidden = !figure.dataset.name.includes(query)
  })
  document.querySelector('main').addEventListener('click', (e) => {
    const figure = e.target.closest('figure')
    if (figure) navigator.clipboard.writeText(figure.dataset.name)
  })
</script>
`)
console.log(`${cells.length} icons -> ${OUT_FILE}`)
