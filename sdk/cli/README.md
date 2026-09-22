# @inugram/cli

Build, check and live-reload [Inugram](https://github.com/teidesu/inugram) plugins.

```bash
pnpm dlx @inugram/cli init my-plugins
cd my-plugins
pnpm install
pnpm dev
```

## What it does

A plugin ships as one `.inu.js` file: a plain script with an `==InuPlugin==` metadata header, which
the app evaluates in its own QuickJS engine. This bundles your TypeScript into that file, writes the
header from your config, and validates the permissions it asks for before the device refuses them.

## Commands

| | |
| --- | --- |
| `inu init [dir]` | scaffold a project |
| `inu build [names...]` | bundle every plugin, or the ones named |
| `inu check [names...]` | validate manifests without building |
| `inu dev [names...]` | build, push to a connected device and reload on every save |
| `inu list` | what the device has installed |
| `inu remove <file>` | uninstall a dev plugin by file name |

Run any of them with `--help` for its options.

## Configuration

`inu.config.ts`, beside your `package.json`:

```ts
import { defineConfig } from '@inugram/cli'

export default defineConfig({
  outDir: 'dist',
  plugins: {
    adblock: {
      entry: 'src/adblock/index.ts',
      manifest: {
        id: 'com.github.you.adblock',
        name: 'Adblock',
        author: '@you',
        version: '1.0.0',
        description: { en: 'hides sponsored posts', ru: 'прячет рекламные посты' },
        icon: 'inu://mute',
        grants: ['onUpdate(new_message)', 'account.read(peers)'],
      },
    },
  },
})
```

Each key is one plugin: `inu build adblock` builds that one, and the bundle lands at
`dist/adblock.inu.js`. `@plugin-api` and `@platform` are filled in for you, from the
`@inugram/plugin-types` version the project has installed.

Several plugins in one repo share code by importing it. Each bundles its own copy, which is right:
a plugin runs alone in its own engine, so there is nothing for them to share at runtime.

### Manifest fields

`name` is the only one required. `id` is what decides whether a later file is an *update* of an
installed plugin or a new plugin of its own, compared verbatim and conventionally written as a
reverse domain name, `com.github.you.my-plugin`. Leave it out and one is derived from `author` and
`name`, which ties the plugin's identity to both: rename either half and the next build installs
beside the old plugin instead of over it. With neither an `id` nor an `author` a plugin can never
be updated in place.

`description` takes a string, or a map of language to string whose `en` entry becomes the untagged
`@description` and the rest become `@description:xx`.

`icon` takes `inu://{name}` for one of the app's own glyphs, `tg://emoji?id={id}` for a custom
emoji, or `tg://addstickers?set={slug}` for a sticker out of a set. Remote urls are deliberately not
supported: fetching one would leak the user's IP to whatever host the manifest names.

`grants` are the permissions the plugin asks for, e.g. `account.read(peers)`. `inu check` holds them
against the same catalogue the app validates against, so a scope the device would refuse fails at
build time instead.

### Escape hatch

`esbuild(options, plugin)` gets the last word on the build options. The format, target and banner are
what make the output loadable, so overriding those is on you.

Minifying is the same deal, and there is no flag for it. The app shows a plugin's source to whoever
installs it and badges a minified one as obfuscated, so a readable bundle is the default worth
having:

```ts
export default defineConfig({
  plugins: { /* ... */ },
  esbuild: (options) => {
    options.minify = true
  },
})
```

## Devices

`inu dev` talks to a running app over `adb`, which means:

- the app has to be running, and developer mode has to be on in Settings > Plugins
- a plugin installed this way skips the permission sheet, which is why the toggle exists
- it talks to `desu.inugram`. A debug build installs under `desu.inugram.beta`, so point it there
  with `--app desu.inugram.beta`
- `-s <serial>` picks the device when more than one is attached

## Typechecking

The scaffolded project runs `tsc --noEmit` over `src`. The api is declared by
`@inugram/plugin-types`, which the template's `tsconfig.json` extends; `inu.config.ts` is not in
that project, because it is node code rather than plugin code.

## License

MIT
