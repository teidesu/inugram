// ==InuPlugin==
// @name         reads test
// @author       teidesu
// @version      1.0
// @description  asserts the Account cache getters and peer resolution answer what common.d.ts says
// @grant        account.read(self)
// @grant        account.read(peers)
// @grant        account.read(dialogs)
// @grant        account.read(messages)
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

function skip(label, why) {
  console.log(`SKIP ${label}: ${why}`)
}

function expectThrows(label, code, fn) {
  try {
    const value = fn()
    fail(label, `did not throw, returned ${JSON.stringify(value)}`)
  } catch (e) {
    if (!(e instanceof inu.PluginError)) fail(label, `not an inu.PluginError (${e})`)
    else if (e.code !== code) fail(label, `code = ${e.code}, want ${code}`)
    else pass(label, e.message)
  }
}

async function expectRejects(label, code, fn) {
  try {
    const value = await fn()
    fail(label, `did not reject, resolved with ${JSON.stringify(value)}`)
  } catch (e) {
    if (!(e instanceof inu.PluginError)) fail(label, `not an inu.PluginError (${e})`)
    else if (e.code !== code) fail(label, `code = ${e.code}, want ${code}`)
    else pass(label, e.message)
  }
}

// a dialog id far past anything telegram has ever issued, so it is a miss on any device and in the
// harness alike
const NOBODY = 4242424242
const NO_CHAT = -4242424242

