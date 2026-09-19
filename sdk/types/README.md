# @inugram/plugin-types

TypeScript typings for the [Inugram](https://github.com/teidesu/inugram) plugin api.

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

That brings in every global the engine provides: `inu`, `console`, `atob`, `performance` and the
rest. There is nothing to import.

For plugins written in plain JavaScript, extend `@inugram/plugin-types/tsconfig.js.json` instead: it
turns on `checkJs` and relaxes the annotations a `.js` plugin would otherwise need, while keeping
every check that can catch a member the contract does not declare.

## What is in here

| | |
| --- | --- |
| `common.d.ts` | the contract: `inu.*`, lifecycle, grants, limits |
| `canvas.d.ts` | `inu.canvas`, the rasterizer |
| `android.d.ts`, `android.jvm.d.ts`, `android.xposed.d.ts`, `android.notification-center.d.ts` | the unsafe android surfaces |
| `android.tl.d.ts` | every TL class and method, generated from the app's own sources |
| `grants.json` | the grant catalogue: what each permission accepts, and what it is called |
| `tl-names.txt` | the rpc method and update vocabularies `inu check` validates scopes against |

`common.d.ts` is normative: where it and the app disagree, one of the two is a bug.

The version of this package tracks the app release it was generated from, so the typings, the TL
layer and the grant catalogue always agree with each other.

## License

MIT
