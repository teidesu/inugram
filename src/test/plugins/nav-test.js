// ==InuPlugin==
// @name         nav test
// @description  asserts inu.ui.onScreenChanged reports one real navigation each and agrees with getCurrentScreen
// @grant        account.read(dialogs)
// ==/InuPlugin==

// the load-time count is exact; the rest reports as you navigate, and each check depends only on
// the change it was handed, so walking four screens finishes it

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

expectThrow('a handler has to be a function', null, () => {
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
  check('getCurrentScreen agrees with the change it fired for', sameScreen(now, screen), `${describe(now)} vs ${describe(screen)}`)

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

  // a rebuild onto the same screen, or a removal mid-stack, is not a navigation
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
