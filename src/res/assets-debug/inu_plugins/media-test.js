// ==UserScript==
// @name         media test
// @author       teidesu
// @version      1.0
// @description  asserts the media transfers hand back the shapes common.d.ts declares, and coalesce progress
// @grant        account.read(peers)
// @grant        account.read(messages)
// @grant        account.read(history)
// @grant        account.write(send)
// @plugin-api   1
// @platform     android
// ==/UserScript==

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
    else if (e.code !== code) fail(label, `code = ${e.code}, want ${code} (${e.message})`)
    else pass(label, e.message)
  }
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

// a message with a document on it, built by hand: every media api takes `Message | tl.TypeMessage`,
// so a literal is a first-class argument and the suite does not depend on a chat having one
/** @type {tl.RawMessage} */
const WITH_MEDIA = {
  _: 'message',
  id: 4242,
  peer_id: { _: 'peerUser', user_id: '4242' },
  date: 1715540640,
  message: '',
  media: {
    _: 'messageMediaDocument',
    document: {
      _: 'document',
      id: '99',
      access_hash: '1',
      file_reference: new Uint8Array(),
      date: 1715540640,
      dc_id: 2,
      size: '11',
      mime_type: 'text/plain',
      attributes: [{ _: 'documentAttributeFilename', file_name: 'note.txt' }],
    },
  },
}

;(async () => {
  let acc
  try {
    acc = inu.account()
  } catch (e) {
    skip('the whole suite', `no account is logged in (${e.code})`)
    console.log('media test done')
    return
  }

  // -- what is not a message never reaches the host --

  for (const bad of [null, undefined, 7, 'a message']) {
    const shown = JSON.stringify(bad) ?? String(bad)
    // @ts-expect-error
    expectThrows(`getMessageFile(${shown}) throws`, 'invalid-argument', () => acc.getMessageFile(bad))
    // @ts-expect-error
    await expectRejects(`downloadMedia(${shown}) rejects`, 'invalid-argument', () => acc.downloadMedia(bad))
  }
  expectThrows('a torn-off getMessageFile throws', 'invalid-argument', () => {
    const { getMessageFile } = acc
    return getMessageFile(WITH_MEDIA)
  })

  // -- a message with no media at all --

  /** @type {tl.RawMessage} */
  const bare = { _: 'message', id: 1, peer_id: { _: 'peerUser', user_id: '4242' }, date: 1715540640, message: 'hi' }
  check('getMessageFile on a message with no media is null', acc.getMessageFile(bare) === null)
  await expectRejects(
    'and downloading one rejects',
    'invalid-argument',
    () => acc.downloadMedia(bare),
  )

  // -- where the media would live --

  const where = acc.getMessageFile(WITH_MEDIA)
  if (where === null) {
    skip('getMessageFile', 'the host has no path for this message')
  } else {
    check('getMessageFile answers a path', typeof where.path === 'string' && where.path.length > 0, where.path)
    check('and says whether it is there', typeof where.exists === 'boolean', String(where.exists))
  }

  // -- the download --

  const seen = []
  let file
  try {
    file = await acc.downloadMedia(WITH_MEDIA, { onProgress: (loaded, total) => seen.push([loaded, total]) })
  } catch (e) {
    skip('downloadMedia', `${e.code ?? e.message}`)
  }
  if (file !== undefined) {
    check('downloadMedia hands back a File', file instanceof File, String(file))
    check('a File is a Blob', file instanceof Blob, String(file.size))
    check('the name survives the download', file.name === 'note.txt', file.name)
    check('and so does the type', file.type === 'text/plain', file.type)
    check('its size is the file on disk', file.size > 0, String(file.size))
    const text = await file.text()
    check('and it reads back', text.length === file.size, JSON.stringify(text))
    check('progress was reported', seen.length > 0, JSON.stringify(seen))
    check(
      'and the last report is the whole file',
      seen.length > 0 && seen[seen.length - 1][0] === seen[seen.length - 1][1],
      JSON.stringify(seen[seen.length - 1]),
    )
    check(
      'coalesced rather than per chunk',
      seen.length < 20,
      `${seen.length} reports`,
    )
  }

  let saved
  try {
    saved = await acc.downloadMediaToFile(WITH_MEDIA)
  } catch (e) {
    skip('downloadMediaToFile', `${e.code ?? e.message}`)
  }
  if (saved !== undefined) {
    check('downloadMediaToFile answers a path and nothing else', typeof saved.path === 'string', saved.path)
    check('and not a File', !(saved instanceof Blob), Object.keys(saved).join())
  }

  // -- the upload --

  await expectRejects(
    'uploadFile with nothing rejects',
    'invalid-argument',
    // @ts-expect-error
    () => acc.uploadFile(null),
  )
  await expectRejects(
    'uploading a path without fs is refused',
    'not-granted',
    () => acc.uploadFile({ path: '/etc/hosts' }),
  )

  const content = new Blob([new Uint8Array([1, 2, 3, 4, 5])], { type: 'application/octet-stream' })
  const named = new File([content], 'payload.bin', { type: 'application/octet-stream' })
  let input
  try {
    input = await acc.uploadFile(named)
  } catch (e) {
    skip('uploadFile', `${e.code ?? e.message}`)
  }
  if (input !== undefined) {
    check('uploadFile answers an InputFile', String(input._).startsWith('inputFile'), input._)
    check('named as the File was', input.name === 'payload.bin', String(input.name))
  }

  let renamed
  try {
    renamed = await acc.uploadFile(new Uint8Array([9, 9, 9]), { fileName: 'chosen.dat' })
  } catch (e) {
    skip('uploadFile(bytes)', `${e.code ?? e.message}`)
  }
  if (renamed !== undefined) {
    check('bytes upload too, under the name asked for', renamed.name === 'chosen.dat', String(renamed.name))
  }

  // a disposed blob has no content to stage, and says so rather than uploading nothing
  const dead = new Blob([new Uint8Array([1, 2, 3])])
  dead.dispose()
  await expectRejects('uploading a disposed Blob rejects', 'handle-expired', () => acc.uploadFile(dead))

  console.log('media test done')
})()
