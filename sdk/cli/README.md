# @inugram/cli

CLI to develop and build [Inugram](https://github.com/teidesu/inugram) plugins.

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
| `inu check [names...]` | validate manifests without building |
| `inu dev [names...]` | build, push to a connected device and reload on every save |
| `inu list` | what the device has installed |
| `inu remove <file>` | uninstall a dev plugin by file name |

Run any of them with `--help` for its options.

## Configuration

`inu.config.ts`, in the package root:

```ts
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
        id: 'com.github.you.dog',
        // author display name for the plugin list page, `@username` is shown as a Telegram username
        author: '@you',
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

Each key defines a plugin, `inu build dog` builds into `dist/dog.inu.js`.
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
