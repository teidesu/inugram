// ==InuPlugin==
// @name         async reads test
// @description  asserts getHistory/getDialogs/getTopics/getUserFull/getChatFull/getDraft answer what common.d.ts says
// @grant        account.read(self)
// @grant        account.read(peers)
// @grant        account.read(dialogs)
// @grant        account.read(messages)
// @grant        account.read(history)
// @grant        account.read(draft)
// ==/InuPlugin==

// past any id telegram issued: a miss on a device and in the harness alike
const NOBODY = 4242424242

// the one peer whose history the takeover filter rewrites
const SERVICE_PEER = 777000

;(async () => {
  let acc
  try {
    acc = inu.account()
  } catch (e) {
    skip('the whole suite', `no account is logged in (${e.code})`)
    console.log('async reads test done')
    return
  }

  // @ts-expect-error
  await expectReject('getHistory of a non-peer', 'invalid-argument', () => acc.getHistory(null))
  await expectReject('getHistory of dialog 0 is a miss', 'not-found', () => acc.getHistory(0))
  await expectReject('a negative limit', 'invalid-argument', () => acc.getHistory('me', { limit: -1 }))
  await expectReject('a fractional offsetId', 'invalid-argument', () => acc.getHistory('me', { offsetId: 1.5 }))
  // @ts-expect-error
  await expectReject('getDialogs wants an options object', 'invalid-argument', () => acc.getDialogs('main'))
  // @ts-expect-error
  await expectReject('getUserFull of a non-peer', 'invalid-argument', () => acc.getUserFull(null))

  // @ts-expect-error
  expectThrow('getDraft of a non-peer', 'invalid-argument', () => acc.getDraft(null))
  check('getDraft of dialog 0 is a miss', acc.getDraft(0) === null)

  await expectReject('getTopics of something that is not a forum', 'invalid-argument', () => acc.getTopics('me'))
  await expectReject('getChatFull of yourself', 'invalid-argument', () => acc.getChatFull('me'))
  await expectReject('history of an uncached id', 'not-found', () => acc.getHistory(NOBODY))

  // @ts-expect-error
  await expectReject('an invented cursor', 'invalid-argument', () => acc.getDialogs({ cursor: 'not-a-cursor' }), 'cursor')
  // @ts-expect-error
  await expectReject('a cursor that is not a string', 'invalid-argument', () => acc.getDialogs({ cursor: 42 }))

  const history = await acc.getHistory('me', { limit: 3 })
  check('getHistory is an array', Array.isArray(history), `${history.length} messages`)
  check('every element is an inu.Message', history.every((m) => m instanceof inu.Message))
  if (history.length === 0) {
    skip('a fetched message is read-only', 'saved messages is empty')
  } else {
    expectThrow('a fetched message is read-only', 'forbidden', () => {
      history[0].raw.message = 'mallory'
    }, 'read-only')
  }

  // the takeover filter lives at materialization, so a fetch path inherits it. any run of five
  // or more digits in the service chat is one it rewrites
  try {
    const service = await acc.getHistory(SERVICE_PEER, { limit: 20 })
    const leaked = service.find((m) => /[0-9-]{5,}/.test(m.text))
    check(
      'nothing reads in clear from the service chat',
      leaked === undefined,
      leaked === undefined ? `${service.length} messages` : leaked.text,
    )
  } catch (e) {
    if (e instanceof inu.PluginError && e.code === 'not-found') skip('the service chat', 'no telegram chat here')
    else fail('the service chat', `${e.code}: ${e.message}`)
  }

  const page = await acc.getDialogs({ limit: 2 })
  check('getDialogs is an array', Array.isArray(page), `${page.length} dialogs`)
  check('a page carries its own next', page.next === null || typeof page.next === 'string', JSON.stringify(page.next))

  const withDraft = page.find((d) => d.draft !== null && d.draft !== undefined)
  const rowDraft = withDraft?.draft
  const rowPeer = withDraft?.peer
  if (rowDraft === undefined || rowPeer === undefined || rowDraft.message === null) {
    skip('a dialog row carries the draft getDraft answers with', 'no draft in the first page')
  } else {
    const own = acc.getDraft(rowPeer)
    check(
      'a dialog row carries the draft getDraft answers with',
      own !== null && own.text === rowDraft.message,
      `${JSON.stringify(own)} vs ${rowDraft.message}`,
    )
  }

  if (typeof page.next !== 'string') {
    skip('paging', 'the whole chat list fit in one page')
  } else {
    const second = await acc.getDialogs({ limit: 2, cursor: page.next })
    check('a cursor pages the same list', Array.isArray(second), `${second.length} more`)
    // @ts-expect-error
    await expectReject('a dialogs cursor is refused by getTopics', 'invalid-argument', () => acc.getTopics('me', { cursor: page.next }), 'cursor')
    // @ts-expect-error
    await expectReject('a tampered cursor is refused', 'invalid-argument', () => acc.getDialogs({ cursor: `${page.next}x` }), 'cursor')
  }

  // getTopics needs a forum, which only a device has
  const chats = await acc.getDialogs({ limit: 100 })
  const forum = chats
    .map((dialog) => (dialog._ === 'dialog' ? acc.getChat(dialog.peer) : null))
    .find((chat) => chat !== null && chat._ === 'channel' && chat.forum === true)
  if (forum === undefined || forum === null) {
    skip('getTopics answers with a page', 'no forum among the first 100 dialogs')
  } else {
    const topics = await acc.getTopics(inu.utils.peers.getMarkedPeerId(forum), { limit: 5 })
    check('getTopics answers with a page', Array.isArray(topics), `${topics.length} topics`)
  }

  // @ts-expect-error
  const badOptions = acc.iterDialogs('main')
  check('iterDialogs does not throw at the call', typeof badOptions.next === 'function')
  await expectReject('a bad option rejects on the first step', 'invalid-argument', () => badOptions.next())
  await expectReject('iterTopics of something that is not a forum', 'invalid-argument', () => acc.iterTopics('me').next())

  const walked = []
  for await (const dialog of acc.iterDialogs({ limit: 3, batchSize: 2 })) walked.push(dialog)
  check('iterDialogs stops at its limit', walked.length <= 3, `${walked.length} dialogs`)
  check('and yields what getDialogs yields', walked.every((d) => typeof d._ === 'string'))

  let seen = 0
  for await (const _dialog of acc.iterDialogs({ batchSize: 2 })) {
    seen++
    break
  }
  check('breaking out of an iterator is allowed', seen <= 1, `${seen} taken`)

  const walkedHistory = []
  for await (const message of acc.iterHistory('me', { limit: 5, batchSize: 2 })) walkedHistory.push(message)
  check('iterHistory stops at its limit', walkedHistory.length <= 5, `${walkedHistory.length} messages`)
  check('and every element is an inu.Message', walkedHistory.every((m) => m instanceof inu.Message))

  const many = await acc.resolvePeerMany([NOBODY, 'me'])
  check('resolvePeerMany answers one per peer', many.length === 2, JSON.stringify(many.map((p) => p && p._)))
  check('an unresolvable peer is null in place', many[0] === null, JSON.stringify(many[0]))
  check('and a resolvable one is an InputPeer', many[1] !== null && typeof many[1]._ === 'string')
  const none = await acc.resolvePeerMany([])
  check('an empty batch is an empty list', Array.isArray(none) && none.length === 0)
  // @ts-expect-error
  await expectReject('a non-peer fails the whole batch', 'invalid-argument', () => acc.resolvePeerMany('me'))

  const me = await acc.getUserFull('me')
  check('getUserFull answers with an object or null', me === null || typeof me === 'object', me && me._)

  const draft = await acc.getDraft('me')
  check('getDraft is a TextWithEntities or null', draft === null || typeof draft.text === 'string', JSON.stringify(draft))

  console.log('async reads test done')
})()
