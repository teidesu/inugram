type SvgAttrs = Record<string, string>

function parseSvgAttrs(raw: string): SvgAttrs {
  const attrs: SvgAttrs = {}
  for (const m of raw.matchAll(/([a-z][\w-]*)\s*=\s*"([^"]*)"/gi)) {
    attrs[m[1]] = m[2]
  }
  return attrs
}

function fmtNum(n: number): string {
  if (!Number.isFinite(n)) throw new Error(`Invalid number: ${n}`)
  return Number.isInteger(n) ? String(n) : String(+n.toFixed(4))
}

function rectPathData(a: SvgAttrs): string {
  const x = Number(a.x ?? 0)
  const y = Number(a.y ?? 0)
  const w = Number(a.width)
  const h = Number(a.height)
  const rx = Number(a.rx ?? a.ry ?? 0)
  const ry = Number(a.ry ?? a.rx ?? 0)
  if (rx === 0 && ry === 0) {
    return `M${fmtNum(x)},${fmtNum(y)}h${fmtNum(w)}v${fmtNum(h)}h${fmtNum(-w)}z`
  }
  return [
    `M${fmtNum(x + rx)},${fmtNum(y)}`,
    `H${fmtNum(x + w - rx)}`,
    `A${fmtNum(rx)},${fmtNum(ry)} 0 0 1 ${fmtNum(x + w)},${fmtNum(y + ry)}`,
    `V${fmtNum(y + h - ry)}`,
    `A${fmtNum(rx)},${fmtNum(ry)} 0 0 1 ${fmtNum(x + w - rx)},${fmtNum(y + h)}`,
    `H${fmtNum(x + rx)}`,
    `A${fmtNum(rx)},${fmtNum(ry)} 0 0 1 ${fmtNum(x)},${fmtNum(y + h - ry)}`,
    `V${fmtNum(y + ry)}`,
    `A${fmtNum(rx)},${fmtNum(ry)} 0 0 1 ${fmtNum(x + rx)},${fmtNum(y)}`,
    'z',
  ].join('')
}

function circlePathData(a: SvgAttrs): string {
  const cx = Number(a.cx)
  const cy = Number(a.cy)
  const r = Number(a.r)
  return `M${fmtNum(cx - r)},${fmtNum(cy)}a${fmtNum(r)},${fmtNum(r)} 0 1,0 ${fmtNum(2 * r)},0a${fmtNum(r)},${fmtNum(r)} 0 1,0 ${fmtNum(-2 * r)},0z`
}

function ellipsePathData(a: SvgAttrs): string {
  const cx = Number(a.cx)
  const cy = Number(a.cy)
  const rx = Number(a.rx)
  const ry = Number(a.ry)
  return `M${fmtNum(cx - rx)},${fmtNum(cy)}a${fmtNum(rx)},${fmtNum(ry)} 0 1,0 ${fmtNum(2 * rx)},0a${fmtNum(rx)},${fmtNum(ry)} 0 1,0 ${fmtNum(-2 * rx)},0z`
}

function linePathData(a: SvgAttrs): string {
  return `M${fmtNum(+a.x1)},${fmtNum(+a.y1)}L${fmtNum(+a.x2)},${fmtNum(+a.y2)}`
}

function polyPathData(a: SvgAttrs, closed: boolean): string {
  const nums = (a.points ?? '').trim().split(/[,\s]+/).map(Number).filter(n => Number.isFinite(n))
  if (nums.length < 4) return ''
  let d = `M${fmtNum(nums[0])},${fmtNum(nums[1])}`
  for (let i = 2; i < nums.length; i += 2) {
    d += `L${fmtNum(nums[i])},${fmtNum(nums[i + 1])}`
  }
  if (closed) d += 'z'
  return d
}

function svgElementPathData(tag: string, attrs: SvgAttrs): string | null {
  switch (tag) {
    case 'path': return attrs.d ?? null
    case 'rect': return rectPathData(attrs)
    case 'circle': return circlePathData(attrs)
    case 'ellipse': return ellipsePathData(attrs)
    case 'line': return linePathData(attrs)
    case 'polyline': return polyPathData(attrs, false)
    case 'polygon': return polyPathData(attrs, true)
  }
  return null
}

