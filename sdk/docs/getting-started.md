# Getting started

A plugin is one `.inu.js` file - plain JS + userscript-style metadata header.
You *can* write it by hand, but we highly recommend using `@inugram/cli`, which does all the tedious parts for you.

## Create a project

```bash
pnpm dlx @inugram/cli init my-plugins
cd my-plugins
pnpm install
```

This scaffolds a project in `./my-plugins` with a starter plugin and a basic config.

## Configure plugins

```ts
// inu.config.ts
import { defineConfig } from '@inugram/cli'

export default defineConfig({
  outDir: 'dist',
  plugins: {
    dog: {
      entry: 'src/dog/index.ts',
      manifest: {
        // the only required field
        name: 'Dog',
        // unique identifier of the plugin, used to preserve state and data across plugin updates
        // it is compared as-is and can be almost anything, but we recommend using reverse domain notation
        // !! avoid changing this once you publish, this identifies the plugin across updates
        id: 'com.github.you.dog',
        // author display name for the plugin list page, `@username` is shown as a Telegram username
        author: '@monk',
        // user-visible version of the plugin
        version: '1.0.0',
        // description of the plugin. either a string, or a lang->text mapping
        description: { en: 'Shows a dog', ru: 'показывает СОБАКУ' },
        // icon for the plugin. can be one of:
        // - `inu://{name}`, where `{name}` is one of the pre-defined icons from `inu.icons.common`
        // - `tg://emoji?id={id}`, where `{id}` is a Telegram custom emoji ID
        // - `tg://addstickers?set={slug}`, where `{slug}` is a stickerset slug. The thumb of the set is used
        // - `tg://addstickers?set={slug}&idx={idx}`, where `{slug}` is a stickerset slug,
        //   and `{idx}` is the 0-based index of the sticker to use from the pack
        // - `tg://addstickers?set={slug}&id={idd}`, where `{slug}` is a stickerset slug,
        //   and `{id}` is the sticker document ID
        icon: 'inu://mute',
        // list of grants the plugin requests
        grants: ['onUpdate(new_message)', 'account.read(peers)'],
      },
    },
  },
})
```

### The header

The CLI turns the manifest from the config into a userscript-style header, something like this:

```js
// ==InuPlugin==
// @name         Dog
// @id           com.github.you.dog
// @author       @you
// @version      1.0.0
// @description  Shows a dog
// @description:ru показывает собаку
// @icon         inu://mute
// @grant        onUpdate(new_message)
// @grant        account.read(peers)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==
```

`@plugin-api` is currently purely for future-proofing and is always `1`.

### Build options

`esbuild` on a plugin or on the whole config lets you change esbuild options:

```ts
export default defineConfig({
  plugins: { /* ... */ },
  esbuild: (options, plugin) => {
    options.define = { DEBUG: 'false' }
  },
})
```

## Developer mode

To simplify the development, Inugram has a "Developer mode", which enables
the use of the `inu dev` sub-command, which automatically pushes and reloads
the plugin on every save, allowing for fast iteration.

1. Launch the app and turn on "Developer mode" in Settings > Plugins > ⋮.
2. Run `inu dev [name]`
3. Start building!

A few notes:

- The CLI currently talks to the app over `adb`. This means that your phone must have "USB debugging" enabled,
  and your computer must be connected to the phone over `adb`. To check see `adb devices`.
  - use `-s <serial>` to select a device when several are connected.
- The app must already be running. The CLI warns if plugins are disabled or in safe mode.
- A pushed plugin skips the permission sheet. Users who install the built file see it.
- Logs from the plugin are forwarded to your terminal (`--no-logs` to turn off)

## Best practices

- Avoid `minify: true`, as this makes it a lot harder for users to audit the plugin's code.
- Prefer multiple small plugins doing one thing ,over one combined plugin doing many things with toggles
  in its settings. Small plugins are easier to audit and manage for the end users, and can also be more performant as a result.
- Use [routines](./routines.md) to reduce the number of JNI crossings. Do not use it for CPU-heavy tasks,
  QuickJS is faster than our custom interpreter, its main point is reducing the number of JNI back and forth
  on hot paths like xposed hooks.
- In message interceptors, instead of `drop`-ping the message and sending a new one separately,
  just replace the content in the passed context.

## Commands

| Command | What it does |
| --- | --- |
| `inu build [names...]` | Bundle every plugin, or the named ones. `-w` watches |
| `inu check [names...]` | Validate manifests and routines, then typecheck, without writing files. Use `--no-typecheck` to skips `tsc` type checking |
| `inu dev [names...]` | Run the watcher |
| `inu list` | List installed plugins on the connected device |
| `inu remove <file>` | Uninstall a dev plugin |
| `inu verify <files...>` | Check that each compiled routine in a built file matches its recorded source |

### Typechecking

The project's `tsconfig.json` extends `@inugram/plugin-types/tsconfig.json`, which declares every
engine global (`inu`, `console`, `fetch`, `localStorage` and so on). You need no imports for them.

This is neither a browser nor Node. There is no DOM, no `process`, no `Buffer` and no Node
built-ins, so do not add DOM or Node types to plugin code. An npm dependency you bundle must work
without them.

For a JavaScript plugin, extend `@inugram/plugin-types/tsconfig.js.json` instead. It turns on
`checkJs` with looser annotation rules but still flags unknown API members.
