// ==InuPlugin==
// @name         lazy tl test
// @author       teidesu
// @version      1.0
// @description  asserts onUpdate payloads are read-only views and invokeRpc results are writable ones
// @grant        onUpdate
// @grant        invokeRpc(help.getConfig)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

function pass(label, detail) {
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  console.error(`FAIL ${label}: ${detail}`)
}

function expectForbidden(label, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  if (!(error instanceof inu.PluginError)) return fail(label, `not an inu.PluginError (${error})`)
  if (error.code !== 'forbidden') return fail(label, `code = ${error.code}, want forbidden`)
  pass(label, error.message)
}

// a vector view throws on `_`, so probing has to be guarded rather than shape-tested
function firstNestedObject(view, keys) {
  for (const key of keys) {
    const value = view[key]
    if (value === null || typeof value !== 'object') continue
    try {
      if (typeof value._ === 'string') return value
    } catch (e) {
      continue
    }
  }
  return undefined
}

let sawUpdate = false
let writable = null

// both halves are asynchronous and neither one's report is the whole file's, so "done" is what
// says every assertion ran rather than the last line of the source
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('lazy tl test done')
}

// a spread of field-carrying types, since the assertions below need an update with fields and any
// one type may not arrive during a test session
inu.onUpdate([
  'updateNewMessage',
  'updateNewChannelMessage',
  'updateEditMessage',
  'updateDeleteMessages',
  'updateReadHistoryInbox',
  'updateUserTyping',
  'updateUserStatus',
], (update) => {
  if (sawUpdate) return

  // Object.keys goes through ownKeys + a descriptor per key: on a read-only view those come back
  // writable:false, which must not trip quickjs's proxy invariants
  const fields = Object.keys(update).filter((k) => k !== '_')
  // a fieldless update (updateContactsReset and friends) can't exercise the field assertions below,
  // so let it through and wait for one that can rather than latching on it
  if (fields.length === 0) return
  sawUpdate = true

  const type = update._
  if (typeof type === 'string' && type.length > 0) pass('onUpdate view reads a field', type)
  else return fail('onUpdate view reads a field', `_ = ${type}`)

  pass('onUpdate view enumerates', `${fields.length} field(s): ${fields.join(', ')}`)

  if (Object.isFrozen(update)) fail('onUpdate view is not frozen', 'Object.isFrozen said true')
  else pass('onUpdate view is not frozen')

  const field = fields[0]
  // a literal rhs on purpose: reading the field back would throw on its own if it went missing,
  // and that error would read as a failed refusal
  expectForbidden(`onUpdate view refuses '${field}' assignment`, () => {
    update[field] = null
  })
  expectForbidden(`onUpdate view refuses '${field}' delete`, () => {
    delete update[field]
  })

  const nested = firstNestedObject(update, fields)
  if (nested === undefined) pass('no nested object on this update to check inheritance with')
  else {
    expectForbidden(`nested '${nested._}' view inherits read-only`, () => {
      nested.inu_not_a_field = 1
    })
  }

  // `toJSON` is on every view the bridge hands over, but the generated tl typings don't carry it
  const copy = /** @type {any} */ (update).toJSON()
  if (copy === null || typeof copy !== 'object') return fail('toJSON() detaches', `got ${copy}`)
  if (copy._ !== type) return fail('toJSON() detaches', `copy._ = ${copy._}, want ${type}`)
  copy._ = 'mutated'
  if (copy._ !== 'mutated') return fail('toJSON() copy is mutable', `read back ${copy._}`)
  if (update._ !== type) return fail('toJSON() copy is detached', `view._ became ${update._}`)
  pass('toJSON() is a plain mutable copy')

  // storing a read-only view into a writable one would launder it: re-reading that field mints a
  // writable child of an object the app owns
  const label = 'read-only view refused as a field value'
  if (writable === null) pass(`${label} (skipped: no writable view yet)`)
  else {
    const slot = Object.keys(writable).find((key) => {
      const value = writable[key]
      return value !== null && typeof value === 'object'
    })
    if (slot === undefined) pass(`${label} (skipped: no object-typed field on ${writable._})`)
    else expectForbidden(`${label} '${slot}'`, () => {
      writable[slot] = update
    })
  }
  halfDone()
})

inu.invokeRpc({ _: 'help.getConfig' }).then(
  (config) => {
    if (config === null || config._ !== 'config') return fail('invokeRpc result is a view', `_ = ${config?._}`)
    pass('invokeRpc result is a view', `this_dc = ${config.this_dc}`)
    writable = config

    const before = config.this_dc
    try {
      config.this_dc = before + 1
    } catch (e) {
      return fail('invokeRpc result is writable', e)
    }
    if (config.this_dc === before + 1) pass('invokeRpc result is writable')
    else fail('invokeRpc result is writable', `read back ${config.this_dc}, want ${before + 1}`)
    halfDone()
  },
  (e) => fail('invokeRpc help.getConfig', e),
)

console.log('lazy-tl-test armed; the onUpdate half reports once an update arrives')
