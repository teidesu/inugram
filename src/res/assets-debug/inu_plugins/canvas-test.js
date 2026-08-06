// ==UserScript==
// @name         canvas test
// @author       teidesu
// @version      1.0
// @description  exercises inu.canvas: the context surface, the state stack, gradients and patterns, text, images and convertToBlob
// @plugin-api   1
// @platform     android
// ==/UserScript==

function pass(label, detail) {
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function expectThrows(label, body, code) {
  try {
    body()
  } catch (e) {
    check(label, e instanceof inu.PluginError && e.code === code, e && `${e.code}: ${e.message}`)
    return e
  }
  fail(label, `expected ${code}, nothing was thrown`)
  return undefined
}

async function expectRejects(label, promise, code) {
  try {
    await promise
  } catch (e) {
    check(label, e instanceof inu.PluginError && e.code === code, e && `${e.code}: ${e.message}`)
    return e
  }
  fail(label, `expected ${code}, it resolved`)
  return undefined
}

// -- the canvas itself --

const canvas = inu.canvas.create(200, 100)
check('a canvas answers the size it was made with', canvas.width === 200 && canvas.height === 100)

const ctx = canvas.getContext('2d')
check('getContext answers the same context every time', ctx === canvas.getContext('2d'))
check('a context points back at its canvas', ctx.canvas === canvas)

expectThrows('an impossible size is refused', () => inu.canvas.create(0, 10), 'invalid-argument')
expectThrows('a canvas past the ceiling is refused', () => inu.canvas.create(99999, 1), 'invalid-argument')
// @ts-expect-error deliberately not the one context id there is
expectThrows('only 2d exists', () => canvas.getContext('webgl'), 'invalid-argument')

// -- initial state, which is the spec's and not ours --

check('the initial fill is opaque black', ctx.fillStyle === '#000000', ctx.fillStyle)
check('the initial line width is 1', ctx.lineWidth === 1)
check('the initial composite is source-over', ctx.globalCompositeOperation === 'source-over')
check('the initial alpha is 1', ctx.globalAlpha === 1)
check('the initial font is the spec default', ctx.font === '10px sans-serif', ctx.font)
check('the initial baseline is alphabetic', ctx.textBaseline === 'alphabetic')
check('the dash list starts empty', ctx.getLineDash().length === 0)

// -- colours --

ctx.fillStyle = 'red'
check('a named colour round trips as hex', ctx.fillStyle === '#ff0000', ctx.fillStyle)
ctx.fillStyle = 'rgb(1 2 3 / 50%)'
check('a modern rgb() with alpha parses', ctx.fillStyle.startsWith('rgba(1, 2, 3'), ctx.fillStyle)
ctx.fillStyle = 'hsl(120 100% 50%)'
check('hsl parses', ctx.fillStyle === '#00ff00', ctx.fillStyle)
ctx.fillStyle = '#abc'
check('the three-digit hex doubles each channel', ctx.fillStyle === '#aabbcc', ctx.fillStyle)
ctx.fillStyle = 'this is not a colour'
check('an unreadable colour leaves the last one alone', ctx.fillStyle === '#aabbcc', ctx.fillStyle)

// -- the state stack --

ctx.save()
ctx.fillStyle = '#123456'
ctx.lineWidth = 7
ctx.translate(10, 10)
ctx.restore()
check('restore puts back every property', ctx.fillStyle === '#aabbcc' && ctx.lineWidth === 1)
ctx.restore()
pass('restoring an empty stack is a no-op')

ctx.fillStyle = 'blue'
ctx.reset()
check('reset returns the context to its initial state', ctx.fillStyle === '#000000')

// -- setters that ignore what they cannot use --

ctx.globalAlpha = 0.25
ctx.globalAlpha = 5
check('an out-of-range alpha is ignored, not clamped', ctx.globalAlpha === 0.25)
ctx.lineWidth = 3
ctx.lineWidth = 0
check('a zero line width is ignored', ctx.lineWidth === 3)
ctx.lineCap = 'round'
// @ts-expect-error deliberately not a line cap
ctx.lineCap = 'squircle'
check('an unknown line cap is ignored', ctx.lineCap === 'round')
ctx.setLineDash([4, 2, 6])
check('an odd dash list is doubled', ctx.getLineDash().join(',') === '4,2,6,4,2,6', ctx.getLineDash().join(','))
ctx.setLineDash([1, -1])
check('one bad dash entry throws the whole list away', ctx.getLineDash().join(',') === '4,2,6,4,2,6')

// -- gradients --

const linear = ctx.createLinearGradient(0, 0, 100, 0)
linear.addColorStop(0, '#000000')
linear.addColorStop(1, 'white')
pass('a linear gradient takes stops')
expectThrows('a stop outside 0..1 is refused', () => linear.addColorStop(2, 'red'), 'invalid-argument')
expectThrows('a stop needs a real colour', () => linear.addColorStop(0.5, 'nope'), 'invalid-argument')
expectThrows(
  'a radial gradient refuses a negative radius',
  () => ctx.createRadialGradient(0, 0, -1, 0, 0, 5),
  'invalid-argument',
)

const stops = ctx.createLinearGradient(0, 0, 1, 0)
expectThrows(
  'a gradient stops taking stops at its ceiling',
  () => {
    for (let i = 0; i < 300; i++) stops.addColorStop(i / 1000, 'red')
  },
  'quota-exceeded',
)

ctx.fillStyle = linear
check('a gradient reads back as a gradient', typeof ctx.fillStyle === 'object' && ctx.fillStyle !== null)
ctx.fillStyle = '#000000'

// -- patterns --

const tile = inu.canvas.create(8, 8)
const pattern = ctx.createPattern(tile, 'repeat')
pattern.setTransform({ a: 2, d: 2 })
pass('a pattern takes a transform')
// @ts-expect-error deliberately not a repetition
expectThrows('an unknown repetition is refused', () => ctx.createPattern(tile, 'tile'), 'invalid-argument')
// @ts-expect-error deliberately not an image
expectThrows('a pattern needs an image', () => ctx.createPattern(42, 'repeat'), 'invalid-argument')

// -- paths --

ctx.beginPath()
ctx.moveTo(0, 0)
ctx.lineTo(50, 0)
ctx.arcTo(100, 0, 100, 100, 20)
ctx.quadraticCurveTo(110, 110, 120, 120)
ctx.bezierCurveTo(130, 130, 140, 140, 150, 150)
ctx.arc(50, 50, 10, 0, Math.PI)
ctx.ellipse(50, 50, 10, 5, 0.5, 0, Math.PI * 2, true)
ctx.rect(0, 0, 10, 10)
ctx.roundRect(0, 0, 10, 10, [4, 2])
ctx.closePath()
ctx.fill()
ctx.fill('evenodd')
ctx.stroke()
ctx.clip()
pass('every path op is accepted and drawn with')

expectThrows('a negative arc radius is refused', () => ctx.arc(0, 0, -1, 0, 1), 'invalid-argument')
expectThrows('a negative arcTo radius is refused', () => ctx.arcTo(1, 1, 2, 2, -1), 'invalid-argument')
expectThrows('a negative corner radius is refused', () => ctx.roundRect(0, 0, 10, 10, -1), 'invalid-argument')
// @ts-expect-error deliberately not a fill rule
expectThrows('an unknown fill rule is refused', () => ctx.fill('winding'), 'invalid-argument')

// -- transforms --

ctx.reset()
ctx.setTransform(2, 0, 0, 2, 5, 5)
ctx.transform(1, 0, 0, 1, 1, 1)
ctx.scale(2, 2)
ctx.rotate(0.5)
ctx.translate(1, 1)
ctx.resetTransform()
ctx.setTransform({ a: 1, d: 1 })
pass('every transform op is accepted')
ctx.setTransform(NaN, 0, 0, 1, 0, 0)
ctx.fillRect(0, 0, 1, 1)
pass('a non-finite transform argument is ignored rather than poisoning the context')

// -- text --

ctx.reset()
ctx.font = 'italic small-caps bold 24px/30px "PT Sans", serif'
check('a full css font shorthand parses', ctx.font.includes('24px'), ctx.font)
ctx.font = 'not a font'
check('an unreadable font leaves the last one alone', ctx.font.includes('24px'))
ctx.textAlign = 'center'
ctx.textBaseline = 'top'
check('the text knobs take what the contract declares', ctx.textAlign === 'center' && ctx.textBaseline === 'top')
ctx.fillText('hello', 10, 20)
ctx.fillText('hello', 10, 20, 40)
ctx.strokeText('hello', 10, 20)
pass('text draws in both forms and with a maxWidth')

const metrics = ctx.measureText('hello')
check(
  'measureText answers the box fields the contract declares',
  ['width', 'actualBoundingBoxLeft', 'actualBoundingBoxRight', 'actualBoundingBoxAscent',
    'actualBoundingBoxDescent', 'fontBoundingBoxAscent', 'fontBoundingBoxDescent']
    .every((k) => typeof metrics[k] === 'number'),
  JSON.stringify(metrics),
)

// -- sampling --

const average = ctx.getAverageColor()
check(
  'getAverageColor answers four channels',
  ['r', 'g', 'b', 'a'].every((k) => typeof average[k] === 'number'),
  JSON.stringify(average),
)
ctx.getAverageColor(0, 0, 10, 10)
pass('getAverageColor takes a region')
expectThrows('a non-finite region is refused', () => ctx.getAverageColor(NaN, 0, 1, 1), 'invalid-argument')

// -- images --

// @ts-expect-error deliberately not an image
expectThrows('drawImage refuses something that is not an image', () => ctx.drawImage({}, 0, 0), 'invalid-argument')
// @ts-expect-error deliberately a count no overload has
expectThrows('drawImage refuses a coordinate count it has no overload for', () => ctx.drawImage(tile, 1, 2, 3), 'invalid-argument')
ctx.drawImage(tile, 0, 0)
ctx.drawImage(tile, 0, 0, 4, 4)
ctx.drawImage(tile, 0, 0, 8, 8, 0, 0, 16, 16)
pass('drawImage takes all three overloads')

// -- encoding, decoding, fonts --

// @ts-expect-error deliberately not an encoding this canvas writes
expectThrows('convertToBlob refuses an encoding it does not write', () => canvas.convertToBlob({ type: 'image/gif' }), 'invalid-argument')
// @ts-expect-error deliberately none of the shapes a source may take
expectThrows('decode refuses a source that is none of the three shapes', () => inu.canvas.decode(42), 'invalid-argument')
expectThrows('loadFont needs a family name', () => inu.canvas.loadFont('', new Uint8Array([1])), 'invalid-argument')
expectThrows(
  'naming a file without the fs grant is refused',
  () => inu.canvas.load({ path: 'a.png' }),
  'not-granted',
)

;(async () => {
  const png = await canvas.convertToBlob()
  check('convertToBlob answers a Blob', png instanceof Blob, `${png.size} bytes, ${png.type}`)

  const image = await inu.canvas.decode(new Uint8Array([1, 2, 3]))
  check('decode answers an image with a size', image.width > 0 && image.height > 0, `${image.width}x${image.height}`)
  ctx.drawImage(image, 0, 0)
  pass('a decoded image draws')

  const held = ctx.createPattern(image, 'repeat')
  image.dispose()
  image.dispose()
  pass('disposing an image twice is a no-op')
  expectThrows('drawing a disposed image is handle-expired', () => ctx.drawImage(image, 0, 0), 'handle-expired')
  ctx.fillStyle = held
  expectThrows('a pattern over a disposed image fails where it is painted', () => ctx.fillRect(0, 0, 1, 1), 'handle-expired')
  ctx.fillStyle = '#000000'

  await inu.canvas.loadFont('Oracle Sans', new Uint8Array([1]))
  ctx.font = '12px "Oracle Sans"'
  check('a loaded family can be named', ctx.font.includes('Oracle Sans'), ctx.font)

  await expectRejects('a decode the host refuses rejects', inu.canvas.decode(new Uint8Array([0])), 'invalid-argument')

  console.log('canvas test done')
})()
