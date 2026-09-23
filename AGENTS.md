# Inugram agent guide

## Repository

Inugram is a **patchset**, not a fork. `worktree/` is stock Telegram with stgit
patches applied. Fork sources are symlinked into it; `patches/` and `series` are
exports, never the source of truth.

`src/` is organized by role:

| Directory | Purpose |
| --- | --- |
| `fork/`, `fork-app/` | Main Kotlin code and app-module code |
| `core/` | JVM-testable code |
| `native/` | Rust plugin engine |
| `test/` | Device tests, assets, and shared JS test plugins |
| `res/`, `profile/` | Resources and ART baseline profile |
| `vendor/` | Copied third-party code |

`sdk/` is the published half: `sdk/types` is `@inugram/plugin-types` (the api
typings and the grant catalogue) and `sdk/cli` is `@inugram/cli` (the bundler,
manifest generator and dev server plugin authors use). Neither is patched into
the worktree.

Mappings live in `scripts/config.ts` → `forkSyncFiles`. Update `FEATURES.md` when
adding, removing, or meaningfully changing a feature or patch.

## Rules

Explicit user instructions override these defaults.

- Edit stock files in `worktree/`; fork logic belongs in `src/fork`. Never hand-edit
  `patches/*.patch` or `series`.
- Do not run `git` or `stg` unless explicitly asked. Read-only `stg top` and
  `stg show` are allowed. Never run `stg export`; the user exports with
  `pnpm run export`. Authorized stock-history queries run inside `worktree/`.
- No LSP. Verify Android compilation with `pnpm run build-debug`, sparingly.
- Never install, launch, or uninstall the app. Builds and read-only device
  operations are allowed; deployment and device testing are the user's call.
- Gate behavior changes with `InuConfig`: default off must behave like stock.
  Check every call site.
- Check whether stock already provides a requested toggle, including Lite Mode.
  Verify an issue in an unpatched worktree before calling it a patch regression.
- No stock renames or removal of stock imports, except `desu.inugram.*` imports.
- Never edit `TLRPC.java`, stock database schema, or `LAST_DB_VERSION`.
- Debug logs use `android.util.Log.d`, not `FileLog`. Prefer non-`_solar` icons.
- Use `rg` to locate symbols; read small ranges in stock files over 2k lines.

## Stock patches and helpers

Patch names: `group__name` → `patches/<group>/<name>.patch`. Propose a name and
plain-language commit subject for a new patch; do not operate stgit yourself.

| Group | Use |
| --- | --- |
| `bugfix` | Upstream bug fix |
| `feature` | Added capability or customization |
| `debloat` | Hide or disable stock behavior |
| `hooks` | Shared extension point, no effect without consumers |
| `misc` | Build, branding, infrastructure |

These are the only groups; `visual__` and `ui__` are invalid.

- Prefer one controller/data-layer hook over many view hooks. Check
  `patches/hooks/` and existing helpers before adding either.
- Keep stock changes small: wiring, guards, or short inline logic. Extract feature
  logic beyond roughly 5–7 lines into the existing feature's Kotlin helper.
  A patch touching only `src/**` is usually unnecessary.
- Fix a bug specific to a stock class in that class; do not route it through a
  helper just to shrink the patch.
- When replacing behavior, put the guarded fork branch before stock and return.
  When extending behavior, run fork logic after stock. Avoid re-indenting stock
  inside an `if`/`else` wrapper.
- Expose an existing private field/method as `public` before adding a new API.
  Prefix new stock fields, methods, and overloads with `inu_`. Avoid base-class
  changes where an extension point exists.
- Helpers read their own config and reference stock constants directly. Keep one
  helper per feature area under `src/fork/helpers/`; reuse its subpackage.
- `*Helper` coordinates features; `*Config` models configuration;
  `*Utils`/`*Parser`/`*Drawable`/`*Resources` describe concrete roles. No mass renames.
- A one-feature hook stays in that feature's patch. Shared hooks go in `hooks/`,
  call a helper, and must be no-ops with consumers stubbed out. Consolidate when
  three or more patches touch the same extension point.
- `src/fork/InuHooks.kt` dispatches lifecycle events only; feature logic stays in
  helpers. New Java-facing hooks use `@JvmStatic` and a small stock call site.

Common owners: `ChatHelper`, `ProfileHelper`, `PhotoViewerHelper`, `FolderHelper`,
`MainTabsHelper`, `MonetHelper`, `NonIslandHelper`, `InuDatabaseHelper`, `InuUtils`.
Shared menu/lifecycle surfaces are indexed by their filenames in `patches/hooks/`.

When explicitly asked to manage patches, use `stg new`, `stg refresh -p <patch>`,
or `stg float <patch>` then `stg refresh`. `--index` refreshes staged changes only.
Stock paths inside exported patches omit `worktree/`.

Always run `stg` commands from the `worktree/` directory.

## Config, database, and settings

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

- Config keys are snake_case. Use `BoolItem`, `IntItem`, `FloatItem`, `StringItem`,
  or subclass `Item<T>` for other types. `BoolItem.toggle()` is available.
  Preferences are `inugram`, loaded by `InuHooks.init`.
- Java reads `.getValue()`, not Kotlin's `.value`. Use `@JvmField` for config
  wrappers and `@JvmStatic` for methods actually called from Java; otherwise a
  Kotlin `object` is accessed through `.INSTANCE`.
- Fork tables use `inu_*`; versions live in `inu_kv`. Migrate through
  `InuDatabaseHelper`. Hook stock load/save operations instead of changing stock SQL.
- Settings pages extend `SettingsPageActivity` and register in `InuSettingsActivity`.
  Prefer existing pages: Appearance, Chats, Messages, Dialogs, or Behavior.
  Use Annoyances only when the user explicitly asks.
