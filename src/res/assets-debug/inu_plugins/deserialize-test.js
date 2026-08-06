// ==UserScript==
// @name         deserialize test
// @author       teidesu
// @version      1.0
// @description  registers a real interceptDeserialize rule, then asserts the refusal vocabulary around it
// @grant        interceptDeserialize(userFull,user,message,encryptedMessage)
// @plugin-api   1
// @platform     android
// ==/UserScript==

// the rewrite itself is only observable on a device: a rule takes effect on the next object the app
// parses, and nothing a plugin can call makes that happen. what runs everywhere is the boundary -
// which rules are refused, and with which error.
const rules = [{
  type: 'userFull',
  set: { noforwards_my_enabled: false, noforwards_peer_enabled: false },
}]

function expectRefused(label, code, run) {
  let error
  try {
    run()
  } catch (e) {
    error = e
  }
  if (error === undefined) return console.error(`FAIL ${label}: it was accepted`)
  if (!(error instanceof inu.PluginError)) return console.error(`FAIL ${label}: not an inu.PluginError (${error})`)
  if (error.code !== code) return console.error(`FAIL ${label}: code = ${error.code}, want ${code}`)
  console.log(`PASS ${label}`)
}

const dispose = inu.interceptDeserialize(rules)
if (typeof dispose === 'function') console.log('PASS a rule set registers and answers with a disposer')
else console.error(`FAIL a rule set registers and answers with a disposer: got ${typeof dispose}`)

dispose()
dispose()
console.log('PASS disposing twice is a no-op')

// the middleware overload. like the rule form, whether it *fires* is only observable on a device -
// what runs everywhere is the registration and the boundary around it
const disposeMiddleware = inu.interceptDeserialize(['user'], (user) => {
  // the argument is the whole TLObject union, so a middleware narrows before it rewrites
  if (user._ === 'user') user.premium = true
})
if (typeof disposeMiddleware === 'function') console.log('PASS the middleware form registers and answers with a disposer')
else console.error(`FAIL the middleware form registers and answers with a disposer: got ${typeof disposeMiddleware}`)
disposeMiddleware()

expectRefused('refuses a middleware over a type the grant does not name', 'not-granted', () => {
  inu.interceptDeserialize(['chat'], (chat) => chat)
})

expectRefused('refuses a middleware that is not a function', 'invalid-argument', () => {
  // @ts-expect-error
  inu.interceptDeserialize(['user'], 'not a function')
})

expectRefused('refuses a type the grant does not name', 'not-granted', () => {
  inu.interceptDeserialize([{ type: 'chat', set: { noforwards: true } }])
})

expectRefused('refuses anything but an array of rules', 'invalid-argument', () => {
  // @ts-expect-error
  inu.interceptDeserialize({ type: 'user', set: { premium: true } })
})

expectRefused('refuses a rule set with no rules', 'invalid-argument', () => {
  inu.interceptDeserialize([])
})

expectRefused('refuses a rule with no type', 'invalid-argument', () => {
  // @ts-expect-error
  inu.interceptDeserialize([{ set: { premium: true } }])
})

expectRefused('refuses an empty type list', 'invalid-argument', () => {
  inu.interceptDeserialize([{ type: [], set: { premium: true } }])
})

expectRefused('refuses a misspelled rule key', 'invalid-argument', () => {
  // @ts-expect-error `where` is not `when`, and ignoring it would rewrite every user
  inu.interceptDeserialize([{ type: 'user', where: { self: true }, set: { premium: true } }])
})

expectRefused('refuses a rule that rewrites nothing', 'invalid-argument', () => {
  inu.interceptDeserialize([{ type: 'user', set: {} }])
})

expectRefused('refuses a value that is not a constant', 'invalid-argument', () => {
  // @ts-expect-error there is no operator dsl, so an object is never a value
  inu.interceptDeserialize([{ type: 'user', set: { premium: { $not: true } } }])
})

expectRefused('refuses undefined as a constant', 'invalid-argument', () => {
  // @ts-expect-error
  inu.interceptDeserialize([{ type: 'user', set: { premium: undefined } }])
})

expectRefused('refuses a field the constructor does not have', 'invalid-argument', () => {
  inu.interceptDeserialize([{ type: 'user', set: { nope: true } }])
})

// everything below is the host's own refusal, and every one of them is about the same thing: what a
// rule rewrites is written back to the local database, so a bad one outlives the plugin
expectRefused('refuses a field that addresses the object', 'forbidden', () => {
  inu.interceptDeserialize([{ type: 'user', set: { access_hash: '1' } }])
})

expectRefused('refuses the wire\'s own flag word', 'forbidden', () => {
  inu.interceptDeserialize([{ type: 'user', set: { flags: 3 } }])
})

expectRefused('refuses secret-chat traffic', 'forbidden', () => {
  inu.interceptDeserialize([{ type: 'encryptedMessage', set: { date: 1 } }])
})

// matching on a value is reading it, and a rule that fires on a guess would confirm the guess
expectRefused('refuses matching on text the api filter redacts', 'forbidden', () => {
  inu.interceptDeserialize([{ type: 'message', when: { message: 'Login code: 12345' }, set: { pinned: true } }])
})

const tooMany = []
for (let i = 0; i < 33; i++) tooMany.push({ type: 'user', set: { premium: true } })
expectRefused('refuses more rules than a plugin may hold', 'quota-exceeded', () => {
  inu.interceptDeserialize(tooMany)
})

// a rule set registers as a whole or not at all, so the ceiling above left nothing behind
const second = inu.interceptDeserialize(rules)
if (typeof second === 'function') console.log('PASS a refused rule set leaves no rules behind')
else console.error('FAIL a refused rule set leaves no rules behind')
second()

console.log('deserialize test done')
