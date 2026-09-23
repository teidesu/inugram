// ==InuPlugin==
// @name         shell test
// @description  asserts inu.openUrl screens what it hands the system, and exercises inu.clipboard and inu.ui.chooser
// @grant        openUrl
// @grant        clipboard.read
// @grant        clipboard.write
// ==/InuPlugin==

// the load-time half opens nothing. the half the user would notice (clipboard, browser, three
// dialogs) is a function, reached from a button on a device

// exact: a vanished member satisfies every refusal, so the surface is asserted positively and the count catches the rest
const EXPECTED = 18
const before = ran

check(
  'the shell members are all declared',
  typeof inu.openUrl === 'function' &&
    typeof inu.clipboard.read === 'function' &&
    typeof inu.clipboard.write === 'function' &&
    typeof inu.ui.chooser === 'function',
)

let deepLink = 'opened'
try {
  inu.openUrl('tg://resolve?domain=durov')
} catch (e) {
  deepLink = `${e.name}: ${e.message}`
}
check('openUrl accepts Telegram deep links', deepLink === 'opened', deepLink)

// the allowlist is all that stands between a url grant and starting any activity with any extras
for (const url of [
  'intent://scan/#Intent;scheme=zxing;package=com.evil;end',
  'file:///data/data/org.telegram.messenger/files/plugins',
  'content://media/external/images/media/1',
  'javascript:alert(1)',
  'telegram.org',
]) {
  expectThrow(`openUrl refuses ${url}`, 'invalid-argument', () => inu.openUrl(url))
}

for (const url of [
  'https://telegram.org@evil.com/',
  'https://evil.com\\@telegram.org/',
  'https://tele gram.org/',
  'https://telegram.org/a\nb',
  'https:///nohost',
]) {
  expectThrow(`openUrl refuses ${JSON.stringify(url)}`, 'invalid-argument', () => inu.openUrl(url))
}

expectThrow('chooser refuses an empty item list', null, () => {
  inu.ui.chooser({ items: [] })
})
expectThrow('chooser refuses items that are not a list', null, () => {
  // @ts-expect-error
  inu.ui.chooser({ items: 'first, second' })
})
expectThrow('chooser refuses an item that is neither text nor an object', null, () => {
  // @ts-expect-error
  inu.ui.chooser({ items: [42] })
})
expectThrow('chooser refuses a `selected` out of range', null, () => {
  inu.ui.chooser({ items: ['first', 'second'], selected: 5 })
})
expectThrow('chooser refuses a list of indices unless `multiple` is set', null, () => {
  // @ts-expect-error
  inu.ui.chooser({ items: ['first', 'second'], selected: [0] })
})
expectThrow('chooser refuses a single index when `multiple` is set', null, () => {
  // @ts-expect-error
  inu.ui.chooser({ items: ['first', 'second'], multiple: true, selected: 0 })
})

if (ran - before !== EXPECTED) {
  console.error(`FAIL oracle: ${ran - before} load-time assertions ran, expected exactly ${EXPECTED}`)
}

async function runShell() {
  const text = 'inugram shell test'
  inu.clipboard.write(text)
  check('the clipboard reads back what was just written', inu.clipboard.read() === text, inu.clipboard.read())

  let opened = 'no-throw'
  try {
    inu.openUrl('https://telegram.org/')
  } catch (e) {
    opened = `${e.name}: ${e.message}`
  }
  check('openUrl accepts an https url', opened === 'no-throw', opened)

  const one = await inu.ui.chooser({
    title: 'select the third one, then OK',
    items: ['first', { text: 'second', subtitle: 'with a subtitle' }, { text: 'third', danger: true }],
    selected: 1,
  })
  check('a single-select chooser resolves to the index', one === 2, JSON.stringify(one))

  const many = await inu.ui.chooser({
    title: 'keep the first and the last, then OK',
    items: ['first', 'second', 'third'],
    multiple: true,
    selected: [0, 2],
  })
  check('a multi-select chooser resolves to a list of indices', Array.isArray(many) && many.join(',') === '0,2', JSON.stringify(many))

  const none = await inu.ui.chooser({ title: 'select it, then cancel', items: ['first'] })
  check('a dismissed chooser resolves to null', none === null, JSON.stringify(none))

  console.log('shell test done')
}

globalThis.__shell = runShell

if (typeof inu.ui.settingsPage === 'function') {
  inu.registerSettings(
    inu.ui.settingsPage({
      title: 'shell test',
      items: () => [
        inu.ui.separator('runs the half that writes your clipboard, opens a browser and shows three dialogs'),
        inu.ui.button({ text: 'Run it', onClick: () => { void runShell() } }),
      ],
    }),
  )
}
