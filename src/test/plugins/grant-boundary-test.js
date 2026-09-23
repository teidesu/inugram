// ==InuPlugin==
// @name         grant boundary test
// @description  asserts every ungranted api rejects with a typed inu.PluginError
// ==/InuPlugin==

async function expectDenied(label, expected, fn) {
  try {
    await fn()
  } catch (e) {
    const matches = e instanceof inu.PluginError && e.name === 'PluginError' && e.code === expected.code
    return check(label, matches && (!('grant' in expected) || e.grant === expected.grant), `${e.name}: ${e.code} ${e.grant} ${e.message}`)
  }
  fail(label, 'did not throw')
}

;(async () => {
  localStorage.setItem('ungranted', 'yes')
  const readBack = localStorage.getItem('ungranted')
  check('localStorage needs no grant', readBack === 'yes', `read back ${readBack}`)

  await expectDenied(
    'interceptRpc',
    { code: 'not-granted', grant: 'interceptRpc(users.getUsers)' },
    () => inu.interceptRpc('users.getUsers', ({ request: req }, next) => next(req)),
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

  // demuxed events name the event, not a constructor of it
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

  let account
  try {
    account = inu.account()
  } catch (e) {
    skip('the account reads', `no account is logged in (${e.code})`)
    console.log('grant boundary test done')
    return
  }
  pass('an account handle needs no grant')

  for (const [label, scope, call] of [
    ['getMe', 'self', () => account.getMe()],
    ['getUser', 'peers', () => account.getUser('me')],
    ['getUsers', 'peers', () => account.getUsers([])],
    ['getChats', 'peers', () => account.getChats([])],
    ['getDialog', 'dialogs', () => account.getDialog('me')],
    ['getMessagesCached', 'messages', () => account.getMessagesCached('me', 1)],
    ['resolvePeerCached', 'peers', () => account.resolvePeerCached('me')],
    ['resolvePeer', 'peers', () => account.resolvePeer('@telegram')],
    // legal arguments, so a denial cannot be a bad-argument rejection
    ['getHistory', 'history', () => account.getHistory('me', { limit: 1 })],
    ['getDialogs', 'dialogs', () => account.getDialogs({ limit: 1 })],
    ['getTopics', 'dialogs', () => account.getTopics('me', { limit: 1 })],
    // the self shortcut is not held either, so the denial names the wider grant
    ['getUserFull', 'peers', () => account.getUserFull('me')],
    ['getChatFull', 'peers', () => account.getChatFull('me')],
    ['getDraft', 'draft', () => account.getDraft('me')],
  ]) {
    await expectDenied(label, { code: 'not-granted', grant: `account.read(${scope})` }, call)
  }

  console.log('grant boundary test done')
})()
