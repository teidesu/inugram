// ==InuPlugin==
// @name         ui test
// @description  exercises the settings page: every element, anchored menus, prompt, page lifetime
// ==/InuPlugin==

// the load-time count is exact; the interactive half reports as you touch the page

const state = {
  enabled: localStorage.getItem('enabled') === 'true',
  notify: false,
  mode: Number(localStorage.getItem('mode') ?? '0'),
  theme: 0,
  speed: Number(localStorage.getItem('speed') ?? '1'),
  name: localStorage.getItem('name'),
  extraSection: false,
  asyncStatus: 'idle',
  clicks: 0,
}

const nestedPage = inu.ui.settingsPage({
  title: 'Nested page',
  items: () => [
    inu.ui.header('Deeper'),
    inu.ui.button({
      text: 'Click me',
      value: String(state.clicks),
      onClick: () => {
        state.clicks++
        mainPage.invalidate()
      },
    }),
    inu.ui.separator('this page was opened via inu.ui.openPage() from a button'),
  ],
  onClose: () => console.log('nested page closed'),
})

const mainPage = inu.ui.settingsPage({
  title: 'UI test',
  items: () => [
    inu.ui.header('Toggles'),
    inu.ui.check({
      text: 'Persisted toggle',
      subtitle: 'stored in localStorage, survives restarts',
      checked: state.enabled,
      onChange: (v) => {
        state.enabled = v
        localStorage.setItem('enabled', String(v))
      },
    }),
    inu.ui.check({
      id: 'plain-toggle',
      text: 'Plain toggle',
      checked: state.notify,
      onChange: (v, anchor) => {
        state.notify = v
        check('onChange is handed an anchor', typeof anchor?.openMenu === 'function', typeof anchor)
      },
      onSecondaryClick: (anchor) => {
        check('onSecondaryClick is handed an anchor', typeof anchor?.openMenu === 'function', typeof anchor)
        anchor.openMenu([
          { text: 'Turn on', checked: state.notify, onClick: () => { state.notify = true } },
          { text: 'Turn off', checked: !state.notify, onClick: () => { state.notify = false } },
        ])
      },
    }),
    inu.ui.check({
      text: 'Show extra section',
      checked: state.extraSection,
      onChange: (v) => {
        state.extraSection = v
      },
    }),
    ...(state.extraSection
      ? [
          inu.ui.header('Extra section'),
          inu.ui.button({ text: 'Conditional row', onClick: () => inu.ui.toast('hi from the extra section') }),
        ]
      : []),
    inu.ui.separator('long-tap the plain toggle for an anchored menu'),

    inu.ui.header('Selects'),
    inu.ui.select({
      text: 'Mode (menu)',
      items: ['Off', 'Normal', 'Aggressive'],
      selected: state.mode,
      onChange: (i, anchor) => {
        state.mode = i
        localStorage.setItem('mode', String(i))
        check('select onChange is handed an anchor', typeof anchor?.openMenu === 'function', typeof anchor)
      },
    }),
    inu.ui.select({
      text: 'Theme (dialog)',
      dialog: true,
      items: [
        { text: 'System', subtitle: 'follow the OS setting' },
        { text: 'Light' },
        { text: 'Dark', subtitle: 'always on' },
      ],
      selected: state.theme,
      onChange: (i) => {
        state.theme = i
      },
    }),
    inu.ui.separator(),

    inu.ui.header('Slider'),
    inu.ui.slider({
      text: 'Speed',
      min: 0.5,
      max: 3,
      step: 0.5,
      value: state.speed,
      default: 1,
      label: (v) => v + 'x',
      onChange: (v, anchor) => {
        state.speed = v
        localStorage.setItem('speed', String(v))
        check('slider onChange is handed an anchor', typeof anchor?.openMenu === 'function', typeof anchor)
      },
    }),
    inu.ui.separator(),

    inu.ui.header('Actions'),
    inu.ui.button({
      text: 'Set name',
      value: state.name ?? 'unset',
      onClick: async () => {
        const name = await inu.ui.prompt({ title: 'Your name?', hint: 'name', value: state.name ?? '', selectAll: true })
        if (name !== null) {
          state.name = name
          localStorage.setItem('name', name)
          mainPage.invalidate()
        }
      },
    }),
    inu.ui.button({
      // by the time this resumes, the auto-invalidate re-rendered the page and freed this render's callback slots
      id: 'anchor-after-await',
      text: 'Menu after an await',
      subtitle: 'the anchor has to survive the re-render',
      onClick: async (anchor) => {
        await new Promise((resolve) => { setTimeout(() => resolve(null), 400) })
        try {
          anchor.openMenu([
            { text: 'still anchored to this row', onClick: () => pass('an anchor survives an await') },
            { text: 'cancel', onClick: () => {} },
          ])
        } catch (e) {
          fail('an anchor survives an await', `${e.name}: ${e.message}`)
        }
      },
    }),
    inu.ui.button({
      text: 'Async work',
      subtitle: 'invalidate() after an await',
      value: state.asyncStatus,
      onClick: async () => {
        state.asyncStatus = 'working…'
        mainPage.invalidate()
        await new Promise((resolve) => {
          let i = 0
          const spin = () => (++i < 100000 ? Promise.resolve().then(spin) : resolve(undefined))
          spin()
        })
        state.asyncStatus = 'done'
        mainPage.invalidate()
      },
    }),
    inu.ui.button({ text: 'Open nested page', onClick: () => inu.ui.openPage(nestedPage) }),
    inu.ui.button({
      text: 'Open transient page',
      subtitle: 'factory-made instance, auto-disposed on close',
      onClick: () => {
        const stamp = ++state.clicks
        const page = inu.ui.settingsPage({
          title: 'Transient #' + stamp,
          transient: true,
          items: () => [inu.ui.separator('this page def is freed once you navigate back')],
          // the dispose happens after onClose returns
          onClose: () => queueMicrotask(() => {
            expectThrow('a transient page is disposed once its onClose returned', 'handle-expired', () => {
              inu.ui.openPage(page)
            })
          }),
        })
        inu.ui.openPage(page)
      },
    }),
    inu.ui.button({
      text: 'Reset everything',
      danger: true,
      onClick: (anchor) => {
        anchor.openMenu([
          {
            text: 'Yes, reset',
            danger: true,
            onClick: (...args) => {
              check('a menu item gets no anchor, so a menu cannot open a menu', args.length === 0, args.length)
              localStorage.clear()
              state.enabled = false
              state.mode = 0
              state.speed = 1
              state.name = null
              inu.ui.toast('reset done')
            },
          },
          { text: 'Cancel', onClick: () => {} },
        ])
      },
    }),
    inu.ui.separator(),
  ],
  bottomButton: {
    text: 'Show summary',
    onClick: (anchor) => {
      check('the bottom button is handed an anchor', typeof anchor?.openMenu === 'function', typeof anchor)
      inu.ui.dialog({
        title: 'Current state',
        message: JSON.stringify(state, null, 2),
        positive: 'OK',
      })
    },
  },
  onClose: () => console.log('main page closed'),
})

