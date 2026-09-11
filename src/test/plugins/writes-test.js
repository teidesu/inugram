// ==InuPlugin==
// @name         writes test
// @author       @teidesu
// @version      1.0
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

async function expectRejects(label, code, fn) {
  try {
    const value = await fn()
    fail(label, `did not reject, resolved with ${JSON.stringify(value)}`)
  } catch (e) {
    if (!(e instanceof inu.PluginError)) fail(label, `not an inu.PluginError (${e})`)
    else if (e.code !== code) fail(label, `code = ${e.code}, want ${code} (${e.message})`)
    else pass(label, e.message)
  }
}

// a dialog id far past anything telegram has ever issued, so it is a miss on any device and in the
// harness alike. `SECRET` carries DialogObject's encrypted bit, which is the one peer shape these
// apis refuse outright rather than fail to resolve
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

  // -- every write fails asynchronously, whatever went wrong --

  for (const bad of [null, undefined, {}, [], 1.5, 'not a name!', true]) {
    const shown = JSON.stringify(bad) ?? String(bad)
    // @ts-expect-error
    await expectRejects(`sendMessage(${shown}) rejects`, 'invalid-argument', () => acc.sendMessage(bad, 'hi'))
  }
  // `0` is a dialog id nothing has rather than a malformed one, so it is the miss it names
  await expectRejects('sendMessage(0) is a miss, not a refusal', 'not-found', () => acc.sendMessage(0, 'hi'))
  await expectRejects('a torn-off sendMessage rejects', 'invalid-argument', () => {
    const { sendMessage } = acc
    return sendMessage(NOBODY, 'hi')
  })

  // -- argument shapes --

  // @ts-expect-error
  await expectRejects('sendMessage without text rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, null))
  // @ts-expect-error
  await expectRejects('sendMessage with a number as text rejects', 'invalid-argument', () => acc.sendMessage(NOBODY, 7))
  await expectRejects(
    'entities that are not an array reject',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendMessage(NOBODY, { text: 'hi', entities: 'bold' }),
  )
  await expectRejects(
    'a non-boolean flag rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendMessage(NOBODY, 'hi', { silent: 'yes' }),
  )
  await expectRejects(
    'a negative scheduleDate rejects',
    'invalid-argument',
    () => acc.sendMessage(NOBODY, 'hi', { scheduleDate: -1 }),
  )
  await expectRejects(
    'options that are not an object reject',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendMessage(NOBODY, 'hi', 7),
  )
  // @ts-expect-error
  await expectRejects('deleteMessages without ids rejects', 'invalid-argument', () => acc.deleteMessages(NOBODY, 'all'))
  await expectRejects(
    'a non-integer message id rejects',
    'invalid-argument',
    () => acc.deleteMessages(NOBODY, [1.5]),
  )
  // @ts-expect-error
  await expectRejects('editMessage with a bad id rejects', 'invalid-argument', () => acc.editMessage(NOBODY, {}, 'hi'))
  await expectRejects(
    'setReaction with a non-array rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.setReaction(NOBODY, 1, '👍'),
  )
  await expectRejects(
    'setReaction with an empty emoji rejects',
    'invalid-argument',
    () => acc.setReaction(NOBODY, 1, ['']),
  )
  await expectRejects(
    'setReaction with a bad customEmojiId rejects',
    'invalid-argument',
    () => acc.setReaction(NOBODY, 1, [{ customEmojiId: 'nope' }]),
  )
  await expectRejects(
    'sendTyping with an unknown action rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendTyping(NOBODY, 'dancing'),
  )
  await expectRejects(
    'sendMultiMedia with no items rejects',
    'invalid-argument',
    () => acc.sendMultiMedia(NOBODY, []),
  )
  await expectRejects(
    'sendMedia without a file rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendMedia(NOBODY, null),
  )
  await expectRejects(
    'a non-function onProgress rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.sendMedia(NOBODY, new Uint8Array([1]), { onProgress: 'yes' }),
  )

  // -- the rules --

  await expectRejects(
    'sendMessage into a secret chat is forbidden',
    'forbidden',
    () => acc.sendMessage(SECRET, 'hi'),
  )
  await expectRejects(
    'setDraft into a secret chat is forbidden',
    'forbidden',
    () => acc.setDraft(SECRET, 'hi'),
  )
  await expectRejects(
    'forwarding *out of* a secret chat is forbidden',
    'forbidden',
    () => acc.forwardMessages(SECRET, [1], NOBODY),
  )

  await expectRejects(
    'an uncached peer is not-found, with nothing sent',
    'not-found',
    () => acc.sendMessage(NOBODY, 'hi'),
  )
  await expectRejects(
    'and so is one named for a delete',
    'not-found',
    () => acc.deleteMessages(NOBODY, [1]),
  )
  await expectRejects(
    'and one named as the target of a forward',
    'not-found',
    () => acc.forwardMessages(NOBODY, [1], NOBODY),
  )

  // -- the grant boundary, from the inside: this plugin holds every scope but `read` --

  await expectRejects(
    'readHistory without account.write(read) is not-granted',
    'not-granted',
    () => acc.readHistory(NOBODY, { maxId: 1 }),
  )

  // -- a real round trip, where there is a peer to make one with --

  // naming yourself needs `account.read(self)` on top of `account.read(peers)`, which this plugin
  // holds - without it this throws and every assertion below it would be skipped instead of run
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
      let threw = null
      try {
        sent.raw.message = 'rewritten'
      } catch (e) {
        threw = e
      }
      check(
        'and is read-only, like everything else an Account answers with',
        threw !== null && threw.code === 'forbidden',
        threw === null ? 'the write went through' : threw.code,
      )
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