- If a setting really requires restart, call `showRestartBulletin()` from its
  click handler. Reuse existing custom cells and dialog builders.
- Searchable pages declare `@JvmField val PAGE = SearchRegistry.Page(...)` and
  register in `SearchRegistry.pages`. Entry IDs reuse each row's
  `InuUtils.generateId()` constant. Slugs must be globally unique and stable:
  they identify search recents and `tg://settings/inu/<slug>` links.
  Highlight rows with `SettingsPageActivity.withHighlight(itemId)`.
- `LayoutHelper.createLinear`/`createFrame` margins are dp. Use float arguments
  for float-only overloads, including six-argument `createLinear`.

## Plugin engine

`sdk/types/common.d.ts` is the contract; fix code or contract when they disagree. The Rust
engine is `src/native` (`api`, `jni`, grants/limits in `sandbox`, promise machinery in
`runtime.rs`), the host is `src/fork/helpers/plugins`, and `sdk/cli` is the bundler and routine
compiler. Opcodes, wire formats and JNI signatures are handwritten on both sides: change Rust and
Kotlin together, never add a codegen layer. The routine instruction set lives in
`sdk/cli/src/routines/ops.ts`, `PluginJvmRoutine` and `private/routines-spec.md`, kept in step;
refresh `src/test/assets/routines.json` with `pnpm --filter @inugram/cli test --update`. Grants are
added only in `sdk/types/grants.json` (`pnpm run generate-grants`); TL tables and typings come
from `pnpm run generate-tl`, rerun after every rebase and never hand-edited.

Engine work runs on `EngineDispatch.scheduler`; caller-thread JVM/Xposed callbacks take the
serialized engine lease, and no engine `Rc`/`Persistent` state may escape it (keep rquickjs
`parallel`). Grant checks fail closed. Runtime work carries its `PluginSession`, never an
engine looked up through the plugin. Install ids key all per-plugin storage, and uninstall wipes
it. TL handles are per-session: reject forged, expired and read-only writes, and a response
crossing queues needs `disableFree` plus exactly one later free. Plugin-originated sends bypass
interceptors; interception runs after local send side effects, so a drop must unwind all of them.
Host code logs through `PluginLog`, never `Log`.

## Checks

Run checks relevant to the change; no build for documentation-only edits.

| Change | Command |
| --- | --- |
| Android code | `pnpm run build-debug` |
| Plugin contract/bridge | `pnpm run typecheck-plugins` |
| Rust engine | `cd src/native && cargo check` / `cargo test` |
| JVM core | `cd worktree && ./gradlew :InuCore:test` |
| Device tests, user-run | `cd worktree && ./gradlew :TMessagesProj:connectedDebugAndroidTest` |
| Plugin SDK | `pnpm run typecheck-sdk` / `pnpm run build-sdk` |
| Routine compiler | `pnpm run test-sdk` |

- Rust tests live in adjacent `*_tests.rs`, included with `#[path]`; no inline modules.
- `build.rs` compiles JS preludes to little-endian bytecode using the exact bundled
  QuickJS version. Do not use `include_str!` for preludes.
- A test asserts behavior only. Never read another source file - kotlin, rust, js,
  `.d.ts` or markdown - to check what it declares, spells or documents, and never
  assert the contents of something this repo generates (`GrantCatalog`,
  `GrantPresentations`, `TlTables`, `TlNames`, `TlFlags`, `TlInt53`): drift there is
  one more place to edit, not a bug anything can catch. Test the code that reads a
  generated table, not the table. Do not cover a 5-10 line helper at all. A test owns
  its fixtures - inline them, a table is not worth a file - and running a JS oracle
  is fine.
- Keep the shared JS test plugins. A fake must not supply the fact a test asserts.
  Both loaders prepend `src/test/plugins/test-prelude.js`; keep `test-prelude.d.ts` in step.
- Every kotlin test method is named in snake_case, a sentence rather than a label. A
  name already in snake_case keeps a symbol it embeds spelled as the symbol is
  (`a_server_error_reaches_the_plugin_as_an_RpcError`).
- Device tests use fresh install IDs and the queue recorders `resetBridge` installs.
  Wipe fixed-name stores. JNI failures can abort the process:
  clear exceptions, take networking offline, and initialize native callback inputs.

## Resources and themes

- Fork strings: `src/res/values/strings_inu.xml`, keys prefixed `Inu`; explanatory
  strings use the `Info` suffix. Read with `LocaleController.getString(R.string.InuXxx)`.
- Assets live in `src/res/drawable`, `drawable-xxhdpi`, and `assets`. Register new
  directories in `forkSyncFiles`. Tabler selection lives in `ICON_SELECTION`;
  `pnpm run setup` generates `inu_tabler_*`. Removing a selection does not remove
  its generated file; delete that file explicitly.
- Reuse stock eye icons: `msg_message` and `menu_hide_gift` (slashed).
- Monet assets are `src/res/assets/monet_{light,dark,amoled}.attheme`, resolved by
  `MonetHelper.getColor`. Values accept palette tones, semantic/custom names, or ints.
  Modifiers: `a` alpha %, `s` blend to white %, `l` blend to black %, `t` HCT tone,
  `c` relative chroma % (0–400); combine as `(t=90,c=75)`.
- Debug theme reload: `pnpm run push-theme [light|dark|amoled] [--watch] [--clear]
  [-s <serial>]`. Requires a running debug app; sends `desu.inugram.RELOAD_THEME`.

Keep this guide to durable rules, ownership boundaries, and commands. Update it
when those change; keep implementation walkthroughs and benchmark history out.
