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
| `plugins/` | Plugin API typings |
| `test/` | Device tests, assets, and shared JS test plugins |
| `res/`, `profile/` | Resources and ART baseline profile |
| `vendor/` | Copied third-party code |

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

## Plugin engine invariants

`src/plugins/common.d.ts` is the contract. Fix code or contract when they disagree.
Keep handwritten opcodes, wire formats, and JNI signatures synchronized between
Rust and Kotlin; do not add a schema/code-generation layer for them.

### Ownership and lifecycle

- Native API code lives in `src/native/src/api`, JNI in `jni`, grants/limits in
  `sandbox`, and shared promise/job machinery in `runtime.rs`. Host code lives in
  `src/fork/helpers/plugins`. Native implementation is Rust; C++ is limited to
  thin LSPlant ABI wrappers in patches. Hot per-pixel loops go in the
  `src/native/yuv` crate, which the release profile builds for speed while the
  engine is built for size.
- `EngineBindings` owns installation order. Build `inu` once in `nativeCreate`.
  `PluginStore` owns installed files; `PluginManager` owns running sessions;
  `PluginRpc` owns RPC chains; `PluginUpdates` owns updates; `TlReflect` owns TL
  reflection. RPC and update dispatch IDs remain separate.
- `PluginSession` captures source, manifest, permissions, engine, TL handles,
  and settings registration. Pass sessions to runtime APIs and queued work.
  Never resolve an old callback ID through `plugin.engine`. `QuickJs` has no
  session back-reference; JNI/resource utilities may take the engine alone.
- Install IDs are minted independently of manifests and survive reload/rename.
  They key storage; uninstall must wipe every per-install store.
- Boot the grant-selected early cohort in `ApplicationLoader.postInitApplication`,
  bounded by `BootCohort.EARLY_BUDGET_MILLIS`; load the rest at first UI.
  `BootGuard` must survive process death during one plugin start, not a whole pass.
- Call `stopDispatching()` before teardown. `onUnload` gets a shared two-second
  cleanup phase; only runnables created for cleanup bypass stopped
  admission. Reload and uninstall wait for teardown. Poll without the engine lease.

### Threads, grants, and handles

- Ordinary work runs on `EngineDispatch.scheduler` (`inuPlugins`). Return app
  responses on `stageQueue`; respect stock UI/storage queue ownership.
- Keep rquickjs `parallel` enabled. Caller-thread JVM/Xposed callbacks acquire the
  serialized engine lease; reject recursion before the runtime lock. No engine
  `Rc`/`Persistent` state may escape the lease. Quiesce callbacks before host cleanup.
- Caller-thread hosts must be stateless, synchronized, or dispatch to their owner
  queue. Keep `CALLER_THREAD_HOSTS` consistent with that rule; void callbacks must
  arrange their own queue handoff. Recheck session/dispatch identity after queue hops.
- Rust calls `PluginBridge`/`PluginListener`. `QuickJs` package and JNI signatures
  are ABI: update both sides when changing them.
- Grants have no `inu.` prefix. Checks fail closed; ignore unknown grant names but
  reject unknown scopes for known grants. Existing API members return `not-granted`
  unless the API itself requires a grant merely to be installed.
- Value wires and nullable error wires differ: error-only results are null on
  success, otherwise a bare message or `P`/`R` wire, never an `E` wire.
- TL handles are per-session. Interceptor handles expire with the dispatch;
  invoke results and observed updates live with the session. App-owned objects and
  observed updates are read-only. Reject forged, expired, and read-only writes. Minting and scope bookkeeping must be atomic
  with scope release; a read already in progress can retain a child until teardown.
- A response crossing queues needs `disableFree` and exactly one later free.
  Release scoped handles after abandoning their continuations, not before.
- JVM global references belong to Rust's `RefTable`; Kotlin uses
  `jvmMint`/`jvmObjectAt`/`jvmRelease` without taking the engine lease. Constructors
  have their own handle kind and expose `newInstance`, not `invoke`.
- Member calls use cached JNI IDs in `jvm/native.rs`; Kotlin resolves members.
  Keep native conversion rules aligned with `PluginJvm.convert` for routines,
  defined classes, and Xposed answers. `new` matches arguments; use
  `getDeclaredConstructor` to select a specific overload.
- `unsafe.jvm`/`unsafe.xposed` are unscoped. Preserve engine-package guards and
  the primitive-box-class hook refusal. Busy/reentrant Xposed phases bypass.
  Share physical hooks across sessions; remove only the last registration.
  Never hold the hook registry lock while invoking callbacks. Recursion guards
  cover callback phases, not the original method or remaining chain.
- An Xposed result reports whether the engine took the argument/result wires,
  including on failure. Untaken wires remain the host's to release.

### Reads, sends, and RPC chains

- `getMessagesCached` sees dialog-list last messages, not the open chat's cache.
  Async reads narrow misses through memory → storage → network and settle once,
  preserving scalar/list result shape. Peer `0` (`D0`) selects the common message
  box for reads: exclude channel messages whose IDs can collide.
