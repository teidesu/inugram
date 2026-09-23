// ==InuPlugin==
// @name         canvas test
// @description  exercises inu.canvas: the context surface, the state stack, gradients and patterns, text, images and convertToBlob
// ==/InuPlugin==

const canvas = inu.canvas.create(200, 100)
check('a canvas answers the size it was made with', canvas.width === 200 && canvas.height === 100)

const ctx = canvas.getContext('2d')
check('getContext answers the same context every time', ctx === canvas.getContext('2d'))
check('a context points back at its canvas', ctx.canvas === canvas)

expectThrow('an impossible size is refused', 'invalid-argument', () => inu.canvas.create(0, 10))
expectThrow('a canvas past the ceiling is refused', 'invalid-argument', () => inu.canvas.create(99999, 1))
// @ts-expect-error deliberately not the one context id there is
expectThrow('only 2d exists', 'invalid-argument', () => canvas.getContext('webgl'))

check('the initial fill is opaque black', ctx.fillStyle === '#000000', ctx.fillStyle)
check('the initial line width is 1', ctx.lineWidth === 1)
check('the initial composite is source-over', ctx.globalCompositeOperation === 'source-over')
check('the initial alpha is 1', ctx.globalAlpha === 1)
check('the initial font is the spec default', ctx.font === '10px sans-serif', ctx.font)
check('the initial baseline is alphabetic', ctx.textBaseline === 'alphabetic')
check('the dash list starts empty', ctx.getLineDash().length === 0)

ctx.fillStyle = 'red'
check('a named colour round trips as hex', ctx.fillStyle === '#ff0000', ctx.fillStyle)
ctx.fillStyle = 'rgb(1 2 3 / 50%)'
check('a modern rgb() with alpha parses', ctx.fillStyle === 'rgba(1, 2, 3, 0.502)', ctx.fillStyle)
ctx.fillStyle = 'hsl(120 100% 50%)'
check('hsl parses', ctx.fillStyle === '#00ff00', ctx.fillStyle)
ctx.fillStyle = '#abc'
check('the three-digit hex doubles each channel', ctx.fillStyle === '#aabbcc', ctx.fillStyle)
ctx.fillStyle = 'this is not a colour'
check('an unreadable colour leaves the last one alone', ctx.fillStyle === '#aabbcc', ctx.fillStyle)

ctx.save()
ctx.fillStyle = '#123456'
ctx.lineWidth = 7
ctx.font = 'italic bold 20px Roboto'
ctx.translate(10, 10)
ctx.restore()
check('restore puts back every property', ctx.fillStyle === '#aabbcc' && ctx.lineWidth === 1 && ctx.font === '10px sans-serif')
ctx.restore()
pass('restoring an empty stack is a no-op')

ctx.fillStyle = 'blue'
ctx.reset()
check('reset returns the context to its initial state', ctx.fillStyle === '#000000')

ctx.globalAlpha = 0.25
ctx.globalAlpha = 5
check('an out-of-range alpha is ignored, not clamped', ctx.globalAlpha === 0.25)
ctx.lineWidth = 3
ctx.lineWidth = 0
check('a zero line width is ignored', ctx.lineWidth === 3)
ctx.lineCap = 'round'
// @ts-expect-error deliberately not a line cap
ctx.lineCap = 'squircle'
// @ts-expect-error deliberately not a composite mode
ctx.globalCompositeOperation = 'nonsense'
check('an unknown line cap or composite is ignored', ctx.lineCap === 'round' && ctx.globalCompositeOperation === 'source-over')
ctx.setLineDash([4, 2, 6])
check('an odd dash list is doubled', ctx.getLineDash().join(',') === '4,2,6,4,2,6', ctx.getLineDash().join(','))
ctx.setLineDash([1, -1])
check('one bad dash entry throws the whole list away', ctx.getLineDash().join(',') === '4,2,6,4,2,6')

const linear = ctx.createLinearGradient(0, 0, 100, 0)
linear.addColorStop(0, '#000000')
linear.addColorStop(1, 'white')
pass('a linear gradient takes stops')
expectDomException('a stop outside 0..1 is refused', 'IndexSizeError', () => linear.addColorStop(2, 'red'))
expectDomException('a stop needs a real colour', 'SyntaxError', () => linear.addColorStop(0.5, 'nope'))
expectThrow('a stop needs a real offset', TypeError, () => linear.addColorStop(NaN, 'red'))
expectDomException('a radial gradient refuses a negative radius', 'IndexSizeError', () => ctx.createRadialGradient(0, 0, -1, 0, 0, 5))

