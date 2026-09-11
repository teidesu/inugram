// ==InuPlugin==
// @name         grant boundary test
// @author       teidesu
// @version      1.0
// @description  asserts every ungranted api rejects with a typed inu.PluginError
// @grant        kv
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

async function expectDenied(label, expected, fn) {
  let error
  try {
    await fn()
    console.error(`FAIL ${label}: did not throw`)
    return
  } catch (e) {
    error = e
  }
  if (!(error instanceof inu.PluginError)) {
    console.error(`FAIL ${label}: not an inu.PluginError (${error})`)
    return
  }
  if (error.name !== 'PluginError') {
    console.error(`FAIL ${label}: name = ${error.name}`)
    return
  }
  if (error.code !== expected.code) {
    console.error(`FAIL ${label}: code = ${error.code}, want ${expected.code}`)
    return
  }
  if ('grant' in expected && error.grant !== expected.grant) {
    console.error(`FAIL ${label}: grant = ${error.grant}, want ${expected.grant}`)
    return
  }
  console.log(`PASS ${label}: ${error.message}`)
}

;(async () => {
  inu.kv.set('granted', 'yes')
  const readBack = inu.kv.get('granted')
  if (readBack === 'yes') console.log('PASS kv is granted')
  else console.error(`FAIL kv is granted: read back ${readBack}`)

  await expectDenied(
    'interceptRpc',
    { code: 'not-granted', grant: 'interceptRpc(users.getUsers)' },
    () => inu.interceptRpc('users.getUsers', (req, next) => next(req)),
  )

  await expectDenied(
    'invokeRpc without a type name',
    { code: 'invalid-argument' },
    // @ts-expect-error
    () => inu.invokeRpc({}),
  )

  await expectDenied(
    'invokeRpc',
    { code: 'not-granted', grant: 'invokeRpc(users.getUsers)' },
    () => inu.invokeRpc({ _: 'users.getUsers', id: [] }),
  )

  await expectDenied(
    'onUpdate',
    { code: 'not-granted', grant: 'onUpdate(updateNewMessage)' },
    () => inu.onUpdate('updateNewMessage', () => {}),
  )

  // the demuxed events have their own half of the `onUpdate` scope vocabulary, so each names the
  // event rather than a constructor of it
  await expectDenied(
    'onNewMessage',
    { code: 'not-granted', grant: 'onUpdate(new_message)' },
    () => inu.onNewMessage(() => {}),
  )

  await expectDenied(
    'onMessageEdited',
    { code: 'not-granted', grant: 'onUpdate(edit_message)' },
    () => inu.onMessageEdited(() => {}),
  )

  await expectDenied(
    'onMessageDeleted',
    { code: 'not-granted', grant: 'onUpdate(delete_message)' },
    () => inu.onMessageDeleted(() => {}),
  )

  // holding an Account costs nothing; every read off one names the scope it reads behind
  let account
  try {
    account = inu.account()
  } catch (e) {
    console.log(`SKIP the account reads: no account is logged in (${e.code})`)
    console.log('grant boundary test done')
    return
  }
  console.log('PASS an account handle needs no grant')

  for (const [label, scope, call] of [
    ['getMe', 'self', () => account.getMe()],
    ['userId', 'self', () => account.userId],
    ['getUser', 'peers', () => account.getUser('me')],
    ['getUsers', 'peers', () => account.getUsers([])],
    ['getChats', 'peers', () => account.getChats([])],
    ['getDialog', 'dialogs', () => account.getDialog('me')],
    ['getMessagesCached', 'messages', () => account.getMessagesCached('me', 1)],
    ['resolvePeerCached', 'peers', () => account.resolvePeerCached('me')],
    ['resolvePeer', 'peers', () => account.resolvePeer('@telegram')],
    // the reads that may go to the network are refused before anything is sent, and the argument
    // each one is given here is a legal one - so a denial cannot be a bad-argument rejection wearing
    // the wrong code
    ['getHistory', 'history', () => account.getHistory('me', { limit: 1 })],
    ['getDialogs', 'dialogs', () => account.getDialogs({ limit: 1 })],
    ['getTopics', 'dialogs', () => account.getTopics('me', { limit: 1 })],
    // 'me' rather than an id: the self shortcut is the one this plugin does not hold either, so the
    // grant it names has to be the wider one
    ['getUserFull', 'peers', () => account.getUserFull('me')],
    ['getChatFull', 'peers', () => account.getChatFull('me')],
    ['getDraft', 'draft', () => account.getDraft('me')],
  ]) {
    await expectDenied(label, { code: 'not-granted', grant: `account.read(${scope})` }, call)
  }

  console.log('grant boundary test done')
})()
