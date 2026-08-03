/**
 * offscreen 2d drawing, shaped after the web canvas api so the mental model (and most stackoverflow
 * answers) carry over. backed by the platform's native 2d rasterizer, so there's no image library
 * to bundle and nothing to ship per-plugin.
 *
 * it is a *subset*: the parts that map cleanly onto the native rasterizer are here and behave as
 * the spec says, and the rest is absent rather than approximated. absent, and why:
 *
 * - `getImageData` / `putImageData` / `createImageData` — not for the transfer cost (a megapixel is
 *   4 MB, and quickjs can be handed the bitmap's own memory without copying at all), but because
 *   the *loop* would run interpreted: quickjs has no jit, so a per-pixel pass costs tens of
 *   millions of interpreter ops and lands ~two orders of magnitude off native. anything shaped like
 *   a filter belongs in native code; see `filter` below, and `getAverageColor` for the sampling
 *   case that would otherwise be the main reason to read pixels back.
 * - `filter` — absent for now, but most of it maps onto the native rasterizer cleanly and it is the
 *   right home for per-pixel work, so it's the next thing to add here. `brightness`, `contrast`,
 *   `saturate`, `grayscale`, `sepia`, `hue-rotate`, `invert` and `opacity` are all a single 4x5
 *   colour matrix; `drop-shadow` is a shadow layer. `blur` is the awkward one — it needs a render
 *   effect (android 12+) with a downsample-and-composite fallback below that. the css shorthand
 *   would be parsed and mapped, so plugins keep writing spec syntax.
 * - `isPointInPath` / `isPointInStroke` — hit testing; ask if you need it, it's implementable.
 * - `direction`, `letterSpacing`, `wordSpacing`, `fontKerning`, `fontStretch`, `fontVariantCaps`,
 *   `textRendering` — text shaping knobs with no clean mapping.
 * - `drawFocusIfNeeded`, `scrollPathIntoView` — meaningless without a dom.
 *
 * `TextMetrics` carries only the box fields the native rasterizer can answer; the font-relative
 * ones (`emHeightAscent`, `hangingBaseline`, ...) are absent.
 *
 * drawing itself needs no grant — it's pure computation. reading or writing a file does: a path
 * passed to `inu.canvas.load`, and `toFile`, both need `fs` (relative to the plugin's own
 * directory; absolute paths additionally need `fs(full)`).
 */

declare interface CanvasGradient {
  addColorStop(offset: number, color: string): void
}

/** opaque; produced by `createPattern` */
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

/** a decoded bitmap, as `createImageBitmap` would give you on the web */
declare interface ImageBitmap {
  readonly width: number
  readonly height: number
  /** free the underlying bitmap; drawing a closed image throws */
  close(): void
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

/**
 * the classic porter-duff set is always available. the separable blend modes (`multiply` through
 * `luminosity`) need android 10+ and fall back to `source-over` below that.
 */
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

  // -- state --
  save(): void
  restore(): void
  /** resets state, path and contents, as if the context were fresh */
  reset(): void

  // -- transforms --
  scale(x: number, y: number): void
  rotate(angle: number): void
  translate(x: number, y: number): void
  transform(a: number, b: number, c: number, d: number, e: number, f: number): void
  setTransform(a: number, b: number, c: number, d: number, e: number, f: number): void
  setTransform(transform?: DOMMatrix2DInit): void
  resetTransform(): void

  // -- compositing --
  globalAlpha: number
  globalCompositeOperation: GlobalCompositeOperation

  // -- styles --
  /** a css color string, or a gradient/pattern from the `create*` methods */
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
  createPattern(image: CanvasImageSource, repetition: 'repeat' | 'repeat-x' | 'repeat-y' | 'no-repeat' | null): CanvasPattern | null

  // -- rects --
  clearRect(x: number, y: number, w: number, h: number): void
  fillRect(x: number, y: number, w: number, h: number): void
  strokeRect(x: number, y: number, w: number, h: number): void