const stops = ctx.createLinearGradient(0, 0, 1, 0)
expectThrow('a gradient stops taking stops at its ceiling', 'quota-exceeded', () => {
  for (let i = 0; i < 300; i++) stops.addColorStop(i / 1000, 'red')
})

ctx.fillStyle = linear
check('a gradient reads back as a gradient', typeof ctx.fillStyle === 'object' && ctx.fillStyle !== null)
ctx.fillStyle = '#000000'

const tile = inu.canvas.create(8, 8)
const pattern = ctx.createPattern(tile, 'repeat')
pattern.setTransform({ a: 2, d: 2 })
pass('a pattern takes a transform')
// @ts-expect-error deliberately not a repetition
expectDomException('an unknown repetition is refused', 'SyntaxError', () => ctx.createPattern(tile, 'tile'))
// @ts-expect-error deliberately not an image
expectThrow('a pattern needs an image', TypeError, () => ctx.createPattern(42, 'repeat'))

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

expectDomException('a negative arc radius is refused', 'IndexSizeError', () => ctx.arc(0, 0, -1, 0, 1))
expectDomException('a negative arcTo radius is refused', 'IndexSizeError', () => ctx.arcTo(1, 1, 2, 2, -1))
expectThrow('a negative corner radius is refused', RangeError, () => ctx.roundRect(0, 0, 10, 10, -1))
// @ts-expect-error deliberately not a fill rule
expectThrow('an unknown fill rule is refused', TypeError, () => ctx.fill('winding'))

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

const average = ctx.getAverageColor()
check('getAverageColor answers four channels', ['r', 'g', 'b', 'a'].every((k) => typeof average[k] === 'number'), JSON.stringify(average))
ctx.getAverageColor(0, 0, 10, 10)
pass('getAverageColor takes a region')
expectThrow('a non-finite region is refused', 'invalid-argument', () => ctx.getAverageColor(NaN, 0, 1, 1))

// @ts-expect-error deliberately not an image
expectThrow('drawImage refuses something that is not an image', TypeError, () => ctx.drawImage({}, 0, 0))
for (const args of [[1, 2, 3], [], [1, 2, 3, 4, 5]]) {
  // @ts-expect-error deliberately no overload
  expectThrow(`drawImage refuses ${args.length} coordinates`, TypeError, () => ctx.drawImage(tile, ...args))
}
ctx.drawImage(tile, 0, 0)
ctx.drawImage(tile, 0, 0, 4, 4)
ctx.drawImage(tile, 0, 0, 8, 8, 0, 0, 16, 16)
pass('drawImage takes all three overloads')

// @ts-expect-error deliberately not an encoding this canvas writes
expectThrow('convertToBlob refuses an encoding it does not write', 'invalid-argument', () => canvas.convertToBlob({ type: 'image/gif' }))
// @ts-expect-error deliberately none of the shapes a source may take
expectThrow('decode refuses a source that is none of the three shapes', 'invalid-argument', () => inu.canvas.decode(42))
expectThrow('loadFont needs a family name', 'invalid-argument', () => inu.canvas.loadFont('', new Uint8Array([1])))
expectThrow('naming a file without the fs grant is refused', 'not-granted', () => inu.canvas.load({ path: 'a.png' }))

;(async () => {
  const png = await canvas.convertToBlob()
  check('convertToBlob answers a Blob', png instanceof Blob, `${png.size} bytes, ${png.type}`)

  const image = await inu.canvas.decode(png)
  check('decode answers an image with a size', image.width > 0 && image.height > 0, `${image.width}x${image.height}`)
  ctx.drawImage(image, 0, 0)
  pass('a decoded image draws')

  const held = ctx.createPattern(image, 'repeat')
  image.dispose()
  image.dispose()
  pass('disposing an image twice is a no-op')
  expectThrow('drawing a disposed image is handle-expired', 'handle-expired', () => ctx.drawImage(image, 0, 0))
  ctx.fillStyle = held
  expectThrow('a pattern over a disposed image fails where it is painted', 'handle-expired', () => ctx.fillRect(0, 0, 1, 1))
  ctx.fillStyle = '#000000'

  await expectReject('a decode the host refuses rejects', 'invalid-argument', inu.canvas.decode(new Uint8Array([0])))

  console.log('canvas test done')
})()
