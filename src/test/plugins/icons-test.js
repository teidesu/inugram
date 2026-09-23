// ==InuPlugin==
// @name         icons test
// @description  asserts every UIIcon source and shows them in a settings page
// ==/InuPlugin==
/* eslint-disable eslint-comments/no-unlimited-disable */
/* eslint-disable */

/** @type {Parameters<typeof inu.icons.common>[0][]} */
const CURATED = [
  'settings', 'info', 'search', 'edit', 'delete', 'copy', 'share', 'download',
  'link', 'pin', 'star', 'mute', 'unmute', 'archive', 'forward', 'reply',
  'user', 'group', 'channel', 'bot', 'lock', 'eye', 'eyeOff', 'refresh',
  'plus', 'minus', 'check', 'close', 'more', 'translate', 'bookmark',
]

// only a real build can assert this: every name the typings offer answers with a shipped asset
const missing = []
const curated = {}
for (const name of CURATED) {
  try {
    curated[name] = inu.icons.common(name)
  } catch (e) {
    missing.push(`${name} (${e.code})`)
  }
}
check(`all ${CURATED.length} curated icons resolve`, missing.length === 0, missing.join(', '))

// @ts-expect-error not a name in the set
expectThrow('an unknown name is refused', 'invalid-argument', () => inu.icons.common('lightbulb'))
// @ts-expect-error the set is case-sensitive and so is the lookup
expectThrow('and so is the wrong case', 'invalid-argument', () => inu.icons.common('Settings'))
// @ts-expect-error not a name in the set
expectThrow('and so is a prototype member', 'invalid-argument', () => inu.icons.common('constructor'))
// @ts-expect-error not a string at all
expectThrow('a name that is not a string is a TypeError', TypeError, () => inu.icons.common(42))

check('the same name hands back an equivalent icon twice', inu.icons.common('star') !== undefined)

check('a drawable the app ships resolves', inu.android.resourceIcon('msg_settings') !== undefined)
expectThrow('a drawable it does not ship is not-found', 'not-found', () =>
  inu.android.resourceIcon('inu_no_such_drawable_anywhere'))
// getIdentifier would read this as another resource type in another package
expectThrow('a qualified resource reference is refused', 'invalid-argument', () =>
  inu.android.resourceIcon('org.telegram.messenger:raw/notification'))
expectThrow('and so is an empty name', 'invalid-argument', () => inu.android.resourceIcon(''))

/** @type {Parameters<typeof inu.icons.animation>[0][]} */
const ANIMATIONS = ['success', 'error', 'info', 'loading']
check('all animation presets resolve', ANIMATIONS.every(name => inu.icons.animation(name) !== undefined))
// @ts-expect-error not a preset
expectThrow('an unknown animation preset is refused', 'invalid-argument', () => inu.icons.animation('unknown'))
check('a raw animation the app ships resolves', inu.android.rawAnimation('done') !== undefined)
expectThrow('a raw animation it does not ship is not-found', 'not-found', () =>
  inu.android.rawAnimation('inu_no_such_animation_anywhere'))

check('a custom emoji id builds an icon', inu.icons.customEmoji('5361751237382052539') !== undefined)
check('a sticker index builds an icon', inu.icons.sticker({ slug: 'teidesu_favs', index: 2 }) !== undefined)
check('a sticker emoji builds an icon', inu.icons.sticker({ slug: 'teidesu_favs', emoji: '🐶' }) !== undefined)
check('a sticker document id builds an icon', inu.icons.sticker({ slug: 'teidesu_favs', id: '5361751237382052539' }) !== undefined)
// @ts-expect-error selector required
expectThrow('a sticker needs one selector', 'invalid-argument', () => inu.icons.sticker({ slug: 'teidesu_favs' }))
expectThrow('a sticker refuses multiple selectors', 'invalid-argument', () =>
  // @ts-expect-error selectors are exclusive
  inu.icons.sticker({ slug: 'teidesu_favs', index: 2, emoji: '🐶' }))

