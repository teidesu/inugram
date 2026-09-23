// ==InuPlugin==
// @name         send intercept test
// @description  asserts inu.interceptSendMessage normalizes all four send methods into one OutgoingMessage, that a rewrite lands on the request that goes out and that a drop is total
// @grant        interceptSendMessage
// ==/InuPlugin==

check('interceptSendMessage exists', typeof inu.interceptSendMessage === 'function')

const disposer = inu.interceptSendMessage(() => 'send')
check('registering hands back a disposer', typeof disposer === 'function')
disposer()
disposer()
pass('disposing twice is a no-op')

let neverRan = 0
inu.interceptSendMessage(() => { neverRan += 1; return 'drop' })()

// the harness sends one of each send method plus a sendMessage asking to be dropped, then hands
// `__report` what the host was asked to send: rewrites and drops are checked against that

const seen = []
const refusals = []
const silentRefusals = []

inu.interceptSendMessage(({ message: m, account }) => {
  seen.push({
    peer: m.peer,
    text: m.text.text,
    media: m.media.length,
    silent: m.silent,
    isEdit: m.isEdit,
    editMessageId: m.editMessageId,
    replyToMessageId: m.replyToMessageId,
    topicId: m.topicId,
    scheduleDate: m.scheduleDate,
    account: typeof account === 'object' && account !== null && typeof account.id === 'number',
  })

  if (m.text.text === 'drop me') return 'drop'

  // a shape change is refused rather than swapping the method the app awaits a response type for
  try {
    m.media = m.media.concat([{ _: 'inputMediaEmpty' }])
    refusals.push('no-throw')
  } catch (e) {
    refusals.push(e.code)
  }

  m.text = { text: `[${m.text.text}]`, entities: [] }
  // a flag-clear field is omitted from reads too, so the shape is read off the method, not probed
  try {
    m.silent = true
    silentRefusals.push('no-throw')
  } catch (e) {
    silentRefusals.push(e.code)
  }
  return 'send'
})

globalThis.__report = (sent) => {
  check('the disposed middleware never ran', neverRan === 0, String(neverRan))
  check('every send reached the middleware', seen.length === 5, String(seen.length))

  const [text, media, album, edit, dropped] = seen

  check('a text send carries no media', text.media === 0 && text.text === 'hi', JSON.stringify(text))
  check('a dialog id is what a peer reads as', text.peer === 7, String(text.peer))
  check('a channel send reads as a negative dialog id', media.peer === -9, String(media.peer))
  check('a basic group send reads as a negative dialog id', album.peer === -5, String(album.peer))

  check('a media send carries exactly one', media.media === 1, String(media.media))
  check('a media send carries its caption as the text', media.text === 'cap', media.text)
  check('silent is read off the request', media.silent === true && text.silent === false)

  check('an album carries one media per item', album.media === 2, String(album.media))
  check("an album's caption is its first item's", album.text === 'one', album.text)

  check('an edit says so', edit.isEdit === true && edit.editMessageId === 42, JSON.stringify(edit))
  check('a send is not an edit', text.isEdit === false && text.editMessageId === null)

  check('an unset reply reads as null', text.replyToMessageId === null && text.topicId === null)
  check('an unscheduled send reads as null', text.scheduleDate === null)
  check('the account handle comes with it', seen.every(s => s.account))
  check('the dropped send was seen before it was dropped', dropped.text === 'drop me', dropped.text)

  check(
    'only an edit refuses to be sent silently',
    silentRefusals.join(',') === 'no-throw,no-throw,no-throw,unsupported',
    silentRefusals.join(','),
  )
  check(
    'attaching media is refused rather than swapping the method',
    refusals.length === 4 && refusals.every(code => code === 'unsupported'),
    JSON.stringify(refusals),
  )

  check('a dropped send never reaches the network', sent.length === 4, String(sent.length))
  check(
    'the rewritten text is what goes out',
    sent.map(s => JSON.parse(s).message).join(',') === '[hi],[cap],,[fixed]',
    sent.map(s => JSON.parse(s).message).join(','),
  )
  check("an album's caption is rewritten on its first item", JSON.parse(sent[2]).multi_media[0].message === '[one]', sent[2])
  check('a flag written by a middleware goes out', JSON.parse(sent[0]).silent === true, sent[0])
  check('nothing that went out says "drop me"', sent.every(s => !s.includes('drop me')))

  console.log('send intercept test done')
}
