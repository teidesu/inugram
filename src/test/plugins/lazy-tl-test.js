// ==InuPlugin==
// @name         lazy tl test
// @description  asserts onUpdate payloads are read-only views and invokeRpc results are writable ones
// @grant        onUpdate
// @grant        invokeRpc(help.getConfig)
// ==/InuPlugin==

// a vector view throws on `_`
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

// both halves are async, so "done" prints once both reported
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('lazy tl test done')
}

// any one type may not arrive during a test session
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

  // Object.keys on a read-only view yields writable:false descriptors, which must not trip quickjs's proxy invariants
  const fields = Object.keys(update).filter((k) => k !== '_')
  if (fields.length === 0) return
  sawUpdate = true

  const type = update._
  if (typeof type === 'string' && type.length > 0) pass('onUpdate view reads a field', type)
  else return fail('onUpdate view reads a field', `_ = ${type}`)

  pass('onUpdate view enumerates', `${fields.length} field(s): ${fields.join(', ')}`)

  if (Object.isFrozen(update)) fail('onUpdate view is not frozen', 'Object.isFrozen said true')
  else pass('onUpdate view is not frozen')

  const field = fields[0]
  // a literal rhs: reading the field back would throw by itself and read as a failed refusal
  expectThrow(`onUpdate view refuses '${field}' assignment`, 'forbidden', () => {
    update[field] = null
  })
  expectThrow(`onUpdate view refuses '${field}' delete`, 'forbidden', () => {
    delete update[field]
  })

  const nested = firstNestedObject(update, fields)
  if (nested === undefined) pass('no nested object on this update to check inheritance with')
  else {
    expectThrow(`nested '${nested._}' view inherits read-only`, 'forbidden', () => {
      nested.inu_not_a_field = 1
    })
  }

  // the generated tl typings lack `toJSON`
  const copy = /** @type {any} */ (update).toJSON()
  if (copy === null || typeof copy !== 'object') return fail('toJSON() detaches', `got ${copy}`)
  if (copy._ !== type) return fail('toJSON() detaches', `copy._ = ${copy._}, want ${type}`)
  copy._ = 'mutated'
  if (copy._ !== 'mutated') return fail('toJSON() copy is mutable', `read back ${copy._}`)
  if (update._ !== type) return fail('toJSON() copy is detached', `view._ became ${update._}`)
  pass('toJSON() is a plain mutable copy')

  // storing a read-only view into a writable one would launder it into a writable child
  const label = 'read-only view refused as a field value'
  if (writable === null) pass(`${label} (skipped: no writable view yet)`)
  else {
    const slot = Object.keys(writable).find((key) => {
      const value = writable[key]
      return value !== null && typeof value === 'object'
    })
    if (slot === undefined) pass(`${label} (skipped: no object-typed field on ${writable._})`)
    else expectThrow(`${label} '${slot}'`, 'forbidden', () => {
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
