// ==UserScript==
// @name         actions test
// @author       teidesu
// @version      1.0
// @description  registers one row of every action kind and asserts the context each one is handed
// @grant        account.read(draft)
// @plugin-api   1
// @platform     android
// ==/UserScript==
/* eslint-disable eslint-comments/no-unlimited-disable */
/* eslint-disable */

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

function expectTypeError(label, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  check(label, error instanceof TypeError, `${error.name}: ${error.message}`)
}

function expectThrow(label, code, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  check(label, error instanceof inu.PluginError && error.code === code, `${error.name}: ${error.code}`)
}

expectTypeError('a row without an id is refused', () => {
  // @ts-expect-error
  inu.registerChatAction({ text: 'no id', callback: () => {} })
})

expectTypeError('a row without text is refused', () => {
  // @ts-expect-error
  inu.registerChatAction({ id: 'no-text', callback: () => {} })
})

expectTypeError('a row without a callback is refused', () => {
  // @ts-expect-error
  inu.registerChatAction({ id: 'no-callback', text: 'no callback' })
})

expectTypeError('an icon not minted by inu.icons is refused', () => {
  // @ts-expect-error
  inu.registerChatAction({ id: 'iconed', text: 'iconed', icon: 'settings', callback: () => {} })
})

const throwaway = inu.registerChatAction({ id: 'throwaway', text: 'throwaway', callback: () => {} })
check('register returns a disposer', typeof throwaway === 'function')
throwaway()
throwaway()
pass('disposing twice is a no-op')

inu.registerChatAction({
  id: 'chat',
  text: (ctx) => {
    if (ctx === null) return 'Chat row'
    check('a chat action names its dialog', ctx.dialogId === -100, String(ctx.dialogId))
    return 'Chat row'
  },
  visible: (ctx) => {
    check('a chat action names its topic', ctx.topicId === 7, String(ctx.topicId))
    return true
  },
  callback: () => {},
})

inu.registerChatAction({
  id: 'hidden',
  text: 'never drawn',
  visible: () => false,
  callback: () => {},
})

// a throwing predicate drops its own row and nothing else - the plugin stays on, which is what
// makes the rest of this file keep working. logs one error per chat menu, deliberately
inu.registerChatAction({
  id: 'thrower',
  text: 'never drawn either',
  visible: () => {
    throw new Error('visible blew up')
  },
  callback: () => {},
})

inu.registerMessageAction({
  id: 'message',
  text: 'Message row',
  placements: ['bubble', 'selection'],
  callback: (ctx) => {
    if (ctx.source === 'bubble') {
      check(
        'a bubble action names every message and its album',
        ctx.messages.length === 2 &&
          ctx.messages[0] instanceof inu.Message && ctx.messages[0].id === 11 && ctx.messages[0].groupedId === '77' &&
          ctx.messages[1] instanceof inu.Message && ctx.messages[1].id === 12 && ctx.messages[1].groupedId === '77',
        JSON.stringify(ctx.messages),
      )
      check('a topic the bubble surface has none of is absent', ctx.topicId === undefined, String(ctx.topicId))
    } else {
      check(
        'a selection action keeps its source and each message dialog',
        ctx.messages.length === 2 &&
          ctx.messages[0] instanceof inu.Message && ctx.messages[0].dialogId === -200 && ctx.messages[0].id === 3 &&
          ctx.messages[1] instanceof inu.Message && ctx.messages[1].dialogId === -100 && ctx.messages[1].id === 14,
        `${ctx.source}: ${JSON.stringify(ctx.messages)}`,
      )
      check('a selection action names its topic', ctx.topicId === 7, String(ctx.topicId))
    }
  },
})

inu.registerProfileAction({
  id: 'profile',
  text: 'Profile row',
  callback: (ctx) => {
    check('a profile action names its dialog', ctx.dialogId === -100, String(ctx.dialogId))
  },
})

inu.registerAction({
  id: 'global',
  text: 'Global row',
  callback: (ctx) => {
    // @ts-expect-error a global action's ctx declares no dialog, which is what this asserts
    check('a global action has no dialog at all', ctx.dialogId === undefined, String(ctx.dialogId))
  },
})

inu.registerMessageEditorAction({
  id: 'editor',
  text: 'Editor row',
  callback: (ctx) => {
    check('an editor action is handed the draft', ctx.draft.text === 'hello', ctx.draft.text)
    ctx.replace('replaced')
    pass('replace crosses to the composer')
    ctx.send({ text: 'sent', entities: [] })
    pass('send crosses to the composer')
  },
})

// the row cap. counted at registration and never at draw, so the filler hides itself and the send
// menu on a device stays readable. the editor row above is already the first of the eight
const CAP = 8
for (let i = 1; i < CAP; i++) {
  inu.registerMessageEditorAction({
    id: 'filler-' + i,
    text: 'filler ' + i,
    visible: () => false,
    callback: () => {},
  })
}
expectThrow('a ninth row of one menu is refused', 'quota-exceeded', () => {
  inu.registerMessageEditorAction({ id: 'one-too-many', text: 'nope', callback: () => {} })
})

// re-registering an id is how a row is updated, so it has to be a replacement rather than a ninth
let replaced = 'no-throw'
try {
  inu.registerMessageEditorAction({
    id: 'filler-1',
    text: 'filler 1 (updated)',
    visible: () => false,
    callback: () => {},
  })
} catch (e) {
  replaced = `${e.name}: ${e.message}`
}
check('a row can still be updated once the menu is full', replaced === 'no-throw', replaced)

console.log('actions test done')
