// ==InuPlugin==
// @name         takeover test
// @author       teidesu
// @version      1.0
// @description  asserts account-takeover rpc methods stay refused even under an unscoped grant
// @grant        invokeRpc
// @grant        interceptRpc
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// an unscoped grant is what makes this worth testing: it satisfies every scope check, so the
// takeover list is the only thing left standing between a plugin and the account
async function expectForbidden(method) {
  const label = `refuses ${method}`
  let error
  try {
    await inu.invokeRpc({ _: method })
  } catch (e) {
    error = e
  }
  if (error === undefined) return console.error(`FAIL ${label}: call was accepted`)
  if (!(error instanceof inu.PluginError)) return console.error(`FAIL ${label}: not an inu.PluginError (${error})`)
  if (error.code !== 'forbidden') return console.error(`FAIL ${label}: code = ${error.code}, want forbidden`)
  console.log(`PASS ${label}`)
}

// the invoke half runs on its own; the swap half needs an intercepted call, so "done" is the
// latch rather than the last line of the file
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('takeover test done')
}

;(async () => {
  await expectForbidden('auth.exportLoginToken')
  await expectForbidden('auth.signIn')
  await expectForbidden('account.getAuthorizations')
  await expectForbidden('account.deleteAccount')

  const label = 'refuses intercepting auth.exportLoginToken'
  try {
    inu.interceptRpc('auth.exportLoginToken', (req, next) => next(req))
    console.error(`FAIL ${label}: registration was accepted`)
  } catch (e) {
    if (e instanceof inu.PluginError && e.code === 'forbidden') console.log(`PASS ${label}`)
    else console.error(`FAIL ${label}: ${e}`)
  }
  halfDone()
})()

// next() forwards the call it intercepted, so swapping the method would turn any interceptRpc
// grant into an unscoped send primitive
let checkedSwap = false
inu.interceptRpc('help.getConfig', async (req, next) => {
  if (checkedSwap) return next(req)
  checkedSwap = true

  const label = 'refuses a method swap in next()'
  let error
  try {
    // @ts-expect-error
    await next({ _: 'auth.exportLoginToken', api_id: 0, api_hash: '', except_ids: [] })
  } catch (e) {
    error = e
  }
  if (error === undefined) console.error(`FAIL ${label}: the swapped request was sent`)
  else if (!(error instanceof inu.PluginError)) console.error(`FAIL ${label}: not an inu.PluginError (${error})`)
  else if (error.code !== 'forbidden') console.error(`FAIL ${label}: code = ${error.code}, want forbidden`)
  else console.log(`PASS ${label}`)

  halfDone()
  return next(req)
})
