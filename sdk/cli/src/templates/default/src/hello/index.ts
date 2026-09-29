import { readFlag, writeFlag } from '../shared/storage.js'

const GREET_KEY = 'greet'

inu.registerSettings(inu.ui.settingsPage({
  title: 'Hello',
  items: () => [
    inu.ui.header('Hello'),
    inu.ui.check({
      text: 'Say hello on load',
      checked: readFlag(GREET_KEY, true),
      onChange: (checked) => {
        writeFlag(GREET_KEY, checked)
      },
    }),
  ],
}))

if (readFlag(GREET_KEY, true)) {
  console.log('hello from inugram', inu.info().appVersion)
}