;(async () => {
  let acc
  try {
    acc = inu.account()
  } catch (e) {
    skip('the whole suite', `no account is logged in (${e.code})`)
    console.log('reads test done')
    return
  }

  // -- what is not a peer never reaches the host --

  for (const bad of [0, '0', null, undefined, {}, [], 1.5, 'not a name!', '@', true, NaN]) {
    const label = typeof bad === 'string' ? `'${bad}'` : String(bad)
    // @ts-expect-error
    expectThrows(`getUser(${label}) is refused`, 'invalid-argument', () => acc.getUser(bad))
  }
  // @ts-expect-error
  expectThrows('getUsers wants an array', 'invalid-argument', () => acc.getUsers('me'))
  // @ts-expect-error
  expectThrows('getChats wants an array', 'invalid-argument', () => acc.getChats(NO_CHAT))
  expectThrows('getMessage wants an integer id', 'invalid-argument', () => acc.getMessage('me', 1.5))
  // @ts-expect-error
  expectThrows('getMessages wants an array', 'invalid-argument', () => acc.getMessages('me', 7))

  // the slot comes off the handle, so a torn-off method is a named mistake rather than a read
  // against slot 0
  const torn = acc.getUser
  expectThrows('a torn-off getter says so', 'invalid-argument', () => torn(NOBODY))

  // -- a miss is null, everywhere --

  check('getUser misses as null', acc.getUser(NOBODY) === null)
  check('getChat misses as null', acc.getChat(NO_CHAT) === null)
  check('getPeer misses as null', acc.getPeer(NOBODY) === null)
  check('getDialog misses as null', acc.getDialog(NO_CHAT) === null)
  check('getMessage misses as null', acc.getMessage(NO_CHAT, 7) === null)
  check('resolvePeerCached misses as null', acc.resolvePeerCached(NOBODY) === null)

  // -- batches keep their misses in place --

  const empty = acc.getUsers([])
  check('getUsers([]) is an empty array', Array.isArray(empty) && empty.length === 0)

  const batch = acc.getUsers(['me', NOBODY])
  check('getUsers keeps its shape', Array.isArray(batch) && batch.length === 2, `${batch.length} entries`)
  check('a batch miss is null in place', batch[1] === null)

  const noChats = acc.getChats([])
  check('getChats([]) is an empty array', Array.isArray(noChats) && noChats.length === 0)

  const chats = acc.getChats([NO_CHAT, 'me'])
  check('getChats keeps its shape', Array.isArray(chats) && chats.length === 2, `${chats.length} entries`)
  check('a chat miss is null in place', chats[0] === null)
  // the batch getters are per kind, so the one peer that certainly exists is still not a chat
  check('getChats does not answer for a user', chats[1] === null)

  const messages = acc.getMessages('me', [7, 999999999])
  check('getMessages keeps its shape', Array.isArray(messages) && messages.length === 2)
  check('a message miss is null in place', messages[1] === null)

  // -- myself --

  const me = acc.getMe()
  if (me === null) {
    skip('getMe', 'the logged-in user is not in the entity cache')
  } else {
    check('getMe is a user', me._ === 'user', me._)
    check('getPeer(me) agrees with getMe', acc.getPeer('me')?._ === me._)
    expectThrows('an account read is read-only', 'forbidden', () => {
      me.first_name = 'mallory'
    })
  }

  const self = acc.resolvePeerCached('me')
  check('resolvePeerCached(me) is inputPeerSelf', self !== null && self._ === 'inputPeerSelf', JSON.stringify(self))
  check('self is the same for both spellings', acc.resolvePeerCached('self')?._ === 'inputPeerSelf')
  check('resolvePeer(me) is inputPeerSelf', (await acc.resolvePeer('me'))._ === 'inputPeerSelf')
  check('resolveUser(me) is inputUserSelf', (await acc.resolveUser('me'))._ === 'inputUserSelf')
  await expectRejects('resolveChannel(me) is refused', 'invalid-argument', () => acc.resolveChannel('me'))

  // -- an input peer in hand is the answer --

  /** @type {tl.RawInputPeerUser} */
  const built = { _: 'inputPeerUser', user_id: '222', access_hash: '22' }
  check('resolvePeerCached passes one through', acc.resolvePeerCached(built) === built)
  check('resolvePeer passes one through', (await acc.resolvePeer(built)) === built)

  // -- an id with nothing cached behind it cannot be resolved --

  await expectRejects('resolvePeer of an unknown id', 'not-found', () => acc.resolvePeer(NOBODY))

  // -- the dialog the account always has: its own saved messages --

  const dialog = acc.getDialog('me')
  if (dialog === null) {
    skip('getDialog', 'saved messages is not in the dialog cache')
  } else {
    check('getDialog is a dialog', String(dialog._).startsWith('dialog'), dialog._)
    const top = dialog.top_message
    if (typeof top !== 'number' || top === 0) {
      skip('getMessage', 'saved messages has no cached top message')
    } else {
      const message = acc.getMessage('me', top)
      if (message === null) {
        skip('getMessage', `message ${top} is not in the message cache`)
      } else {
        check('getMessage wraps in inu.Message', message instanceof inu.Message)
        check('the wrapper reads its own id', message.id === top, `${message.id} vs ${top}`)
        check('the raw message is a message', String(message.raw._).startsWith('message'), message.raw._)
        const both = acc.getMessages('me', [top, 999999999])
        check('getMessages wraps too', both[0] instanceof inu.Message && both[1] === null)
      }
    }
  }

  // -- the chat list the app already holds --

  // shape rather than content: the fake host seeds one dialog and one folder, a device has
  // whatever the account has, and every assertion below has to hold for both
  const cached = await acc.getDialogsCached()
  check('getDialogsCached answers with an array', Array.isArray(cached), `${cached.length} dialogs`)
  // @ts-expect-error - the point: a cached read is a plain array, with no cursor to page from
  check('and never pages', cached.next === undefined)
  check(
    'every element is a dialog',
    cached.every(dialog => typeof dialog?._ === 'string' && dialog._.startsWith('dialog')),
    cached[0]?._,
  )
  check(
    'the archive is not in the main list',
    cached.every(dialog => !dialog.folder_id),
    `${cached.filter(dialog => dialog.folder_id).length} archived`,
  )
  check(
    'keeping the archive answers with at least as many',
    (await acc.getDialogsCached({ archive: 'keep' })).length >= cached.length,
  )
  check('a limit caps the answer', (await acc.getDialogsCached({ limit: 1 })).length <= 1)

  await expectRejects('an unknown archive mode is refused', 'invalid-argument', () =>
    // @ts-expect-error - refused at runtime too, which is what this asserts
    acc.getDialogsCached({ archive: 'both' }))
  await expectRejects('archive and chatFolderId together are refused', 'invalid-argument', () =>
    acc.getDialogsCached({ archive: 'keep', chatFolderId: 0 }))
  await expectRejects('a negative chatFolderId is refused', 'invalid-argument', () =>
    acc.getDialogsCached({ chatFolderId: -1 }))

  const folders = await acc.getChatFoldersCached()
  check('getChatFoldersCached answers with an array', Array.isArray(folders), `${folders.length} folders`)
  const allChats = folders.find(folder => folder.isDefault)
  check('and every account has the default folder', allChats !== undefined && allChats.id === 0, `${allChats?.id}`)
  check(
    'a folder carries a title, a count and its pins',
    folders.every(folder =>
      typeof folder.title?.text === 'string'
      && typeof folder.dialogCount === 'number'
      && Array.isArray(folder.pinned)),
  )
  check(
    'a colour is an index into telegram\'s palette or nothing at all',
    folders.every(folder => folder.colorIndex === null || (folder.colorIndex >= 0 && folder.colorIndex <= 7)),
  )
  check(
    'the default folder is not a shared one',
    allChats === undefined || allChats.isChatlist === false,
  )

  // -- a username is the one thing that can be looked up --

  let telegram
  try {
    telegram = await acc.resolvePeer('@Telegram')
  } catch (e) {
    // only the server saying no is a reason to skip: a PluginError here is this api getting the
    // lookup wrong, which is the whole thing this section is for. an RpcError is the one with a
    // numeric code
    if (typeof e.code === 'number') skip('resolvePeer(@Telegram)', `the server said ${e.code}: ${e.text}`)
    else fail('resolvePeer(@Telegram)', `${e.code}: ${e.message}`)
  }
  if (telegram !== undefined) {
    check('a username resolves to a channel', telegram._ === 'inputPeerChannel', telegram._)
    check(
      'and the cache answers for it afterwards',
      acc.resolvePeerCached('telegram')?._ === 'inputPeerChannel',
    )
    check('the @ and the case are both optional', acc.resolvePeerCached('@TELEGRAM')?._ === 'inputPeerChannel')
    check('resolveChannel narrows it', (await acc.resolveChannel('telegram'))._ === 'inputChannel')
    await expectRejects('resolveUser refuses a channel', 'invalid-argument', () => acc.resolveUser('telegram'))
  }

  console.log('reads test done')
})()
