# UI

Most of the time, plugins don't need to manually manage Android views directly.
Instead, Inugram provides a declarative UI layer, keeping the plugin UI consistent
with the rest of the app

Most of these don't require any grants.

## Elements and icons are values

There are three main building blocks for the UI:
- `UIElement` - an opaque descriptor of a widget, such as a button or a switch
- `UIIcon` - an opaque descriptor of an icon, such as a built-in one or a custom SVG
- `UIPage` - an actual custom page

Most of these can be built with the `inu.ui.*` and `inu.icons.*` functions respectively.

## Settings pages

A settings page is a title plus an `items` function that returns the rows to show:

```ts
let enabled = false
let mode = 0

const page /* UIPage */ = inu.ui.settingsPage({
  title: 'Dog',
  items: () => [
    inu.ui.header('General'), // UIElement
    inu.ui.check({
      id: 'enabled',
      text: 'Show the dog',
      icon: inu.icons.common('check'), // UIIcon
      checked: enabled,
      onChange: (checked) => {
        enabled = checked
      },
    }), // UIElement
    inu.ui.select({
      id: 'mode',
      text: 'Mode',
      items: ['Small', 'Large'],
      selected: mode,
      onChange: (index) => {
        mode = index
      },
    }), // UIElement
    inu.ui.separator('Changes apply right away'), // UIElement
  ],
})

inu.registerSettings(page)
```

### How rendering works

The pages are declarative - `items()` function, returning `UIElement[]`.
You can think of it as a very simplified React.

The `items()` function is called to build the UI when:
- the page opens
- the user interacts with the UI
- you explicitly call `page.invalidate()`

Since the UI automatically invalidates after interactions, you only really need `invalidate()`
when the state changes from outside the page, for example after a timer or a network request.

**Controls do not hold their own state.** Think of it as "controlled" inputs in React.
For example, a switch value shows whatever `checked` the last `items()` returned.
If `onChange` does not store the new value, the switch flips back on the next render.

> Note: a subtle exception to this is `inu.ui.slider`: re-running `items()` on every change
> would be too expensive, so the new value is only reported on release.

If `items()` throws, the error goes to the plugin log and the page keeps its last good render.

### Row identity

Every row in the page has an `id`, used to identify it across renders.
Think of it as a `key` in React.

In simple cases, you don't really need row IDs, and it is automatically derived from other parameters
(namely, row type + text + occurrence index). However, when you have dynamic rows (e.g. a section only visible when a switch is on), it is highly recommended to use explicit `id`-s for them to properly match.

### Available elements

| Function | Description |
| --- | --- |
| `header(text)` | Section title. |
| `button` | A simple button row, optionally with a subtitle, icon and value (shown on the trailing side) |
| `check` | A `button` with a switch on the trailing side |
| `select` | A `button` with `<select>` semantics - on tap, a selection menu (or a dialog) is shown |
| `slider` | A slider with an optional title, value formatter and reset button. As mentioned above, `onChange` is only fired on release, to avoid too many re-renders |
| `separator(text?)` | A divider with optional footer text. |

### Menus

Every callback on a row gets a `UIAnchor` as its last argument, allowing you to open a popup menu
next to the row, using `anchor.openMenu(items)`:

```ts
// contrived example, in real code you would use `inu.ui.select`
inu.ui.button({
  id: 'size',
  text: 'Size',
  onClick: (anchor) => {
    anchor.openMenu([
      { text: 'Small', onClick: () => console.log('small') },
      { text: 'Large', onClick: () => console.log('large') },
    ])
  },
})
```

### Opening pages

`inu.ui.openPage(page)` opens one of your pages. `openPage` also takes a stock target:

```ts
inu.ui.openPage({ type: 'chat', dialogId: 777000 })
inu.ui.openPage({ type: 'settings' })
```

- `account` defaults to the active account.
- `chat` and `profile` need the dialog to be known to the app, or they throw `not-found`.
- Secret chats are refused with `forbidden`.
- `topicId` is only valid for `chat`.


### Page lifetime

A page lives until you call `page.dispose()`, the reference is GC-ed, or the plugin unloads.
As long as the page is live, you can open it using `inu.ui.openPage`.

