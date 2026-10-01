// ==InuPlugin==
// @name         send intercept test
// @description  asserts inu.interceptSendMessage hands a middleware the message as plain values, that what it hands back names the media it kept, and that a drop is total
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

// the harness sends a text, an album of a picked file and a sticker, a forward and a text asking to
// be dropped, then hands `__report` the verdicts: rewrites and drops are checked against those

const seen = []

inu.interceptSendMessage(({ message: m, account }) => {
  seen.push({
    peer: m.peer,
    text: m.text.text,
    media: m.media.map(item => [item._, 'kind' in item ? item.kind : null, 'spoiler' in item ? item.spoiler ?? null : null]),
    reply: m.reply,
    forward: m.forward,
    sealed: Object.isSealed(m),
    account: typeof account === 'object' && account !== null && typeof account.id === 'number',
  })

  if (m.text.text === 'drop me') return 'drop'

  if (m.media.length > 1) {
    m.media.reverse()
    m.media[1].spoiler = true
    m.media.push({ _: 'inputMediaDice', emoticon: '🎲' })
  }
  if (m.forward !== null) m.forward.mode = 'hide-sender'
  m.text = `[${m.text.text}]`
  m.silent = true
  return 'send'
})

globalThis.__report = (verdicts) => {
  check('the disposed middleware never ran', neverRan === 0, String(neverRan))
  check('every send reached the middleware', seen.length === 4, String(seen.length))

  const [text, album, forward, dropped] = seen

  check('the peer is what the host said', text.peer === 7, String(text.peer))
  check('the message cannot grow fields', text.sealed)
  check('a picked file reads as a LocalMedia', JSON.stringify(album.media[0]) === '["localMedia","photo",false]', JSON.stringify(album.media))
  check('anything else reads as the TL it is', album.media[1][0] === 'inputMediaDocument', JSON.stringify(album.media))
  check('a reply reads as the host gave it', album.reply?.messageId === 3 && album.reply.quote === null, JSON.stringify(album.reply))
  check('a forward reads as the host gave it', forward.forward?.messageIds.join() === '5,6', JSON.stringify(forward.forward))
  check('the account handle comes with it', seen.every(s => s.account))
  check('the dropped send was seen before it was dropped', dropped.text === 'drop me', dropped.text)

  check('a drop is its own verdict', verdicts[3] === 'D', verdicts[3])
  const messages = verdicts.slice(0, 3).map(v => JSON.parse(v.slice(1)))
  check(
    'the rewritten text is what goes back',
    messages.map(m => m.text.text).join(',') === '[hi],[two],[look]',
    messages.map(m => m.text.text).join(','),
  )
  check('a string assigned as the text goes back without entities', messages[0].text.entities.length === 0)
  check('a flag written by a middleware goes back', messages[0].silent === true)
  const media = messages[1].media
  check('kept media goes back named by where it was', JSON.stringify(media.map(item => item.kept)) === '[1,0,-1]', JSON.stringify(media))
  check('a kept LocalMedia goes back with its id and spoiler', media[1].local?.id === '11' && media[1].local.spoiler === true, JSON.stringify(media[1]))
  check('an added item goes back as the TL it is', media[2].tl?._ === 'inputMediaDice', JSON.stringify(media[2]))
  check('a forward edit goes back', messages[2].forward.mode === 'hide-sender', JSON.stringify(messages[2].forward))
  check('nothing that went back says "drop me"', verdicts.every(v => !v.includes('drop me')))

  console.log('send intercept test done')
}
