# @inugram/cli

CLI to develop and build [Inugram](https://github.com/teidesu/inugram) plugins.
Guides on grants, routines, interception and more are in the [plugin docs](https://github.com/teidesu/inugram/tree/main/sdk/docs).

```bash
pnpm dlx @inugram/cli init my-plugins
cd my-plugins
pnpm install
```

## Why?

Each plugin is one `.inu.js` script with an `==InuPlugin==` metadata header, run in its own QuickJS engine.

The CLI simplifies bundling TypeScript, generates the header from your config automatically,
and validates grants before you install the plugin.

## Commands

| | |
| --- | --- |
| `inu init [dir]` | scaffold a project |
| `inu build [names...]` | bundle every plugin, or the ones named |
| `inu check [names...]` | validate manifests and routines and typecheck, without building (`--no-typecheck` to skip types) |
| `inu dev [names...]` | build, push to a connected device and reload on every save |
| `inu push [names...]` | build, push and reload once, print the log for `--logs <seconds>` (default 3); exits 1 on a build, install or load failure, or any error line |
| `inu eval <name> [code]` | run code inside a running dev plugin and print the result; `-f <file>` or `-` for stdin, `--timeout <seconds>` (default 10) |
| `inu eval -x [code]` | the same in a scratch plugin with every grant, no project needed; `--reset` restarts it and wipes its storage |
| `inu list` | what the device has installed |
| `inu memory [name]` | what running plugins hold in memory, or just this project's `name`; `-w <seconds>` samples again until interrupted, printing what changed |
| `inu remove <file>` | uninstall a dev plugin by file name |

Run any of them with `--help` for its options.

## Configuration

`inu.config.ts`, in the package root:

```ts
import { defineConfig } from '@inugram/cli'

export default defineConfig({
  outDir: 'dist',
  // defaults for every plugin's manifest
  manifest: {
    author: '@you',
  },
  plugins: {
    dog: 'src/dog/index.ts',
    // the long form, for build options or manifest keys kept in the config
    cat: {
      entry: 'src/cat/index.ts',
      manifest: { version: '2.0.0' },
    },
  },
})
```

Each key defines a plugin, `inu build dog` builds into `dist/dog.inu.js`.

### Manifest

A plugin's manifest lives in its entry:

```ts
export const manifest = {
  // the only required field
  name: 'Dog',
  // unique identifier of the plugin, used to preserve state and data across plugin updates
  // it is compared as-is and can be almost anything, but we recommend using reverse domain notation
  id: 'com.github.you.dog',
  // author display name for the plugin list page, `@username` is shown as a Telegram username
  author: '@you',
  // user-visible version of the plugin
  version: '1.0.0',
  // description of the plugin. either a string, or a lang->text mapping.
  // supports telegram markdown: **bold**, __italic__, [text](url), etc.
  description: { en: 'Shows a dog', ru: 'показывает СОБАКУ' },
  // icon for the plugin. can be one of:
  // - `inu://{name}`, where `{name}` is one of the pre-defined icons from `inu.icons.common`
  // - `tg://emoji?id={id}`, where `{id}` is a Telegram custom emoji ID
  // - `tg://addstickers?set={slug}`, where `{slug}` is a stickerset slug. The thumb of the set is used
  // - `tg://addstickers?set={slug}&idx={idx}`, where `{slug}` is a stickerset slug,
  //   and `{idx}` is the 0-based index of the sticker to use from the pack
  // - `tg://addstickers?set={slug}&id={id}`, where `{slug}` is a stickerset slug,
  //   and `{id}` is the sticker document ID
  icon: 'inu://mute',
  // list of grants the plugin requests
  grants: ['onUpdate(new_message)', 'account.read(peers)'],
} satisfies inu.Manifest
```

The CLI reads it without running the entry, so it must be a plain literal: no variables, spreads,
calls or interpolation. Anything computed belongs in `inu.config.ts`, which runs in Node.

Keys in the entry override the plugin's `manifest` in the config, which overrides the config's
top-level `manifest`. A key replaces the one below it whole, so a `grants` list is never combined;
only `extra` is merged key by key. Any layer may be absent, as long as the merged manifest has a
`name`.

`inu.Manifest` comes from `@inugram/cli/manifest.d.ts`, listed in the template's `tsconfig.json`:

```json
{ "compilerOptions": { "types": ["@inugram/plugin-types", "@inugram/cli/manifest.d.ts"] } }
```

The installed `@inugram/plugin-types` version supplies `@plugin-api` and `@platform`.

Plugins in one repo can import shared source.

### Custom build options

Use `esbuild(options, plugin)` to change build options. Changes to the format, target, or banner
can make the output incompatible with the app.

For example, to minify the output:

```ts
export default defineConfig({
  plugins: { /* ... */ },
  esbuild: (options) => {
    options.minify = true
  },
})
```

> Note: it is highly encouraged for plugins to be transparent, thus minifying the plugin code is
> considered a bad practice, and will lead to a warning banner in the app

## Developer mode

`inu dev` connects to the app over `adb`:

- Run the app and enable developer mode in Settings > Plugins. Dev installs skip the permission sheet.
- The default package is `desu.inugram`. Use `--app desu.inugram.beta` for debug builds.
- Use `-s <serial>` to select a device when several are connected.

## Typechecking

The scaffolded project runs `tsc --noEmit` over `src`. The api is declared by
`@inugram/plugin-types`, which the template's `tsconfig.json` extends; `inu.config.ts` is not in
that project, because it is node code rather than plugin code.

## License

This CLI is licensed under MIT