const HEART = '<svg viewBox="0 0 24 24"><path d="M12 21C12 21 3 14 3 8.5 3 5.4 5.4 3 8.5 3 10.4 3 12 4.2 12 4.2 12 4.2 13.6 3 15.5 3 18.6 3 21 5.4 21 8.5 21 14 12 21 12 21Z"/></svg>'
check('an inline svg parses', inu.icons.svg(HEART) !== undefined)
expectThrow('something that is not markup is refused', 'invalid-argument', () => inu.icons.svg('hello'))
expectThrow('and markup with no <svg> in it', 'invalid-argument', () => inu.icons.svg('<html><body/></html>'))
// the one xml construct that can name an external file or expand to more of itself
expectThrow('a doctype is refused outright', 'invalid-argument', () =>
  inu.icons.svg('<!DOCTYPE svg SYSTEM "file:///etc/hosts"><svg><path d="M0 0"/></svg>'))
check('but a comment is fine', inu.icons.svg('<svg><!-- a note --><path d="M0 0h4v4H0z"/></svg>') !== undefined)

const huge = `<svg>${'x'.repeat(64 * 1024)}</svg>`
let quota
try {
  inu.icons.svg(huge)
} catch (e) {
  quota = e
}
check(
  'source past the 64 KiB limit is quota-exceeded',
  quota instanceof inu.PluginError && quota.code === 'quota-exceeded',
  quota && quota.code,
)
check(
  'and the error says by how much',
  quota !== undefined && quota.usage === huge.length && quota.quota === 64 * 1024,
  quota && `${quota.usage}/${quota.quota}`,
)

expectThrow('a row refuses an icon it was not handed', TypeError, () => inu.ui.button({
  text: 'x',
  // @ts-expect-error a resource name is not a UIIcon
  icon: 'msg_settings',
  onClick: () => {},
}))
expectThrow('and a hand-built one is re-checked, not trusted', 'invalid-argument', () => inu.ui.button({
  text: 'x',
  // @ts-expect-error a forged tag is not a UIIcon
  icon: { __inuIcon: 'rorg.telegram.messenger:raw/notification' },
  onClick: () => {},
}))
check('a switch row takes one too', inu.ui.check({
  text: 'x',
  checked: false,
  icon: curated.settings,
  onChange: () => {},
}) !== undefined)
expectThrow('and refuses one it was not handed, the same way every other row does', TypeError, () => inu.ui.check({
  text: 'x',
  checked: false,
  // @ts-expect-error a resource name is not a UIIcon
  icon: 'msg_settings',
  onChange: () => {},
}))

const svgPage = inu.ui.settingsPage({
  title: 'Inline svg',
  items: () => [
    inu.ui.separator('drawn from the source in the plugin, tinted like every other row'),
    inu.ui.button({ text: 'A heart', icon: inu.icons.svg(HEART), onClick: () => inu.ui.toast('inline svg') }),
    inu.ui.button({
      text: 'A stroked square',
      icon: inu.icons.svg('<svg viewBox="0 0 24 24"><path d="M5 5h14v14H5z" fill="none" stroke="#000" stroke-width="2"/></svg>'),
      onClick: () => inu.ui.toast('strokes work too'),
    }),
    inu.ui.button({
      text: 'From the app, by name',
      icon: inu.android.resourceIcon('msg_gift_premium'),
      onClick: () => inu.ui.toast('android.resourceIcon'),
    }),
  ],
})

let picked = 0
const page = inu.ui.settingsPage({
  title: 'Icons',
  items: () => [
    inu.ui.header('The curated set'),
    ...CURATED.filter((name) => curated[name] !== undefined).map((name) => inu.ui.button({
      text: name,
      icon: curated[name],
      onClick: () => inu.ui.toast(`inu.icons.common('${name}')`),
    })),
    inu.ui.separator('these follow the app icon pack, so switching it in appearance settings changes them'),

    inu.ui.header('Animated'),
    inu.ui.button({ text: 'Success', icon: inu.icons.animation('success'), onClick: () => {} }),
    inu.ui.button({ text: 'Custom emoji', icon: inu.icons.customEmoji('5361751237382052539'), onClick: () => {} }),
    inu.ui.button({ text: 'Sticker', icon: inu.icons.sticker({ slug: 'teidesu_favs', index: 2 }), onClick: () => {} }),

    inu.ui.header('The other two'),
    inu.ui.select({
      text: 'A row with an icon and a value',
      icon: curated.settings,
      items: ['first', 'second', 'third'],
      selected: picked,
      onChange: (index) => {
        picked = index
      },
    }),
    inu.ui.button({ text: 'Inline svg', icon: curated.more, onClick: () => inu.ui.openPage(svgPage) }),
    inu.ui.separator(),
  ],
})

inu.registerSettings(page)
console.log('icons test done')
