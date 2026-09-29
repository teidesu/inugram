# Canvas

`inu.canvas` is an offscreen 2D rasterizer. It (mostly) follows the web's `OffscreenCanvas` and
`CanvasRenderingContext2D`, and draws with Android's own graphics stack. Use it to make images,
stickers, avatars or short videos, then save them, send them, or show them as icons.

It needs no grant, except where it reads a file by path.

```ts
using canvas = inu.canvas.create(512, 512)
const ctx = canvas.getContext('2d')
ctx.fillStyle = '#2a9d8f'
ctx.fillRect(0, 0, 512, 512)
ctx.font = 'bold 64px sans-serif'
ctx.textAlign = 'center'
ctx.fillStyle = 'white'
ctx.fillText('woof', 256, 280)
using png = await canvas.convertToBlob({ type: 'image/png' })
```

## What is missing

- A canvas is not displayed on screen. It only outputs as a `Blob` or alike
- There is no raw pixel access, because it would be too expensive to run in QuickJS
  - `getAverageColor` covers the common case of sampling a color
  - If you actually do need raw pixels, please reach out with your use case
- Not every member of the web API exists.

## How drawing works

`inu.canvas` is backed by a real Android `Bitmap` and `Canvas`.
For performance reasons (to reduce JNI crossings), drawing calls are batched when:

- about 1 MB of commands has built up,
- you read something (e.g.`measureText`, `getAverageColor`, `convertToBlob`)
- another operation needs the canvas as it is now, such as using it as an image source.

### Limits

- A canvas is at most 8192 pixels on a side.
- Every canvas, image, animation frame and encoder buffer is charged 4 bytes per pixel against
  the plugin's native memory budget.
- Setting `width` or `height` clears the pixels, even when the size does not change. Unlike the
  web, the context keeps its state (styles, transform, font). Call `ctx.reset()` if you want both.

Dispose what you are done with. `dispose()` and `using` free the memory right away. Garbage
collection works too, but disposing it manually means reclaiming the memory right away.

## Text and fonts

`font` takes a regular CSS font shorthand: style, variant, weight, stretch, size with an optional line
height, then a family list. Stretch and line height are accepted and ignored.

A family name is looked up in this order:

1. fonts this plugin loaded with `inu.canvas.loadFont`,
2. the app's font roster: built-in fonts, fonts the user imported, and device fonts when the user enabled them in font settings,
3. the system default.

An unknown family falls back to regular font.

You can use `inu.canvas.listFonts()` to list the available fonts (i.e. the app's font roster),
and you can also use `inu.canvas.loadFont(family, source)` to load a custom font file

## Animations and videos

`inu.canvas.decodeAnimation(source)` opens an animated sticker (tgs), a video (webm, mp4) or a
GIF for reading frame by frame. A still image opens as a one-frame animation.

```ts
async function drawEveryFrame(file: Blob, canvas: OffscreenCanvas) {
  const ctx = canvas.getContext('2d')
  using animation = await inu.canvas.decodeAnimation(file, { width: 256, height: 256 })
  for await (using frame of animation) {
    ctx.drawImage(frame, 0, 0)
  }
}
```

Then, you can use `inu.canvas.createEncoder({ width, height, fps?, bitrate? })` to actually encode the video
into a silent MP4:

```ts
async function renderClip() {
  using canvas = inu.canvas.create(320, 320)
  const ctx = canvas.getContext('2d')
  using encoder = await inu.canvas.createEncoder({ width: 320, height: 320, fps: 30 })
  for (let i = 0; i < 60; i++) {
    ctx.fillStyle = `hsl(${i * 6}, 80%, 50%)`
    ctx.fillRect(0, 0, 320, 320)
    await encoder.addFrame(canvas)
  }
  return await encoder.finish()
}
```
