// ==InuPlugin==
// @name         accounts test a very very very very very very very long name
// @author       teidesu
// @version      1.0
// @icon         tg://addstickers?set=gabapentinoids
// @description  asserts Account handles are pinned, withCurrentAccount follows switches and every dispatch carries one
// @grant        account.read(self)
// @grant        onUpdate(updateUserStatus)
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

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function expectThrows(label, body, code) {
  try {
    body()
  } catch (e) {
    check(label, e instanceof inu.PluginError && e.code === code, e && `${e.code}: ${e.message}`)
    return
  }
  fail(label, `expected ${code}, nothing was thrown`)
}

function describe(account) {
  return `#${account.id} (user ${account.userId}, current=${account.isCurrent()})`
}

const list = inu.accounts()
check('accounts() lists the logged-in slots', Array.isArray(list) && list.length > 0, `${list.length} slot(s)`)
check('accounts() hands out account handles', list.every(a => typeof a.invokeRpc === 'function'))
check(
  'exactly one slot is current',
  list.filter(a => a.isCurrent()).length === 1,
  list.map(a => `${a.id}:${a.isCurrent()}`).join(','),
)
check(
  'every slot answers whether it is premium',
  list.every(a => typeof a.isPremium() === 'boolean'),
  list.map(a => `${a.id}:${a.isPremium()}`).join(','),
)

const current = inu.account()
check('account() defaults to the selected slot', current.isCurrent(), describe(current))
check(
  'account(id) agrees with accounts()',
  list.every(info => inu.account(info.id).userId === info.userId),
)

let missing
try {
  const occupied = new Set(list.map(account => account.id))
  inu.account(Array.from({ length: 64 }, (_, id) => id).find(id => !occupied.has(id)))
} catch (e) {
  missing = e
}
check(
  'account() of an empty slot throws not-found',
  missing instanceof inu.PluginError && missing.code === 'not-found',
  missing,
)

// the whole point of pinning: this handle keeps denoting the slot it was minted for, and only
// isCurrent() moves. switch accounts in the app and watch the two lines below.
const pinnedSlot = current.id
const pinnedUser = current.userId
let sawChange = false
inu.onAccountsChanged((accounts) => {
  if (sawChange) return
  sawChange = true
  check(
    'onAccountsChanged hands over the new list',
    Array.isArray(accounts) && accounts.filter(a => a.isCurrent()).length === 1,
    accounts.map(a => `${a.id}:${a.isCurrent()}`).join(','),
  )
  check(
    'the pinned handle keeps its slot across the change',
    current.id === pinnedSlot && current.userId === pinnedUser,
    describe(current),
  )
  check('and only isCurrent() moved', current.isCurrent() === (inu.account().id === pinnedSlot))
})

// the account form of invokeRpc sends on the handle it was called through, and one prototype
// serves every slot, so a torn-off method has to fail by name rather than send on slot 0
const pending = inu.account().invokeRpc({ _: 'help.getConfig' })
check('invokeRpc through an account answers a promise', pending instanceof Promise)
pending.catch(() => {})
const detached = inu.account().invokeRpc
expectThrows('a torn-off invokeRpc names no account', () => detached({ _: 'help.getConfig' }), 'invalid-argument')

let setups = 0
const torn = []
inu.withCurrentAccount((account) => {
  setups++
  if (setups === 1) check('withCurrentAccount runs for the selected account', account.isCurrent(), describe(account))
  return () => torn.push(account.id)
})

let sawUpdate = false
inu.onUpdate('updateUserStatus', (update, account) => {
  if (sawUpdate) return
  sawUpdate = true
  check(
    'onUpdate hands over the account it arrived on',
    typeof account === 'object' && typeof account.isCurrent === 'function',
    describe(account),
  )
  check('the update is the type that was named', update._ === 'updateUserStatus', update._)
  check(
    'a switch re-ran withCurrentAccount, tearing the old one down first',
    setups === 1 || (setups === 2 && torn.join(',') === String(pinnedSlot)),
    `${setups} setup(s), torn ${torn.join(',')}`,
  )
  console.log('accounts test done')
})

console.log('accounts-test armed; switch accounts to exercise the reactive half')