inu.registerSettings(mainPage)

const EXPECTED = 17
const before = ran

// a vanished member satisfies every expectThrow, so the surface is asserted positively first
const MEMBERS = ['settingsPage', 'openPage', 'header', 'check', 'button', 'select', 'slider', 'separator', 'prompt']
check(
  'inu.ui declares every member this page uses',
  MEMBERS.every(m => typeof inu.ui[m] === 'function') && typeof inu.registerSettings === 'function',
  MEMBERS.filter(m => typeof inu.ui[m] !== 'function').join(',') || 'all present',
)
check(
  'the free ui.openMenu is gone, the anchor replaced it',
  // @ts-expect-error
  inu.ui.openMenu === undefined,
)

expectThrow('check refuses a missing `checked`', null, () => {
  // @ts-expect-error
  inu.ui.check({ text: 'x', onChange: () => {} })
})
expectThrow('button refuses a missing `onClick`', null, () => {
  // @ts-expect-error
  inu.ui.button({ text: 'x' })
})
expectThrow('select refuses an empty item list', null, () => {
  inu.ui.select({ text: 'x', items: [], selected: 0, onChange: () => {} })
})
expectThrow('select refuses a `selected` out of range', null, () => {
  inu.ui.select({ text: 'x', items: ['a'], selected: 5, onChange: () => {} })
})
expectThrow('slider refuses a zero step', null, () => {
  inu.ui.slider({ min: 0, max: 10, step: 0, value: 1, onChange: () => {} })
})
expectThrow('slider refuses max <= min', null, () => {
  inu.ui.slider({ min: 10, max: 10, step: 1, value: 10, onChange: () => {} })
})
// the strip is precomputed, so a label past the step cap is refused rather than silently dropped
expectThrow('slider refuses a label strip past the step cap', 'invalid-argument', () => {
  inu.ui.slider({ min: 0, max: 2000, step: 1, value: 0, label: (v) => v + ' MB', onChange: () => {} })
})
check(
  'the same range without a label is fine',
  typeof inu.ui.slider({ min: 0, max: 2000, step: 1, value: 0, onChange: () => {} }) === 'object',
)
check('header and a textless separator are elements', [
  inu.ui.header('h'),
  inu.ui.separator(),
  inu.ui.separator('with a footer'),
].every(e => typeof e === 'object'))

expectThrow('a second registerSettings throws rather than picking a winner', null, () => {
  inu.registerSettings(mainPage)
})
mainPage.invalidate()
pass('invalidate() on a page nobody has open is a no-op')

const throwaway = inu.ui.settingsPage({ title: 'throwaway', items: () => [] })
throwaway.dispose()
throwaway.dispose()
pass('a second dispose() is a no-op')
expectThrow('opening a disposed page is handle-expired', 'handle-expired', () => {
  inu.ui.openPage(throwaway)
})
expectThrow('registering a disposed page is handle-expired', 'handle-expired', () => {
  inu.registerSettings(throwaway)
})
expectThrow('openPage refuses something that is not a page at all', null, () => {
  // @ts-expect-error
  inu.ui.openPage({})
})

const actual = ran - before
if (actual !== EXPECTED) {
  console.error(`FAIL oracle: ${actual} load-time assertions ran, expected exactly ${EXPECTED}`)
}

console.log('ui test done')
