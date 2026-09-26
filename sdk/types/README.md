# @inugram/plugin-types

TypeScript typings for the [Inugram](https://github.com/teidesu/inugram) plugin API.

Package versions follow app releases, keeping the types, TL layer, and grant catalogue in sync.

```bash
pnpm add -D @inugram/plugin-types
```

`tsconfig.json`:

```json
{
  "extends": "@inugram/plugin-types/tsconfig.json",
  "include": ["src"]
}
```

This adds types for all engine globals, including `inu`, `console`, `atob`, and `performance`.
No imports are needed.

For JavaScript plugins, extend `@inugram/plugin-types/tsconfig.js.json`. It enables `checkJs`
with fewer annotation requirements while still checking for unknown API members.

## What's inside

| | |
| --- | --- |
| `common.d.ts` | the contract: `inu.*`, lifecycle, grants, limits |
| `dom.d.ts` | web API-shaped globals: `console`, timers, URLs, blobs, and `fetch` |
| `canvas.d.ts` | `inu.canvas`, the rasterizer |
| `android.d.ts`, `android.jvm.d.ts`, `android.xposed.d.ts`, `android.notification-center.d.ts` | the unsafe android-specific surfaces |
| `android.tl.d.ts` | every TL class and method, generated from the app's own sources |
| `grants.json` | the grant catalogue: what each permission accepts, and what it is called |
| `tl-names.txt` | the rpc method and update vocabularies `inu check` validates scopes against |

These declarations define the API contract. Any mismatch with the app is a bug in one of them.

## License

This repo is licensed under the MIT license
