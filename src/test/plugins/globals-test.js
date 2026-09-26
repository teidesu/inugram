// ==InuPlugin==
// @name         globals test
// @description  asserts the sandbox globals: TextEncoder/TextDecoder, crypto, AbortController, structuredClone
// @grant        invokeRpc(help.getConfig)
// ==/InuPlugin==

const encoder = new TextEncoder()
const decoder = new TextDecoder()
// a 2-byte codepoint, a surrogate pair and a NUL: what a naive encoder gets wrong
const sample = 'привет · café · 🐕 · \u0000 end'
const bytes = encoder.encode(sample)

check('encode() returns a Uint8Array', bytes instanceof Uint8Array, `${bytes.length} byte(s)`)
check('utf-8 round-trips non-ascii', decoder.decode(bytes) === sample, JSON.stringify(decoder.decode(bytes)))
check('a 2-byte codepoint encodes to 2 bytes', encoder.encode('é').length === 2, encoder.encode('é').length)
check('an astral codepoint encodes to 4 bytes, not 6', encoder.encode('🐕').length === 4, encoder.encode('🐕').length)
check('both report utf-8', encoder.encoding === 'utf-8' && decoder.encoding === 'utf-8')
check('decode() takes an ArrayBuffer too', decoder.decode(bytes.buffer) === sample)
check('the empty cases are empty', encoder.encode().length === 0 && decoder.decode() === '')
// @ts-expect-error
expectThrow('TextDecoder refuses a non-utf-8 label', RangeError, () => new TextDecoder('utf-16'))

// binary strings, unlike inu.utils.toBase64, so a byte past 0x7f has to survive
const binary = 'inu\x00\xff'
check('btoa encodes a binary string', btoa(binary) === 'aW51AP8=', btoa(binary))
check('atob undoes it', atob(btoa(binary)) === binary, JSON.stringify(atob(btoa(binary))))

const buffer = new Uint8Array(32)
check('getRandomValues returns the array it was handed', crypto.getRandomValues(buffer) === buffer)
check('getRandomValues actually fills it', buffer.some(byte => byte !== 0), buffer.slice(0, 4).join(','))
check('two draws differ', crypto.getRandomValues(new Uint8Array(32)).join(',') !== buffer.join(','))
expectDomException('getRandomValues refuses more than 65536 bytes', 'QuotaExceededError', () => {
  crypto.getRandomValues(new Uint8Array(65537))
})
// @ts-expect-error
expectThrow('getRandomValues refuses a plain array', TypeError, () => crypto.getRandomValues([1, 2, 3]))

const uuid = crypto.randomUUID()
check('randomUUID is a v4 uuid', /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(uuid), uuid)
check('randomUUIDs differ', crypto.randomUUID() !== uuid)

const controller = new AbortController()
let aborts = 0
const listener = () => aborts++

check('a fresh signal is not aborted', controller.signal.aborted === false && controller.signal.reason === undefined)
controller.signal.addEventListener('abort', listener)
controller.signal.addEventListener('abort', listener)
// @ts-expect-error
controller.signal.addEventListener('ignored', () => aborts++)

controller.abort()
controller.abort('a second time')

check('the listener fired exactly once for two aborts and two registrations', aborts === 1, aborts)
check('aborted latches', controller.signal.aborted === true)
check(
  'the default reason is an AbortError',
  controller.signal.reason instanceof DOMException && controller.signal.reason.name === 'AbortError',
  controller.signal.reason,
)

let late = 0
controller.signal.addEventListener('abort', () => late++)
check('a listener added after the abort never fires', late === 0, late)

const removed = new AbortController()
let removedCalls = 0
const doomed = () => removedCalls++
removed.signal.addEventListener('abort', doomed)
removed.signal.removeEventListener('abort', doomed)
removed.abort('explicit reason')
check('removeEventListener takes a listener off', removedCalls === 0, removedCalls)
check('an explicit reason is kept as-is', removed.signal.reason === 'explicit reason', removed.signal.reason)

const source = {
  n: 1,
  s: 'x',
  nested: { list: [1, 2, 3] },
  when: new Date(1_700_000_000_000),
  map: new Map([['k', { deep: true }]]),
  set: new Set([1, 2]),
  bytes: new Uint8Array([1, 2, 3]),
}
source.self = source

const clone = structuredClone(source)
check('structuredClone copies plain data', clone.n === 1 && clone.nested.list.join() === '1,2,3')
check('the copy is detached', clone.nested !== source.nested && clone !== source)
check('cycles are preserved', clone.self === clone)
check(
  'Date/Map/Set/typed arrays clone as themselves',
  clone.when instanceof Date && clone.when.getTime() === source.when.getTime()
  && clone.map.get('k')?.deep === true && clone.map.get('k') !== source.map.get('k')
  && clone.set.has(2) && clone.bytes instanceof Uint8Array && clone.bytes[2] === 3,
)
const shared = { id: 1 }
const twice = structuredClone({ a: shared, b: shared })
check('reference identity is preserved', twice.a === twice.b && twice.a !== shared)

clone.nested.list.push(4)
check('mutating the copy leaves the original alone', source.nested.list.length === 3, source.nested.list.length)

expectDomException('structuredClone refuses a function', 'DataCloneError', () => structuredClone(() => {}))
expectDomException('structuredClone refuses a promise', 'DataCloneError', () => structuredClone(Promise.resolve(1)))
expectDomException('structuredClone refuses a symbol', 'DataCloneError', () => structuredClone(Symbol('x')))
expectDomException('structuredClone refuses a weak collection', 'DataCloneError', () => structuredClone(new WeakMap()))

inu.invokeRpc({ _: 'help.getConfig' }).then(
  (config) => {
    if (config === null || config._ !== 'config') return fail('invokeRpc returned a view', `_ = ${config?._}`)
    pass('invokeRpc returned a view', `this_dc = ${config.this_dc}`)

    const copy = structuredClone(config)
    copy.this_dc = -1
    check(
      'structuredClone detaches a TL view into a mutable copy',
      copy._ === 'config' && copy !== config && config.this_dc !== -1,
      `${copy._} ${config.this_dc}`,
    )
    const nested = structuredClone({ inner: [config] })
    check('structuredClone copies a view held inside plain data', nested.inner[0]._ === 'config', nested.inner[0]._)
    const pair = structuredClone([config, config])
    check('a view named twice clones to one object', pair[0] === pair[1] && pair[0] !== config)

    console.log('globals test done')
  },
  e => fail('invokeRpc help.getConfig', e),
)
