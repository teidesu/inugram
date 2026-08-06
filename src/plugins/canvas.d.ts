/**
 * offscreen 2d drawing, shaped after the web canvas api so the mental model (and most stackoverflow
 * answers) carry over. backed by the platform's native 2d rasterizer, so there's no image library
 * to bundle and nothing to ship per-plugin.
 *
 * where a mode can't be honoured it throws rather than approximating. an earlier draft had the
 * separable blend modes silently falling back to `source-over` on older platforms, which renders
 * the wrong picture and tells nobody. absent is a contract; wrong is not.
 *
 * **drawing is recorded, not performed.** every call appends to a command buffer, and the buffer is
 * handed to the rasterizer only when something actually needs the pixels: `convertToBlob`,
 * `getAverageColor`, or naming this canvas as a source from another one. so a drawing of ten
 * thousand shapes costs one crossing, and `ctx.fillRect(...)` returning is not a promise that
 * anything has been painted yet — an error the rasterizer reports (it ran out of memory, the
 * bitmap is gone) surfaces at whichever of those three points flushed the buffer, not at the call
 * that recorded the shape. the one thing that follows for you: `try`/`catch` around the read, not
 * around the drawing.
 *
 * a consequence of that same design, and the two places it is visible. a `CanvasGradient` is
 * encoded with the stops it has **when it is drawn with**, so `addColorStop` after assigning it to
 * `fillStyle` still counts, exactly as on the web. and an image source — a `drawImage`, or the
 * image behind a `createPattern` — is **read when the buffer is flushed**, not when the call was
 * recorded, so drawing into a source canvas between the two shows up in the copy. if you need the
 * web's snapshot-at-draw-time there, read the destination (`convertToBlob`, `getAverageColor`)
 * before you touch the source again. an `ImageBitmap` is immutable, so this only ever concerns a
 * canvas used as a source; `dispose()` is safe at any point and still frees the memory there and
 * then, flushing whatever named it first.
 *
 * limits, all of them `quota-exceeded` or `invalid-argument` and none of them silent:
 *
 * - a canvas is at most 8192 pixels on a side, and its bitmap (`width * height * 4` bytes) is
 *   charged against the same per-plugin native budget a `Blob` is — see `common.d.ts`. so is a
 *   decoded `ImageBitmap`. `dispose()` gives it back; so does dropping the last reference.
 * - a gradient may carry at most 256 colour stops.
 * - `decode`/`load`/`loadFont` take at most 32 MB of source in one call.
 *
 * three things the platform's rasterizer has no equivalent for, and each throws `'unsupported'`
 * where you ask for it rather than drawing something else:
 *
 * - the separable blend modes below android 10 — see `GlobalCompositeOperation`.
 * - a `createRadialGradient` whose two circles are not concentric. the concentric case (which is
 *   every `(x, y, 0)` → `(x, y, r)` gradient, i.e. nearly all of them) is exact; the focal case
 *   needs a two-point conical shader that does not exist here.
 * - a `createPattern` repetition other than `'repeat'` below android 12, which is where the tile
 *   mode that leaves the outside transparent arrives. below it, `'no-repeat'` and friends would
 *   smear the edge pixel across the whole fill.
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
 * drawing itself needs no grant — it's pure computation — and neither does getting the result out:
 * `convertToBlob` hands back a `Blob` that `sendMedia` takes as-is. only naming a file needs `fs`:
 * a `{ path }` passed to `inu.canvas.load` or `loadFont` (relative to the plugin's own directory;
 * absolute paths additionally need `unsafe.fs`), and `inu.fs.write` if you want to keep the result.
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
  /**
   * free the underlying bitmap; drawing a disposed image throws `handle-expired`. spelled
   * `dispose` rather than the spec's `close` so that everything with a lifetime on this surface
   * (`Blob`, `UIPage`, this) ends the same way
   */
  dispose(): void
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
 * `luminosity`) need android 10+ and throw `'unsupported'` below that, rather than quietly
 * rendering as `source-over` — feature-detect on `inu.info()` if you need to degrade.
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
  /**
   * unlike the spec's, this never answers `null`: the two reasons it would (an image that isn't
   * usable yet, a repetition that isn't one) are a `handle-expired` and an `invalid-argument` here,
   * because there is no loading state on this platform for the first to mean
   */
  createPattern(image: CanvasImageSource, repetition?: 'repeat' | 'repeat-x' | 'repeat-y' | 'no-repeat' | null): CanvasPattern

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
   * encode the current contents, exactly as the spec's `convertToBlob` does.
   *
   * ```ts
   * const png = await canvas.convertToBlob()
   * await inu.account().sendMedia('me', png)
   * ```
   *
   * that send needs no `fs` grant, and there's nothing to clean up — see `Blob`. `.bytes()` if you
   * actually want the bytes in js, `inu.fs.write` to keep it; neither is on the path above, which
   * is the point.
   *
   * `quality` applies to `image/jpeg` and `image/webp` only.
   *
   * async because encoding a large bitmap is not cheap: it runs off the plugin's queue, so the
   * plugin's timers and handlers keep running while it does. (nothing here can stall another
   * plugin — each has its own queue; see the execution-model note in `common.d.ts`.)
   */
  convertToBlob(options?: { type?: 'image/png' | 'image/jpeg' | 'image/webp', quality?: number }): Promise<Blob>
}

declare namespace inu {
  namespace canvas {
    /** dimensions are in pixels; there's no device pixel ratio to account for */
    function create(width: number, height: number): OffscreenCanvas

    /**
     * decode an image. stands in for `createImageBitmap`, which takes a `Blob` we don't have.
     *
     * neither shape needs a grant: bytes are the plugin's own, and a `Blob` can only be one it
     * was handed. a message's photo goes straight from `downloadMedia` into here, and a remote one
     * from `fetch`, without either touching the filesystem. `load` is the same thing from a path.
     */
    function decode(source: Blob | Uint8Array): Promise<ImageBitmap>
    /** @needs-grant fs */
    function load(file: { path: string }): Promise<ImageBitmap>

    /**
     * register a font file under a family name, so `ctx.font` can name it. the spec equivalent is
     * constructing a `FontFace` and adding it to `document.fonts`.
     *
     * families the host already has (the app's own ui font, and whatever the system ships) are
     * usable without this. `{ path }` needs `fs`; bytes need no grant.
     */
    function loadFont(family: string, source: Blob | Uint8Array | { path: string }): Promise<void>
  }
}