  // -- paths --
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
  /** `radii` is one radius for every corner, or `[tl, tr, br, bl]` */
  roundRect(x: number, y: number, w: number, h: number, radii?: number | number[]): void
  fill(fillRule?: 'nonzero' | 'evenodd'): void
  stroke(): void
  clip(fillRule?: 'nonzero' | 'evenodd'): void

  // -- text --
  /** css font shorthand, e.g. `'bold 24px Roboto'`. families come from `inu.canvas.loadFont` */
  font: string
  textAlign: 'start' | 'end' | 'left' | 'right' | 'center'
  textBaseline: 'top' | 'hanging' | 'middle' | 'alphabetic' | 'ideographic' | 'bottom'
  /** single-line, exactly like the spec — wrapping is yours to do with `measureText` */
  fillText(text: string, x: number, y: number, maxWidth?: number): void
  strokeText(text: string, x: number, y: number, maxWidth?: number): void
  measureText(text: string): TextMetrics

  /**
   * mean colour of a region, summed natively — not part of the canvas spec.
   *
   * this exists because sampling is the one genuinely useful reason to read pixels back (picking a
   * background off cover art, deciding whether to draw light or dark text over an image), and doing
   * it through `getImageData` would mean an interpreted loop over the whole region to compute four
   * numbers. the region defaults to the whole canvas, and is clipped to it; a region that ends up
   * empty throws.
   *
   * the average is alpha-weighted, so transparent areas don't drag the result toward black —
   * `{ r, g, b }` describe the colour of what's actually painted and `a` how much of the region is
   * covered. a fully transparent region gives `a: 0` with the channels at 0. all four are 0-255.
   */
  getAverageColor(): { r: number, g: number, b: number, a: number }
  getAverageColor(sx: number, sy: number, sw: number, sh: number): { r: number, g: number, b: number, a: number }

  // -- images --
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

  /**
   * encode the current contents. the spec's `convertToBlob` in all but name — there's no `Blob`
   * here, so you get the bytes directly.
   *
   * `quality` applies to `image/jpeg` and `image/webp` only.
   *
   * async because encoding a large bitmap is not cheap: it runs off the plugin's queue, so the
   * plugin's timers and handlers keep running while it does. (nothing here can stall another
   * plugin — each has its own queue; see the execution-model note in `common.d.ts`.)
   */
  toBytes(options?: { type?: 'image/png' | 'image/jpeg' | 'image/webp', quality?: number }): Promise<Uint8Array>
  /**
   * same, written straight to disk — skips materializing the bytes in js, which for a large image
   * is most of the cost, and is the right way to hand a drawing to `sendMedia`.
   *
   * relative paths land in the plugin's own directory; absolute ones need `fs(full)`.
   *
   * what you write here persists until you remove it — it is the plugin's storage. for a drawing
   * you only need long enough to send, write to an `inu.fs.createTempFile()` path instead: those
   * are wiped on unload and don't count against the plugin's storage.
   *
   * @needs-grant fs
   */
  toFile(path: string, options?: { type?: 'image/png' | 'image/jpeg' | 'image/webp', quality?: number }): Promise<void>
}

declare namespace inu {
  namespace canvas {
    /** dimensions are in pixels; there's no device pixel ratio to account for */
    function create(width: number, height: number): OffscreenCanvas

    /**
     * decode an image. stands in for `createImageBitmap`, which takes a `Blob` we don't have.
     *
     * a string is a filesystem path and needs `fs`; bytes need no grant. use `fetch` for
     * network images and pass the bytes.
     */
    function decode(source: Uint8Array): Promise<ImageBitmap>
    /** @needs-grant fs */
    function load(path: string): Promise<ImageBitmap>

    /**
     * register a font file under a family name, so `ctx.font` can name it. the spec equivalent is
     * constructing a `FontFace` and adding it to `document.fonts`.
     *
     * families the host already has (the app's own ui font, and whatever the system ships) are
     * usable without this.
     *
     * @needs-grant fs
     */
    function loadFont(family: string, path: string): Promise<void>
  }
}
