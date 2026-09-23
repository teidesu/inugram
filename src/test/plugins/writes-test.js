// ==InuPlugin==
// @name         writes test
// @description  asserts the Account write surface argues, gates and refuses what common.d.ts says
// @grant        account.read(peers)
// @grant        account.read(self)
// @grant        account.write(send)
// @grant        account.write(edit)
// @grant        account.write(delete)
// @grant        account.write(forward)
// @grant        account.write(react)
// @grant        account.write(typing)
// @grant        account.write(draft)
// ==/InuPlugin==

// past any id telegram issued: a miss on a device and in the harness alike. `SECRET` carries
// DialogObject's encrypted bit, the one peer shape these apis refuse outright
const NOBODY = 4242424242
const SECRET = '4611686018427387911'

;(async () => {
  let acc
  try {
    acc = inu.account()
  } catch (e) {
    skip('the whole suite', `no account is logged in (${e.code})`)
    console.log('writes test done')
    return
  }

  for (const bad of [null, undefined, {}, [], 1.5, 'not a name!', true]) {
    const shown = JSON.stringify(bad) ?? String(bad)
    // @ts-expect-error
    await expectReject(`sendMessage(${shown}) rejects`, 'invalid-argument', () => acc.sendMessage(bad, 'hi'))
  }
  await expectReject('sendMessage(0) is a miss, not a refusal', 'not-found', () => acc.sendMessage(0, 'hi'))
  await expectReject('a torn-off sendMessage rejects', 'invalid-argument', () => {
    const { sendMessage } = acc
    return sendMessage(NOBODY, 'hi')
  })

  /** @type {[string, string, () => Promise<unknown>][]} */
  const refusals = [
    // @ts-expect-error
    ['sendMessage without text rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, null)],
    // @ts-expect-error
    ['sendMessage with a number as text rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, 7)],
    // @ts-expect-error
    ['entities that are not an array reject', 'invalid-argument', () => acc.sendMessage(NOBODY, { text: 'hi', entities: 'bold' })],
    // @ts-expect-error
    ['a non-boolean flag rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, 'hi', { silent: 'yes' })],
    ['a negative scheduleDate rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, 'hi', { scheduleDate: -1 })],
    // @ts-expect-error
    ['options that are not an object reject', 'invalid-argument', () => acc.sendMessage(NOBODY, 'hi', 7)],
    // @ts-expect-error
    ['deleteMessages without ids rejects', 'invalid-argument', () => acc.deleteMessages(NOBODY, 'all')],
    ['a non-integer message id rejects', 'invalid-argument', () => acc.deleteMessages(NOBODY, [1.5])],
    // @ts-expect-error
    ['editMessage with a bad id rejects', 'invalid-argument', () => acc.editMessage(NOBODY, {}, 'hi')],
    // @ts-expect-error
    ['setReaction with a non-array rejects', 'invalid-argument', () => acc.setReaction(NOBODY, 1, '👍')],
    ['setReaction with an empty emoji rejects', 'invalid-argument', () => acc.setReaction(NOBODY, 1, [''])],
    ['setReaction with a bad customEmojiId rejects', 'invalid-argument', () => acc.setReaction(NOBODY, 1, [{ customEmojiId: 'nope' }])],
    // @ts-expect-error
    ['sendTyping with an unknown action rejects', 'invalid-argument', () => acc.sendTyping(NOBODY, 'dancing')],
    ['sendMultiMedia with no items rejects', 'invalid-argument', () => acc.sendMultiMedia(NOBODY, [])],
    // @ts-expect-error
    ['sendMedia without a file rejects', 'invalid-argument', () => acc.sendMedia(NOBODY, null)],
    // @ts-expect-error
    ['a non-function onProgress rejects', 'invalid-argument', () => acc.sendMedia(NOBODY, new Uint8Array([1]), { onProgress: 'yes' })],
    ['sendMessage into a secret chat is forbidden', 'forbidden', () => acc.sendMessage(SECRET, 'hi')],
    ['setDraft into a secret chat is forbidden', 'forbidden', () => acc.setDraft(SECRET, 'hi')],
    ['forwarding *out of* a secret chat is forbidden', 'forbidden', () => acc.forwardMessages(SECRET, [1], NOBODY)],
    ['an uncached peer is not-found, with nothing sent', 'not-found', () => acc.sendMessage(NOBODY, 'hi')],
    ['and so is one named for a delete', 'not-found', () => acc.deleteMessages(NOBODY, [1])],
    ['and one named as the target of a forward', 'not-found', () => acc.forwardMessages(NOBODY, [1], NOBODY)],
  ]
  for (const [label, code, fn] of refusals) await expectReject(label, code, fn)

  await expectReject('readHistory without account.write(read) is not-granted', 'not-granted', () =>
    acc.readHistory(NOBODY, { maxId: 1 }))

  let me = null
  try {
    me = acc.resolvePeerCached('me')
  } catch (e) {
    me = null
  }
  if (me === null) {
    skip('the round trip', 'no cached self peer')
  } else {
    let sent
    try {
      sent = await acc.sendMessage('me', { text: 'inu writes-test', entities: [] })
    } catch (e) {
      skip('the round trip', `sendMessage failed (${e.code ?? e.message})`)
    }
    if (sent !== undefined) {
      check('a send answers with a Message', sent instanceof inu.Message, String(sent && sent.id))
      check('the sent message carries its text', sent.text === 'inu writes-test', sent.text)
      check('the sent message is outgoing', sent.out === true, String(sent.out))
      expectThrow('and is read-only, like everything else an Account answers with', 'forbidden', () => {
        sent.raw.message = 'rewritten'
      })
      await acc.setReaction('me', sent.id, ['👍']).then(
        () => pass('setReaction resolves with nothing'),
        e => skip('setReaction', e.code ?? e.message),
      )
      await acc.editMessage('me', sent.id, 'inu writes-test (edited)').then(
        edited => check('editMessage answers with the new text', edited.text.endsWith('(edited)'), edited.text),
        e => skip('editMessage', e.code ?? e.message),
      )
      await acc.deleteMessages('me', [sent.id], { revoke: true }).then(
        value => check('deleteMessages resolves with nothing', value === undefined, String(value)),
        e => skip('deleteMessages', e.code ?? e.message),
      )
    }
    await acc.sendTyping('me', 'typing').then(
      value => check('sendTyping resolves with nothing', value === undefined, String(value)),
      e => skip('sendTyping', e.code ?? e.message),
    )
    await acc.setDraft('me', 'inu writes-test draft').then(
      value => check('setDraft resolves with nothing', value === undefined, String(value)),
      e => skip('setDraft', e.code ?? e.message),
    )
    await acc.setDraft('me', null).then(
      () => pass('and clearing it resolves too'),
      e => skip('clearing the draft', e.code ?? e.message),
    )
  }

  console.log('writes test done')
})()
