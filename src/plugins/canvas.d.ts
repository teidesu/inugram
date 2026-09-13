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

/** an opaque pointer to a decoded image */
declare interface ImageBitmap {
  readonly width: number
  readonly height: number

  /** dispose the bitmap and the underlying memory */
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

/**
 * a 2d context for drawing on an offscreen canvas
 *
 * the supported api is a strict subset of the web api,
 * if something is not correctly implemented comparet to the browser,
 * it is a plugin engine bug, please report it
 */
declare interface CanvasRenderingContext2D {
  /** the canvas this context is drawing on */
  readonly canvas: OffscreenCanvas
  /** save the current state */
  save(): void
  /** restore the last saved state */
  restore(): void
  /** reset the state to default */
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

/** an offscreen canvas that can be drawn on, semantically similar to web OffscreenCanvas */
declare interface OffscreenCanvas {
  /** canvas width */
  width: number
  /** canvas height */
  height: number
  /** get a 2d context for drawing on the canvas */
  getContext(contextId: '2d'): CanvasRenderingContext2D

  /** render the canvas into a Blob with an image */
  convertToBlob(options?: {
    type?: 'image/png' | 'image/jpeg' | 'image/webp'
    quality?: number
  }): Promise<Blob>

  /** dispose the canvas */
  dispose(): void
  [Symbol.dispose](): void
}

/** one family of {@link inu.canvas.listFonts} */
declare interface FontEntry {
  /** name of the font, to be used in {@link CanvasRenderingContext2D.font} */
  readonly name: string

  /**
   * where the family comes from:
   * - `builtin` - bundled with the app
   * - `imported` - was manually added by the user
   * - `device` - system's built-in font
   * - `plugin` - loaded by this plugin.
   */
  readonly source: 'builtin' | 'imported' | 'device' | 'plugin'

  /** whether the font is hidden from the painter roster */
  readonly hidden: boolean
}

/** one frame of an {@link AnimatedImage} */
declare interface AnimationFrame extends ImageBitmap {
  /** ms from the start of the animation */
  readonly timestamp: number
}

/**
 * An animated source (tgs/webm/mp4), opened for frame-by-frame reading.
 *
 * **Limits: at most 4 at once per plugin.**
 *
 * It is an async iterable of its frames in order:
 *
 * ```js
 * for await (using frame of animation) ctx.drawImage(frame, 0, 0)
 * ```
 */
declare interface AnimatedImage extends AsyncIterableIterator<AnimationFrame> {
  /** frame width (note: will match the one asked for, if any) */
  readonly width: number
  /** frame height (note: will match the one asked for, if any) */
  readonly height: number
  /**
   * total number of frames.
   *
   * for variable-rate gifs, this value is estimated from {@link duration} and {@link fps},
   * making it an upper bound
   */
  readonly frameCount: number
  /** animation duration in milliseconds, `0` when not available */
  readonly duration: number
  /** animation fps rate, `0` when not available */
  readonly fps: number

  /** read the next frame */
  next(): Promise<IteratorResult<AnimationFrame, undefined>>

  /**
   * read a frame by its specific index
   *
   * note that video codecs are not optimized for random access, so skipping forward
   * will require decoding intermediate frames from the key frame
   */
  frame(index: number): Promise<AnimationFrame>

  /** dispose the reader */
  dispose(): void
  [Symbol.dispose](): void
}

/**
 * A video encoder, one frame at a time.
 *
 * **Limits: at most 2 at once per plugin, at most 3600 frames**.
 *
 * currently the only supported output is a slient mp4.
 */
declare interface VideoEncoder {
  readonly width: number
  readonly height: number

  /**
   * append a frame to the video
   *
   * when the source does not match the encoder's dimensions, it is scaled to fit
   *
   * @param source the image to append
   * @param durationMs defaults to one frame at the `fps` the encoder was created with
   */
  addFrame(source: CanvasImageSource, durationMs?: number): Promise<void>

  /** finalize the encoder and return a Blob with the video */
  finish(): Promise<Blob>

  /** cancel encoding and dispose the encoder */
  dispose(): void
  [Symbol.dispose](): void
}

declare namespace inu {
  namespace canvas {
    function create(width: number, height: number): OffscreenCanvas

    function decode(source: Blob | Uint8Array): Promise<ImageBitmap>
    /** @needs-grant fs */
    function load(file: { path: string }): Promise<ImageBitmap>

    /**
     * Opens an animated source (tgs/webm/mp4) for reading.
     *
     * @needs-grant fs to name a file
     */
    function decodeAnimation(
      source: Blob | Uint8Array | { path: string },
      options?: {
        /** width to decode at */
        width?: number
        /** height to decode at */
        height?: number
      },
    ): Promise<AnimatedImage>

    /** create a video encoder */
    function createEncoder(options: {
      /** mime type of the output file */
      type?: 'video/mp4'
      /** width of the output video */
      width: number
      /** height of the output video */
      height: number
      /** frames per second */
      fps?: number
      /** bitrate in bits per second */
      bitrate?: number
    }): Promise<VideoEncoder>

    function loadFont(family: string, source: Blob | Uint8Array | { path: string }): Promise<void>

    /** list of fonts available for use in {@link CanvasRenderingContext2D.font} */
    function listFonts(): Promise<FontEntry[]>
  }
}
