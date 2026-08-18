// ==InuPlugin==
// @name         nav test
// @author       teidesu
// @version      1.0
// @description  asserts inu.ui.onScreenChanged reports one real navigation each and agrees with getCurrentScreen
// @grant        account.read(dialogs)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// the load-time half runs on its own and its count is checked exactly; the rest reports as you
// navigate, which is the only way a navigation event can be exercised at all. every check in it is
// a function of the one change it was handed (or of the one before it), never of what the app
// happens to have on screen, so walking around the app for four screens finishes it.

let ran = 0

function pass(label, detail) {
  ran++
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  ran++
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function expectThrow(label, fn) {
  try {
    fn()
  } catch (e) {
    return pass(label, `${e.name}: ${e.message}`)
  }
  fail(label, 'did not throw')
}

const SCREEN_TYPES = ['chat', 'profile', 'dialogs', 'settings', 'other']
const ACTIONS = ['push', 'pop', 'replace']

function describe(screen) {
  if (screen === null || screen === undefined) return 'null'
  return 'dialogId' in screen ? `${screen.type}(${screen.dialogId})` : screen.type
}

/** value identity, the same one the host diffs its stack with */
function sameScreen(a, b) {
  if (a === null || a === undefined) return b === null || b === undefined
  if (b === null || b === undefined) return false
  return a.type === b.type && a.dialogId === b.dialogId && a.topicId === b.topicId
}

// -- load-time half: everything decidable without navigating anywhere --

// exact, not a floor: a member that vanished would satisfy every expectThrow below by not being a
// function at all, and only the count tells that apart from a refusal
const EXPECTED = 5
const before = ran

check(
  'inu.ui declares both navigation members',
  typeof inu.ui.getCurrentScreen === 'function' && typeof inu.ui.onScreenChanged === 'function',
)

const throwaway = inu.ui.onScreenChanged(() => fail('a disposed navigation callback fired', 'after dispose()'))
check('registering hands back a disposer', typeof throwaway === 'function')
throwaway()
throwaway()
pass('disposing twice is a no-op')

expectThrow('a handler has to be a function', () => {
  // @ts-expect-error
  inu.ui.onScreenChanged('not a callback')
})

const atLoad = inu.ui.getCurrentScreen()
check(
  'getCurrentScreen answers null or a CurrentScreen',
  atLoad === null || (SCREEN_TYPES.includes(atLoad.type) && typeof atLoad.account.id === 'number'),
  describe(atLoad),
)

if (ran - before !== EXPECTED) {
  console.error(`FAIL oracle: ${ran - before} load-time assertions ran, expected exactly ${EXPECTED}`)
}

// -- navigation half --

const WANTED = 4
let seen = 0
/** the screen the last change reported, which is what the next one's `previous` has to be */
let lastScreen = null

const disposer = inu.ui.onScreenChanged((change) => {
  seen++
  const screen = change.screen
  const stack = change.stack
  const where = `#${seen} ${change.action} ${describe(change.previous)} -> ${describe(screen)}`

  check('the action is one of the three documented verdicts', ACTIONS.includes(change.action), where)

  const now = inu.ui.getCurrentScreen()
  check(
    'getCurrentScreen agrees with the change it fired for',
    sameScreen(now, screen),
    `${describe(now)} vs ${describe(screen)}`,
  )

  check('the stack is a memoized getter', stack === change.stack, where)

  const top = stack.length === 0 ? null : stack[stack.length - 1]
  check('the stack ends on `screen`, and is empty exactly when `screen` is null', sameScreen(top, screen), where)

  check(
    'a chat screen carries a dialogId, account.read(dialogs) being granted',
    screen === null || screen.type !== 'chat' || typeof screen.dialogId === 'number',
    describe(screen),
  )

  check(
    'the screen names the account it belongs to',
    screen === null || (typeof screen.account.id === 'number' && typeof screen.account.isCurrent === 'function'),
    where,
  )

  // the dedup rule from the outside: a rebuild that lands on the same screen, or a removal from
  // the middle of the stack, is not a navigation and never reaches here
  check('every change is a real change: `previous` is not `screen`', !sameScreen(change.previous, screen), where)

  if (seen > 1) {
    check(
      '`previous` is the screen the last change reported',
      sameScreen(change.previous, lastScreen),
      `${describe(change.previous)} vs ${describe(lastScreen)}`,
    )
  }
  if (change.action === 'push') {
    const below = stack.length < 2 ? null : stack[stack.length - 2]
    check('a push left `previous` directly below the new top', sameScreen(below, change.previous), where)
  }

  lastScreen = screen

  if (seen === WANTED) {
    disposer()
    console.log('nav test done')
  }
})

console.log(`nav-test armed; navigate ${WANTED} times (open a chat, a profile, go back) to run it`)
