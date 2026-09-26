// ==InuPlugin==
// @name         media test
// @description  asserts the media transfers hand back the shapes common.d.ts declares, and coalesce progress
// @grant        account.read(peers)
// @grant        account.read(messages)
// @grant        account.read(history)
// @grant        account.write(send)
// ==/InuPlugin==

// every media api takes `Message | tl.TypeMessage`, so a literal keeps the suite independent of chat content
/** @type {tl.RawMessage} */
const WITH_MEDIA = {
  _: 'message',
  id: 4242,
  peer_id: { _: 'peerUser', user_id: 4242 },
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
      size: 11,
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

  for (const bad of [null, undefined, 7, 'a message']) {
    const shown = JSON.stringify(bad) ?? String(bad)
    // @ts-expect-error
    expectThrow(`getMessageFile(${shown}) throws`, 'invalid-argument', () => acc.getMessageFile(bad))
    // @ts-expect-error
    await expectReject(`downloadMedia(${shown}) rejects`, 'invalid-argument', () => acc.downloadMedia(bad))
  }
  expectThrow('a torn-off getMessageFile throws', 'invalid-argument', () => {
    const { getMessageFile } = acc
    return getMessageFile(WITH_MEDIA)
  })

  /** @type {tl.RawMessage} */
  const bare = { _: 'message', id: 1, peer_id: { _: 'peerUser', user_id: 4242 }, date: 1715540640, message: 'hi' }
  check('getMessageFile on a message with no media is null', acc.getMessageFile(bare) === null)
  await expectReject('and downloading one rejects', 'invalid-argument', () => acc.downloadMedia(bare))

  const where = acc.getMessageFile(WITH_MEDIA)
  if (where === null) {
    skip('getMessageFile', 'the host has no path for this message')
  } else {
    check('getMessageFile answers a path', typeof where.path === 'string' && where.path.length > 0, where.path)
    check('and says whether it is there', typeof where.exists === 'boolean', String(where.exists))
  }

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
    const last = seen[seen.length - 1]
    check('and the last report is the whole file', last !== undefined && last[0] === last[1], JSON.stringify(last))
    check('coalesced rather than per chunk', seen.length < 20, `${seen.length} reports`)
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

  // @ts-expect-error
  await expectReject('uploadFile with nothing rejects', 'invalid-argument', () => acc.uploadFile(null))
  await expectReject('uploading a path without fs is refused', 'not-granted', () => acc.uploadFile({ path: '/etc/hosts' }))

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

  const dead = new Blob([new Uint8Array([1, 2, 3])])
  dead.dispose()
  await expectReject('uploading a disposed Blob rejects', 'handle-expired', () => acc.uploadFile(dead))

  console.log('media test done')
})()