For pages you rebuild on each open, pass `transient: true`.
This is pretty much a shorthand for `onClose: () => page.dispose()`.

`registerSettings(page)` makes `page` the plugin's settings entry in the plugin list,
showing a "Settings" button next to the plugin's name in the app's list.


## Icons

Many of the UI rows accept an `icon` parameter, which is an `UIIcon`. You can get one using:

| Function | Description |
| --- | --- | --- |
| `common(name)` | A built-in icon from a fixed list |
| `animation(name)` | A built-in animated icon |
| `customEmoji(id, options?)` | A Telegram custom emoji by its ID |
| `sticker(options)` | A Telegram sticker from a specified set |
| `svg(source)` | Custom SVG |

Additionally, you can use some of the less-safe APIs:

| Function | Description |
| --- | --- |
| `resourceIcon(id)` | An icon from `R.drawable.{name}` |
| `rawAnimation(name)` | A Lottie animation from `R.raw.{name}` |
| `drawableIcon(name, options?)` | A custom "icon" backed by a `Drawable` (needs `unsafe.jvm`) |

## Actions

Using actions APIs, you can expand the app's built-in menus with custom actions:

| Function | Where | Context |
| --- | --- | --- |
| `registerGlobalAction` | Drawer or main activity's ⋮ |
| `registerChatAction` | Chat activity's ⋮ |
| `registerProfileAction` | Profile activity's ⋮ |
| `registerMessageAction` | Message context menu |
| `registerMessageEditorAction` | Long tap on the send button |

```ts
inu.registerMessageAction({
  id: 'count-words',
  text: 'Count words',
  icon: inu.icons.common('info'),
  // bubble = on bubble tap
  // selection = multiple messages selected -> ⋮ menu
  placements: ['bubble', 'selection'],
  callback: (ctx) => {
    const words = ctx.messages.reduce((n, m) => n + m.text.split(/\s+/).length, 0)
    inu.ui.toast(`${words} words`)
  },
})
```

### Static and dynamic rows

`text` and `icon` can be plain values or getters. `visible` is always a getter.

Prefer static `text` and `icon` values, as they are cheaper to compute,
and if you can't - keep the getters fast and free of I/O.

### Other rules

- `id` must be stable. The ID is used to actually dispatch the event, as well as to keep the
  user's hide/pin/order settings for the row.
- At most 8 rows per menu per plugin. Registering past that throws `quota-exceeded`.
- Users can hide, pin and reorder chat and message actions in the app's menu settings. That
  screen calls your `text` and `icon` getters with `null` instead of a context, and never calls
  `visible`. The typings reflect this: those getters take `Ctx | null`.
- Actions never show in secret chats.

## Dialogs

| Function | Decsription |
| --- | --- |
| `toast(text)` | Shows a native Android toast |
| `bulletin(options)` | Shows a customizable bulletin, resolves with `'button'`, `'clicked'` or `'dismissed'` |
| `dialog(options)` | Shows a customizable dialog, resolves with `'positive'`, `'negative'`, `'neutral'` or `'dismissed'` |
| `chooser(options)` | Shows a select-style picked, resolves with the selected index, the list of indices with `multiple`, or `null` |
| `prompt(options)` | Shows a text input dialog, resolves with the entered text, or `null` |

```ts
const answer = await inu.ui.dialog({
  title: 'Delete everything?',
  message: 'This cannot be undone.',
  positive: 'Delete',
  negative: 'Cancel',
})
if (answer === 'positive') {
  // ...
}
```

## Files

There are also a few functions to load/save files from a plugin.

`inu.ui.pickFile()` opens the "open" system file picker. The chosen files are copied into the plugin's
private storage, and returned as one or more `File`-s.

`inu.ui.saveFile(content)` opens a "save as" dialog and resolves with `true` if the user saved.
`content` is a `Blob` or `Uint8Array` of up to 32 MB, or `{ path }`grant.

See [io.md](io.md) for more info on blobs and paths.

## Current screen

`inu.ui.getCurrentScreen()` returns what the user is looking at, and `onScreenChanged` reports
navigation with the whole back stack.
