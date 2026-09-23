// ==InuPlugin==
// @name         takeover test
// @description  asserts account-takeover rpc methods stay refused even under an unscoped grant
// @grant        invokeRpc
// @grant        interceptRpc
// ==/InuPlugin==

// the swap half needs an intercepted call, so "done" is the latch
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('takeover test done')
}

// an unscoped grant satisfies every scope check, so the takeover list is all that is left
;(async () => {
  for (const method of ['auth.exportLoginToken', 'auth.signIn', 'account.getAuthorizations', 'account.deleteAccount']) {
    // @ts-expect-error
    await expectReject(`refuses ${method}`, 'forbidden', () => inu.invokeRpc({ _: method }))
  }
  expectThrow('refuses intercepting auth.exportLoginToken', 'forbidden', () =>
    inu.interceptRpc('auth.exportLoginToken', ({ request: req }, next) => next(req)),
  )
  halfDone()
})()

// next() forwards the intercepted call, so a swap would make any interceptRpc grant an unscoped send
let checkedSwap = false
inu.interceptRpc('help.getConfig', async ({ request: req }, next) => {
  if (checkedSwap) return next(req)
  checkedSwap = true

  // @ts-expect-error
  await expectReject('refuses a method swap in next()', 'forbidden', () => next({ _: 'auth.exportLoginToken', api_id: 0, api_hash: '', except_ids: [] }))

  halfDone()
  return next(req)
})
