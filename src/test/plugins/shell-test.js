// ==InuPlugin==
// @name         shell test
// @author       teidesu
// @version      1.0
// @description  asserts inu.openUrl screens what it hands the system, and exercises inu.clipboard and inu.ui.chooser
// @grant        openUrl
// @grant        clipboard.read
// @grant        clipboard.write
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// the load-time half is everything that can run without touching the user's device: a refused url
// opens nothing and a refused chooser shows nothing. the half that does something the user would
// notice - clobbering their clipboard, opening a browser, putting three dialogs on screen - is a
// function, reached from a button on a device.

let ran = 0

function pass(label, detail) {
  ran++
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  ran++
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function expectThrow(label, fn) {
  try {
    fn()
  } catch (e) {
    return pass(label, `${e.name}: ${e.message}`)
  }
  fail(label, 'did not throw')
}

function expectPluginError(label, code, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  check(label, error instanceof inu.PluginError && error.code === code, `${error.name}: ${error.code}`)
}

// -- load-time half --

// exact, not a floor: every refusal below is satisfied by a member that stopped existing, so the
// surface is asserted positively first and the count is what catches the rest
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

// the allowlist is the only thing between a url grant and "start any activity on the device with any extras"
for (const url of [
  'intent://scan/#Intent;scheme=zxing;package=com.evil;end',
  'file:///data/data/org.telegram.messenger/files/plugins',
  'content://media/external/images/media/1',
  'javascript:alert(1)',
  'telegram.org',
]) {
  expectPluginError(`openUrl refuses ${url}`, 'invalid-argument', () => inu.openUrl(url))
}

// and a url that is not the host it reads as
for (const url of [
  'https://telegram.org@evil.com/',
  'https://evil.com\\@telegram.org/',
  'https://tele gram.org/',
  'https://telegram.org/a\nb',
  'https:///nohost',
]) {
  expectPluginError(`openUrl refuses ${JSON.stringify(url)}`, 'invalid-argument', () => inu.openUrl(url))
}

expectThrow('chooser refuses an empty item list', () => {
  inu.ui.chooser({ items: [] })
})
expectThrow('chooser refuses items that are not a list', () => {
  // @ts-expect-error
  inu.ui.chooser({ items: 'first, second' })
})
expectThrow('chooser refuses an item that is neither text nor an object', () => {
  // @ts-expect-error
  inu.ui.chooser({ items: [42] })
})
expectThrow('chooser refuses a `selected` out of range', () => {
  inu.ui.chooser({ items: ['first', 'second'], selected: 5 })
})
expectThrow('chooser refuses a list of indices unless `multiple` is set', () => {
  // @ts-expect-error
  inu.ui.chooser({ items: ['first', 'second'], selected: [0] })
})
expectThrow('chooser refuses a single index when `multiple` is set', () => {
  // @ts-expect-error
  inu.ui.chooser({ items: ['first', 'second'], multiple: true, selected: 0 })
})

if (ran - before !== EXPECTED) {
  console.error(`FAIL oracle: ${ran - before} load-time assertions ran, expected exactly ${EXPECTED}`)
}

// -- the half that touches the device --

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
    title: 'pick the third one',
    items: ['first', { text: 'second', subtitle: 'with a subtitle' }, { text: 'third', danger: true }],
    selected: 1,
  })
  check('a single-select chooser resolves to the index', one === 2, JSON.stringify(one))

  const many = await inu.ui.chooser({
    title: 'pick the first and the last',
    items: ['first', 'second', 'third'],
    multiple: true,
    selected: [0, 2],
  })
  check(
    'a multi-select chooser resolves to a list of indices',
    Array.isArray(many) && many.join(',') === '0,2',
    JSON.stringify(many),
  )

  const none = await inu.ui.chooser({ title: 'dismiss this one', items: ['first'] })
  check('a dismissed chooser resolves to null', none === null, JSON.stringify(none))

  console.log('shell test done')
}

globalThis.__shell = runShell

// a device reaches the same function through a button; the harness has no settings pages
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