- Projections must agree with ordinary field reads. Fully scalar objects may be
  projected whole; other objects carry their type only, with children minted lazily.
  Dispatch views cache nothing. Explicit `fields` projections may omit unsupported
  fields only if lazy reads still work. Paged-operation argument changes must also
  update the cursor index and test host.
- Ordinal TL reads validate the handle's class and use `ORDINAL_FALLBACK` for
  unsupported/oversized values. Keep the fast and ordinary read paths equivalent.
  Cache reflection/flag/filter decisions in `TlReflect.FieldInfo`.
- Pass bytes as byte arrays/buffers. Base64 is reserved for existing fallback,
  JSON-construction, and JVM-routine wire paths.
- Optimistic text/media sends use stock's composer; unsupported cases use the
  direct request path. Both resolve with the server message. Preserve draft
  clearing, upload progress, and retry behavior. Resolve replies to real local
  messages; never substitute empty message stubs. Remove per-send observers on
  settlement or session detach.
- Plugin-originated sends bypass interceptors. The bypass is a lease through
  stock retries, released on response or cancellation, not on first send.
- `PluginSendHold` delays drawing at most 100ms for matching sends, regardless of
  sync/async callback syntax. Quick rejection draws nothing; longer work shows a
  pending bubble. Preserve ordering within each dialog and media morph behavior.
- A chain response may be replaced by outer middleware; a drop/takeover verdict
  may not. Store the verdict on `RpcChain`; app-side verdicts use account + local
  message ID, never error text alone.
- Interception occurs after local send side effects. Audit the whole unwind:
  persisted message, draw, sending maps, local/server draft, composer transition,
  and upload state. `PluginSendMorph` retries the same local message after takeover.
  **Unresolved:** paid-send `StarsController.beforeSendingMessage` bookkeeping has
  no verified unwind. Media may already be uploaded before interception.
- RPC chain budgets are 10s, or 60s with send middleware; exclude real network
  flight time. `RpcChain` owns response/verdict/deadline. `DispatchDeadline` and
  tests use the same scheduler clock. On timeout, abandon deepest stages first,
  release handles last, and fail unless passthrough already replied. Free requests
  on `stageQueue` after queued sends.
- Raw invoke/takeout reuse invoke settlement. Takeout never widens grants; check
  the inner method and takeover restrictions on both sides. Raw calls check the
  constructor ID. Handwritten takeout classes stay outside `TlReflect`; other
  missing constructors belong in `invokeRaw`.

### TL generation and performance

- `pnpm run generate-tl` writes the gitignored `src/plugins/android.tl.d.ts` and
  `src/core/src/main/resources/tl_tables.txt` (read by `TlTables`). `pnpm run setup`
  runs it, but refuses while the stack diverges from `series`, so run it yourself
  after every rebase. Never edit the outputs by hand.
- A long crosses as a js number only where the table marks it (decided per
  constructor), otherwise as a decimal string, on every read path. Writes take either;
  numbers past `MAX_SAFE_INTEGER` are refused. The list is mtcute's, vendored in
  `scripts/data/int53-overrides.json`; refresh it on rebase, not by hand-editing.
- Generate names from constructor IDs/layer dumps and flags from stock
  `serializeToStream`, not the published schema. Pass a class to `TlNames`, not
  its name. Preserve distinct legacy names. Typings exclude `_layerNNN` classes;
  flag tables still cover them. Flags are hidden from plugins; field presence
  and flag bits follow current values (`null`/`0`/`''`/`[]` clear optional fields).
- Preserve tests comparing projections/ordinal reads with real TL objects.
  Benchmark cold and warmed paths on the same device/session. Cache font lookup
  per font wire and invalidate on font load or roster-generation changes.

### Dev server

- `push-plugin.ts` writes to `getExternalFilesDir("plugin-dev")` and sends the
  `desu.inugram.plugins.DEV` broadcast. No install review: the receiver must exist
  only after `PLUGINS_DEV_MODE` consent and require sender permission
  `android.permission.DUMP`.
- Register after store load. Read only plain filenames inside the drop directory,
  never source from broadcast extras. Reply synchronously via ordered-broadcast
  `setResultData`. The `dev` flag describes the latest bytes; ordinary updates clear it.

## Checks

Run checks relevant to the change; no build for documentation-only edits.

| Change | Command |
| --- | --- |
| Android code | `pnpm run build-debug` |
| Plugin contract/bridge | `pnpm run typecheck-plugins` |
| Rust engine | `cd src/native && cargo check` / `cargo test` |
| JVM core | `cd worktree && ./gradlew :InuCore:test` |
| Device tests, user-run | `cd worktree && ./gradlew :TMessagesProj:connectedDebugAndroidTest` |

- Rust tests live in adjacent `*_tests.rs`, included with `#[path]`; no inline modules.
- `build.rs` compiles JS preludes to little-endian bytecode using the exact bundled
  QuickJS version. Do not use `include_str!` for preludes.
- Keep hook/boot source checks and shared JS test plugins. A fake must not supply
  the fact a test asserts.
- Device tests use snake_case names, fresh install IDs, and queue recorders installed
  by `resetBridge`. Wipe fixed-name stores. JNI failures can abort the process:
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
