/** A canvas is at most 8192 pixels on a side; a gradient has at most 256 colour stops. */
declare interface CanvasGradient {
  addColorStop(offset: number, color: string): void
}

declare interface CanvasPattern {
  setTransform(transform?: DOMMatrix2DInit): void
}

declare interface DOMMatrix2DInit {
  a?: number
  b?: number
  c?: number
  d?: number
  e?: number
  f?: number
}

declare interface ImageBitmap {
  readonly width: number
  readonly height: number

  /** Frees the decoded bitmap now, rather than when the collector gets to it. Also `using`-able. */
  dispose(): void
  [Symbol.dispose](): void
}

declare interface TextMetrics {
  readonly width: number
  readonly actualBoundingBoxLeft: number
  readonly actualBoundingBoxRight: number
  readonly actualBoundingBoxAscent: number
  readonly actualBoundingBoxDescent: number
  readonly fontBoundingBoxAscent: number
  readonly fontBoundingBoxDescent: number
}

declare type CanvasImageSource = ImageBitmap | OffscreenCanvas

declare type GlobalCompositeOperation
  = | 'source-over' | 'source-in' | 'source-out' | 'source-atop'
    | 'destination-over' | 'destination-in' | 'destination-out' | 'destination-atop'
    | 'lighter' | 'copy' | 'xor'
    | 'multiply' | 'screen' | 'overlay' | 'darken' | 'lighten'
    | 'color-dodge' | 'color-burn' | 'hard-light' | 'soft-light'
    | 'difference' | 'exclusion'
    | 'hue' | 'saturation' | 'color' | 'luminosity'

declare interface CanvasRenderingContext2D {
  readonly canvas: OffscreenCanvas
  save(): void
  restore(): void

  reset(): void
  scale(x: number, y: number): void
  rotate(angle: number): void
  translate(x: number, y: number): void
  transform(a: number, b: number, c: number, d: number, e: number, f: number): void
  setTransform(a: number, b: number, c: number, d: number, e: number, f: number): void
  setTransform(transform?: DOMMatrix2DInit): void
  resetTransform(): void
  globalAlpha: number
  globalCompositeOperation: GlobalCompositeOperation

  fillStyle: string | CanvasGradient | CanvasPattern
  strokeStyle: string | CanvasGradient | CanvasPattern
  lineWidth: number
  lineCap: 'butt' | 'round' | 'square'
  lineJoin: 'round' | 'bevel' | 'miter'
  miterLimit: number
  lineDashOffset: number
  setLineDash(segments: number[]): void
  getLineDash(): number[]

  shadowBlur: number
  shadowColor: string
  shadowOffsetX: number
  shadowOffsetY: number

  createLinearGradient(x0: number, y0: number, x1: number, y1: number): CanvasGradient
  createRadialGradient(x0: number, y0: number, r0: number, x1: number, y1: number, r1: number): CanvasGradient
  createConicGradient(startAngle: number, x: number, y: number): CanvasGradient

  createPattern(image: CanvasImageSource, repetition?: 'repeat' | 'repeat-x' | 'repeat-y' | 'no-repeat' | null): CanvasPattern
  clearRect(x: number, y: number, w: number, h: number): void
  fillRect(x: number, y: number, w: number, h: number): void
  strokeRect(x: number, y: number, w: number, h: number): void
  beginPath(): void
  closePath(): void
  moveTo(x: number, y: number): void
  lineTo(x: number, y: number): void
  bezierCurveTo(cp1x: number, cp1y: number, cp2x: number, cp2y: number, x: number, y: number): void
  quadraticCurveTo(cpx: number, cpy: number, x: number, y: number): void
  arc(x: number, y: number, radius: number, startAngle: number, endAngle: number, counterclockwise?: boolean): void
  arcTo(x1: number, y1: number, x2: number, y2: number, radius: number): void
  ellipse(
    x: number, y: number, radiusX: number, radiusY: number,
    rotation: number, startAngle: number, endAngle: number, counterclockwise?: boolean,
  ): void
  rect(x: number, y: number, w: number, h: number): void

  roundRect(x: number, y: number, w: number, h: number, radii?: number | number[]): void
  fill(fillRule?: 'nonzero' | 'evenodd'): void
  stroke(): void
  clip(fillRule?: 'nonzero' | 'evenodd'): void

  font: string
  textAlign: 'start' | 'end' | 'left' | 'right' | 'center'
  textBaseline: 'top' | 'hanging' | 'middle' | 'alphabetic' | 'ideographic' | 'bottom'

  fillText(text: string, x: number, y: number, maxWidth?: number): void
  strokeText(text: string, x: number, y: number, maxWidth?: number): void
  measureText(text: string): TextMetrics

  getAverageColor(): { r: number, g: number, b: number, a: number }
  getAverageColor(sx: number, sy: number, sw: number, sh: number): { r: number, g: number, b: number, a: number }
  drawImage(image: CanvasImageSource, dx: number, dy: number): void
  drawImage(image: CanvasImageSource, dx: number, dy: number, dw: number, dh: number): void
  drawImage(
    image: CanvasImageSource,
    sx: number, sy: number, sw: number, sh: number,
    dx: number, dy: number, dw: number, dh: number,
  ): void
}

declare interface OffscreenCanvas {
  width: number
  height: number
  getContext(contextId: '2d'): CanvasRenderingContext2D
  convertToBlob(options?: { type?: 'image/png' | 'image/jpeg' | 'image/webp', quality?: number }): Promise<Blob>

  /**
   * Frees the backing bitmap now, rather than when the collector gets to it. Its context, and any
   * pattern made from it, answer `handle-expired` afterwards. Also `using`-able.
   */
  dispose(): void
  [Symbol.dispose](): void
}

declare namespace inu {
  namespace canvas {
    function create(width: number, height: number): OffscreenCanvas

    function decode(source: Blob | Uint8Array): Promise<ImageBitmap>
    /** @needs-grant fs */
    function load(file: { path: string }): Promise<ImageBitmap>

    function loadFont(family: string, source: Blob | Uint8Array | { path: string }): Promise<void>
  }
}
