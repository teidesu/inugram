// ==UserScript==
// @name         ui test
// @author       teidesu
// @namespace    inugram.dev
// @version      1.0
// @description  exercises the settings-page ui: every element, menus, prompt, nested pages
// @grant        inu.kv
// @plugin-api   1
// @platform     android
// ==/UserScript==
/* eslint-disable eslint-comments/no-unlimited-disable */
/* eslint-disable */

const state = {
  enabled: inu.kv.get('enabled') === 'true',
  notify: false,
  mode: Number(inu.kv.get('mode') ?? '0'),
  theme: 0,
  speed: Number(inu.kv.get('speed') ?? '1'),
  name: inu.kv.get('name'),
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
      subtitle: 'stored in inu.kv, survives restarts',
      checked: state.enabled,
      onChange: (v) => {
        state.enabled = v
        inu.kv.set('enabled', String(v))
      },
    }),
    inu.ui.check({
      text: 'Plain toggle',
      checked: state.notify,
      onChange: (v) => {
        state.notify = v
      },
      onSecondaryClick: () => {
        inu.ui.openMenu([
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
    inu.ui.separator('long-tap the plain toggle for an openMenu() demo'),

    inu.ui.header('Selects'),
    inu.ui.select({
      text: 'Mode (menu)',
      items: ['Off', 'Normal', 'Aggressive'],
      selected: state.mode,
      onChange: (i) => {
        state.mode = i
        inu.kv.set('mode', String(i))
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
      onChange: (v) => {
        state.speed = v
        inu.kv.set('speed', String(v))
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
          inu.kv.set('name', name)
          mainPage.invalidate()
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
          const spin = () => (++i < 100000 ? Promise.resolve().then(spin) : resolve())
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
        inu.ui.openPage(inu.ui.settingsPage({
          title: 'Transient #' + stamp,
          transient: true,
          items: () => [inu.ui.separator('this page def is freed once you navigate back')],
          onClose: () => console.log('transient #' + stamp + ' closed + disposed'),
        }))
      },
    }),
    inu.ui.button({
      text: 'Reset everything',
      danger: true,
      onClick: () => {
        inu.ui.openMenu([
          {
            text: 'Yes, reset',
            danger: true,
            onClick: () => {
              inu.kv.clear()
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
    onClick: () => {
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
console.log('ui-test loaded; open plugin settings from the plugins list')
