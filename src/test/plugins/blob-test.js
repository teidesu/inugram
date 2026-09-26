// ==InuPlugin==
// @name         blob test
// @description  asserts Blob/File: round trips, slices as views, dispose, the spill boundary, structuredClone
// ==/InuPlugin==

function sameBytes(a, b) {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false
  return true
}

async function main() {
  const source = new Uint8Array([0, 1, 2, 250, 251, 255])
  const blob = new Blob([source], { type: 'Application/Octet-Stream' })
  check('size counts bytes, not parts', blob.size === 6, blob.size)
  check('type is lowercased', blob.type === 'application/octet-stream', blob.type)
  check('a blob with no type says so', new Blob(['x']).type === '', JSON.stringify(new Blob(['x']).type))

  const read = await blob.bytes()
  check('bytes() returns a Uint8Array of the same bytes', read instanceof Uint8Array && sameBytes(read, source), read.join(','))
  check('arrayBuffer() is the same content', (await blob.arrayBuffer()).byteLength === 6)

  const mixed = new Blob(['a', new Uint8Array([98]), new Uint8Array([99]).buffer, new Blob(['🐕'])])
  check('parts concatenate in order', (await mixed.text()) === 'abc🐕', await mixed.text())
  check('and a multi-byte part is counted in bytes', mixed.size === 7, mixed.size)
  check('text() replaces what is not utf-8', (await new Blob([new Uint8Array([0xff])]).text()) === '\uFFFD')

  const digits = new Blob(['0123456789'])
  check('slice reads the range', (await digits.slice(2, 5).text()) === '234', await digits.slice(2, 5).text())
  check('a negative start counts from the end', (await digits.slice(-3).text()) === '789')
  check('an inverted range is empty', digits.slice(5, 2).size === 0)
  check('slices compose against the original', (await digits.slice(2, 8).slice(1, 3).text()) === '34')
  check('slice does not inherit the type', new Blob(['x'], { type: 'text/plain' }).slice(0, 1).type === '')

  const parent = new Blob(['hello world'], { type: 'text/plain' })
  const kept = parent.slice(0, 5)
  check('a slice reads before the parent goes', (await kept.text()) === 'hello')

  parent.dispose()
  parent.dispose()
  await expectReject('disposing the parent kills reads on its slices', 'handle-expired', kept.bytes())
  check(
    'but a slice keeps answering size and type, which it never asked the content for',
    kept.size === 5 && kept.type === '',
    `${kept.size}/${JSON.stringify(kept.type)}`,
  )
  expectThrow('the disposed handle itself is dead for every member', 'handle-expired', () => parent.size)
  await expectReject('including its reads', 'handle-expired', parent.text())

  const whole = new Blob(['abcdef'])
  const part = whole.slice(0, 2)
  part.dispose()
  check('disposing a slice leaves the parent readable', (await whole.text()) === 'abcdef', await whole.text())
  await expectReject('and kills only itself', 'handle-expired', part.text())

  const file = new File(['payload'], 'photos/holiday.jpg', { type: 'image/jpeg', lastModified: 1700000000000 })
  check('a File is a Blob', file instanceof File && file instanceof Blob)
  check('a File keeps its name', file.name === 'photos:holiday.jpg', file.name)
  check('lastModified survives', file.lastModified === 1700000000000, file.lastModified)
  check('a plain Blob is not a File', !(new Blob(['x']) instanceof File))
  check('slicing a File gives a Blob', !(file.slice(0, 2) instanceof File) && file.slice(0, 2) instanceof Blob)
  check('a File is content like any other', (await file.text()) === 'payload', await file.text())

  check('a blob brands itself', Object.prototype.toString.call(blob) === '[object Blob]', String(blob))
  check('and a File says File', Object.prototype.toString.call(file) === '[object File]', String(file))

  // the app mints media against this prototype, not `globalThis.File`, which a plugin can reassign
  const protoKey = Symbol.for('inu.blob.fileProto')
  const stash = Object.getOwnPropertyDescriptor(Blob.prototype, protoKey)
  check(
    'the real File prototype is stashed out of reach',
    !!stash && stash.value === File.prototype && !stash.writable && !stash.configurable,
    JSON.stringify(stash && { writable: stash.writable, configurable: stash.configurable }),
  )
  let swap = 'no-throw'
  try {
    Object.defineProperty(Blob.prototype, protoKey, { value: {} })
  } catch (e) {
    swap = e.constructor.name
  }
  check('and cannot be redefined', swap === 'TypeError' && Blob.prototype[protoKey] === File.prototype, swap)

  // 3 MB is past the in-memory threshold. where the bytes went is invisible to js, so this checks
  // they survive the boundary; proof the spill happened comes after
  const CHUNK = 3 * 1024 * 1024
  const big = new Uint8Array(CHUNK)
  for (let i = 0; i < CHUNK; i++) big[i] = i % 251
  const spilled = new Blob(['head:', big])
  check('a blob past the memory threshold reports its whole size', spilled.size === CHUNK + 5, spilled.size)
  check('its head reads back', (await spilled.slice(0, 5).text()) === 'head:', await spilled.slice(0, 5).text())

  const tail = await spilled.slice(spilled.size - 4).bytes()
  const wanted = big.slice(CHUNK - 4)
  check('and so does its tail, across the boundary', sameBytes(tail, wanted), `${tail.join(',')} vs ${wanted.join(',')}`)

  const middle = await spilled.slice(1024 * 1024, 1024 * 1024 + 8).bytes()
  const expected = big.slice(1024 * 1024 - 5, 1024 * 1024 + 3)
  check('a slice in the middle is exact', sameBytes(middle, expected), `${middle.join(',')} vs ${expected.join(',')}`)

  const rebuilt = new Blob([spilled.slice(5, 5 + 16)])
  check('building from a spilled blob copies its content', sameBytes(await rebuilt.bytes(), big.slice(0, 16)))

  const huge = new Blob([big, big, big, big, big, big])
  check('a huge blob is still cheap to build', huge.size === CHUNK * 6, huge.size)
  await expectReject('reading more than 16 MB into js is refused', 'quota-exceeded', huge.bytes())
  check('and reading a slice of it still works', (await huge.slice(0, 4).bytes()).length === 4)
  huge.dispose()
  spilled.dispose()

  // a js string can cost two bytes per content byte, so text() stops lower than bytes()
  const wide = new Blob([big, big, big])
  check('a 9 MB blob still reads as bytes', (await wide.bytes()).length === CHUNK * 3)
  await expectReject('but reading it as text is refused', 'quota-exceeded', wide.text())
  wide.dispose()

  const PER_WALL = 8 * CHUNK
  expectThrow('assembling more than 32 MB in one call is refused', 'quota-exceeded', () => {
    const part = new Blob(Array(8).fill(big))
    try {
      return new Blob([part, part])
    } finally {
      part.dispose()
    }
  })

  // the spill is not observable directly: more content live at once than the 64 MB native budget
  // holds is only possible off ram. one call assembles at most 32 MB, so the wall is three
  const wall = []
  try {
    for (let i = 0; i < 3; i++) wall.push(new Blob(Array(8).fill(big)))
  } catch (e) {
    fail('content past the native budget goes somewhere that is not ram', `${e.name}: ${e.code || e.message}`)
  }
  if (wall.length === 3) {
    const total = wall.reduce((sum, one) => sum + one.size, 0)
    check('content past the native budget is live all at once', total === 3 * PER_WALL && total > 64 * 1024 * 1024, total)
    const head = await wall[2].slice(0, 4).bytes()
    const end = await wall[2].slice(PER_WALL - 4).bytes()
    check('and reads back at both ends', sameBytes(head, big.slice(0, 4)) && sameBytes(end, big.slice(CHUNK - 4)))
    for (const one of wall) one.dispose()
  }

  const original = new Blob(['shared content'], { type: 'text/plain' })
  const graph = structuredClone({ a: original, b: original, f: new File(['x'], 'n.txt') })
  check('a blob clones as a blob', graph.a instanceof Blob && graph.a !== original, graph.a.size)
  check('the clone reads the same content', (await graph.a.text()) === 'shared content', await graph.a.text())
  check('reference identity is preserved', graph.a === graph.b)
  check('a File clones as a File', graph.f instanceof File && graph.f.name === 'n.txt', graph.f.name)

  const solo = structuredClone(original)
  graph.a.dispose()
  check('disposing a clone leaves the original', (await original.text()) === 'shared content')
  check('and leaves every other clone', (await solo.text()) === 'shared content')

  original.dispose()
  await expectReject('disposing the original kills the clone', 'handle-expired', solo.text())
  check('though the clone still answers its own metadata', solo.size === 14 && solo.type === 'text/plain', solo.size)

  let impostor = 'no-throw'
  try {
    structuredClone(Object.create(Blob.prototype))
  } catch (e) {
    impostor = e.name
  }
  check('an object pretending to be a Blob is not cloneable', impostor === 'DataCloneError', impostor)
}

main().then(
  () => console.log('blob test done'),
  e => fail('blob test', (e && e.stack) || String(e)),
)