interface SvgShape {
  d: string
  fill?: string
  stroke?: string
  strokeWidth?: string
  strokeLineCap?: string
  strokeLineJoin?: string
}

function parseSvgBody(body: string): SvgShape[] {
  const shapes: SvgShape[] = []
  const stack: SvgAttrs[] = [{}]
  // eslint-disable-next-line regexp/no-super-linear-backtracking
  const tagRe = /<(\/)?([a-z]+)([^>]*)>/gi
  let m: RegExpExecArray | null
  // eslint-disable-next-line no-cond-assign
  while ((m = tagRe.exec(body)) !== null) {
    const [, closing, tag, rest] = m
    if (closing) {
      if (tag === 'g') stack.pop()
      continue
    }
    const selfClose = rest.trimEnd().endsWith('/')
    const attrStr = selfClose ? rest.slice(0, rest.lastIndexOf('/')) : rest
    const attrs = parseSvgAttrs(attrStr)
    const merged = { ...stack[stack.length - 1], ...attrs }
    if (tag === 'g') {
      if (!selfClose) stack.push(merged)
      continue
    }
    const d = svgElementPathData(tag, attrs)
    if (!d) continue
    shapes.push({
      d,
      fill: merged.fill,
      stroke: merged.stroke,
      strokeWidth: merged['stroke-width'],
      strokeLineCap: merged['stroke-linecap'],
      strokeLineJoin: merged['stroke-linejoin'],
    })
  }
  return shapes
}

const DEFAULT_COLOR = '#ffffff'

function normalizeSvgColor(value: string): string {
  if (value === 'currentColor') return DEFAULT_COLOR
  if (value.startsWith('#')) {
    let hex = value.slice(1).toUpperCase()
    if (hex.length === 3) hex = [...hex].map(c => c + c).join('')
    if (hex.length === 6) hex = `FF${hex}`
    return `#${hex}`
  }
  return value
}

function resolveFillColor(value: string | undefined): string | null {
  if (value === 'none') return null
  if (!value) return DEFAULT_COLOR
  return normalizeSvgColor(value)
}

function resolveStrokeColor(value: string | undefined): string | null {
  if (!value || value === 'none') return null
  return normalizeSvgColor(value)
}

export interface SvgToDrawableOptions {
  overrideStrokeWidth?: number
  paddingInset?: number
}

export function svgBodyToVectorDrawable(body: string, width: number, height: number, options?: SvgToDrawableOptions): string {
  const shapes = parseSvgBody(body)
  const inset = options?.paddingInset ?? 0
  const vpW = width - 2 * inset
  const vpH = height - 2 * inset
  const strokeScale = vpW / width
  const paths = shapes.map(shape => {
    const attrs: string[] = [`android:pathData="${shape.d}"`]
    const fill = resolveFillColor(shape.fill)
    const stroke = resolveStrokeColor(shape.stroke)
    if (fill) attrs.push(`android:fillColor="${fill}"`)
    if (stroke) {
      const baseStroke = options?.overrideStrokeWidth ?? Number(shape.strokeWidth ?? 1)
      attrs.push(`android:strokeColor="${stroke}"`)
      attrs.push(`android:strokeWidth="${fmtNum(baseStroke * strokeScale)}"`)
      if (shape.strokeLineCap) attrs.push(`android:strokeLineCap="${shape.strokeLineCap}"`)
      if (shape.strokeLineJoin) attrs.push(`android:strokeLineJoin="${shape.strokeLineJoin}"`)
    }
    return `    <path\n        ${attrs.join('\n        ')} />`
  })
  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${width}dp"
    android:height="${height}dp"
    android:viewportWidth="${fmtNum(vpW)}"
    android:viewportHeight="${fmtNum(vpH)}">
    <group
        android:translateX="${fmtNum(-inset)}"
        android:translateY="${fmtNum(-inset)}">
${paths.join('\n')}
    </group>
</vector>
`
}
