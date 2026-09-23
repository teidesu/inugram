// ==InuPlugin==
// @name         reads test
// @description  asserts the Account cache getters and peer resolution answer what common.d.ts says
// @grant        account.read(self)
// @grant        account.read(peers)
// @grant        account.read(dialogs)
// @grant        account.read(messages)
// ==/InuPlugin==

// past any id telegram issued: a miss on a device and in the harness alike
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

  for (const bad of [null, undefined, {}, [], 1.5, 'not a name!', '@', true, NaN]) {
    const label = typeof bad === 'string' ? `'${bad}'` : String(bad)
    // @ts-expect-error
    expectThrow(`getUser(${label}) is refused`, 'invalid-argument', () => acc.getUser(bad))
  }
  // @ts-expect-error
  expectThrow('getUsers wants an array', 'invalid-argument', () => acc.getUsers('me'))
  // @ts-expect-error
  expectThrow('getChats wants an array', 'invalid-argument', () => acc.getChats(NO_CHAT))
  expectThrow('getMessagesCached wants an integer id', 'invalid-argument', () => acc.getMessagesCached('me', 1.5))
  // @ts-expect-error
  expectThrow('getMessagesCached refuses a peer that names nothing', 'invalid-argument', () => acc.getMessagesCached(null, 7))
  await expectReject('getMessages wants an integer id', 'invalid-argument', () => acc.getMessages('me', 1.5))

  const torn = acc.getUser
  expectThrow('a torn-off getter says so', 'invalid-argument', () => torn(NOBODY))

  check('getUser misses as null', acc.getUser(NOBODY) === null)
  check('getUser(0) is a miss rather than a refusal', acc.getUser(0) === null)
  check('getChat misses as null', acc.getChat(NO_CHAT) === null)
  check('getPeer misses as null', acc.getPeer(NOBODY) === null)
  check('getDialog misses as null', acc.getDialog(NO_CHAT) === null)
  check('getMessagesCached misses as null', acc.getMessagesCached(NO_CHAT, 7) === null)
  check('resolvePeerCached misses as null', acc.resolvePeerCached(NOBODY) === null)

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
  check('getChats does not answer for a user', chats[1] === null)

  const messages = acc.getMessagesCached('me', [7, 999999999])
  check('getMessagesCached keeps its shape', Array.isArray(messages) && messages.length === 2)
  check('a message miss is null in place', messages[1] === null)

  const fetchedOne = await acc.getMessages('me', 999999999)
  check('getMessages(id) answers one value', fetchedOne === null || fetchedOne instanceof inu.Message)
  const fetchedMany = await acc.getMessages('me', [999999999])
  check('getMessages([id]) answers an array', Array.isArray(fetchedMany) && fetchedMany.length === 1)

  const me = acc.getMe()
  if (me === null) {
    skip('getMe', 'the logged-in user is not in the entity cache')
  } else {
    check('getMe is a user', me._ === 'user', me._)
    check('getPeer(me) agrees with getMe', acc.getPeer('me')?._ === me._)
    expectThrow('an account read is read-only', 'forbidden', () => {
      me.first_name = 'mallory'
    })
  }

  const self = acc.resolvePeerCached('me')
  check('resolvePeerCached(me) is inputPeerSelf', self !== null && self._ === 'inputPeerSelf', JSON.stringify(self))
  check('self is the same for both spellings', acc.resolvePeerCached('self')?._ === 'inputPeerSelf')
  check('resolvePeer(me) is inputPeerSelf', (await acc.resolvePeer('me'))._ === 'inputPeerSelf')
  check('resolveUser(me) is inputUserSelf', (await acc.resolveUser('me'))._ === 'inputUserSelf')
  await expectReject('resolveChannel(me) is refused', 'invalid-argument', () => acc.resolveChannel('me'))

  /** @type {tl.RawInputPeerUser} */
  const built = { _: 'inputPeerUser', user_id: 222, access_hash: '22' }
  check('resolvePeerCached passes one through', acc.resolvePeerCached(built) === built)
  check('resolvePeer passes one through', (await acc.resolvePeer(built)) === built)

  await expectReject('resolvePeer of an unknown id', 'not-found', () => acc.resolvePeer(NOBODY))

  const dialog = acc.getDialog('me')
  if (dialog === null) {
    skip('getDialog', 'saved messages is not in the dialog cache')
  } else {
    check('getDialog is a dialog', String(dialog._).startsWith('dialog'), dialog._)
    const top = dialog.top_message
    if (typeof top !== 'number' || top === 0) {
      skip('getMessagesCached', 'saved messages has no cached top message')
    } else {
      const message = acc.getMessagesCached('me', top)
      if (message === null) {
        skip('getMessagesCached', `message ${top} is not in the message cache`)
      } else {
        check('getMessagesCached wraps in inu.Message', message instanceof inu.Message)
        check('the wrapper reads its own id', message.id === top, `${message.id} vs ${top}`)
        check('the raw message is a message', String(message.raw._).startsWith('message'), message.raw._)
        const both = acc.getMessagesCached('me', [top, 999999999])
        check('getMessagesCached wraps too', both[0] instanceof inu.Message && both[1] === null)
      }
      // saved messages is a user dialog, so its ids are common-box ids
      const viaBox = await acc.getMessages(0, top)
      check('the common box answers for a user dialog', viaBox === null || viaBox.id === top)
    }
  }

  // shape only: must hold for the fake host's seed and a real account alike
  const cached = await acc.getDialogsCached()
  check('getDialogsCached answers with an array', Array.isArray(cached), `${cached.length} dialogs`)
  // @ts-expect-error - the point: a cached read is a plain array, with no cursor to page from
  check('and never pages', cached.next === undefined)
  check('every element is a dialog', cached.every(dialog => typeof dialog?._ === 'string' && dialog._.startsWith('dialog')), cached[0]?._)
  check(
    'the archive is not in the main list',
    cached.every(dialog => !dialog.folder_id),
    `${cached.filter(dialog => dialog.folder_id).length} archived`,
  )
  check('keeping the archive answers with at least as many', (await acc.getDialogsCached({ archive: 'keep' })).length >= cached.length)
  check('a limit caps the answer', (await acc.getDialogsCached({ limit: 1 })).length <= 1)

  const named = await acc.getDialogsCached({ archive: 'keep', fields: ['top_message', 'unread_count', 'peer'] })
  const plain = await acc.getDialogsCached({ archive: 'keep' })
  check('naming fields answers the same dialogs', named.length === plain.length, `${named.length} vs ${plain.length}`)
  check(
    'and every one of them reads the same, named or not',
    named.every((dialog, at) =>
      dialog.top_message === plain[at].top_message
      && dialog.unread_count === plain[at].unread_count
      && dialog.folder_id === plain[at].folder_id
      && String(dialog.peer?._) === String(plain[at].peer?._)),
  )
  const unknown = await acc.getDialogsCached({ archive: 'keep', fields: ['not_a_field'] })
  check(
    'a name no dialog has is carried by nobody and breaks nothing',
    unknown.length === plain.length && unknown.every((dialog, at) => dialog.top_message === plain[at].top_message),
  )

  await expectReject('a field name that is not a string is refused', 'invalid-argument', () =>
    // @ts-expect-error - refused at runtime too, which is what this asserts
    acc.getDialogsCached({ fields: [7] }))
  await expectReject('and one that could smuggle a separator is refused', 'invalid-argument', () =>
    acc.getDialogsCached({ fields: ['top_message,peer'] }))
  await expectReject('fields must be an array', 'invalid-argument', () =>
    // @ts-expect-error - refused at runtime too, which is what this asserts
    acc.getDialogsCached({ fields: 'top_message' }))

  await expectReject('an unknown archive mode is refused', 'invalid-argument', () =>
    // @ts-expect-error - refused at runtime too, which is what this asserts
    acc.getDialogsCached({ archive: 'both' }))
  await expectReject('archive and chatFolderId together are refused', 'invalid-argument', () =>
    acc.getDialogsCached({ archive: 'keep', chatFolderId: 0 }))
  await expectReject('a negative chatFolderId is refused', 'invalid-argument', () =>
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
  check('the default folder is not a shared one', allChats === undefined || allChats.isChatlist === false)

  let telegram
  try {
    telegram = await acc.resolvePeer('@Telegram')
  } catch (e) {
    // only an RpcError (numeric code) is the server saying no
    if (typeof e.code === 'number') skip('resolvePeer(@Telegram)', `the server said ${e.code}: ${e.text}`)
    else fail('resolvePeer(@Telegram)', `${e.code}: ${e.message}`)
  }
  if (telegram !== undefined) {
    check('a username resolves to a channel', telegram._ === 'inputPeerChannel', telegram._)
    check('and the cache answers for it afterwards', acc.resolvePeerCached('telegram')?._ === 'inputPeerChannel')
    check('the @ and the case are both optional', acc.resolvePeerCached('@TELEGRAM')?._ === 'inputPeerChannel')
    check('resolveChannel narrows it', (await acc.resolveChannel('telegram'))._ === 'inputChannel')
    await expectReject('resolveUser refuses a channel', 'invalid-argument', () => acc.resolveUser('telegram'))
  }

  console.log('reads test done')
})()
