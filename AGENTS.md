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
the worktree; `sdk/types/*.d.ts` is synced in as a device-test asset only.

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

`sdk/types/common.d.ts` is the contract. Fix code or contract when they disagree.
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
- `PluginManifest.id` is `@id` verbatim, or a slug derived from `@author` and
  `@name` when there is none. It decides only what an install replaces, never
  storage. `@inugram/cli` writes the same derivation, so keep the two in step.
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
- A routine is bytecode, not a builder: `@inugram/cli` compiles the body, and
  `PluginJvmRoutine` verifies and runs it. Instruction `i` writes register `i`,
  operands name registers below `i`, and `loop` is the only backward jump, so a
  program without one runs each instruction at most once and pays nothing for the
  clock. Safety is the verifier's, never the compiler's. Keep the instruction
  vocabulary in `sdk/cli/src/routines/ops.ts` and `PluginJvmRoutine` in step, and
  keep `private/routines-spec.md` the description of both. Captures cross one wire
  each, arrays flattened under a `layout` the host rebuilds them from.
  `sdk/cli/test/verifier.ts` mirrors the host verifier and `routines.test.ts` compiles
  a battery of bodies against it. `routines-runtime.test.ts` pins compiler-generated
  `src/test/assets/routines.json`; `PluginJvmRoutineTest` executes those programs on
  device and checks results and side-effect order. Update that fixture with
  `pnpm --filter @inugram/cli test --update` after reviewing compiler changes.
- A receiver and an argument each cost a bridge check, so the compiler reads them
  where the body first reads them, not in a prologue: a filter that turns a call
  away never pays for what it does not reach. Input validation can throw: never
  hoist it across guards, loops, or exception regions. `src/routines/flow.ts` reuses
  only reads already completed on every incoming path; hook argument reads stay
  live across writes. It also checks all register reads, because the host cannot:
  a skipped register reads as `null` rather than being refused, so a lowering bug
  of that shape is silent. It runs over every program the compiler emits.
  `src/routines/captures.ts` is the other check the compiler cannot make from one body:
  what a capture resolves to is in the file around it, so the esbuild hook refuses a
  global, a function or class declaration, and a binding something assigns again, whose
  value is taken once and would otherwise run stale. It decides whether a file builds,
  never what the bytecode is, so `inu verify` does not repeat it.
  Temporaries for `&&`, `?:` and `?.` are pooled; slots bound what a run allocates,
  not how many expressions a body may hold. `PluginJvmRoutineBenchTest` measures
  execution, load and that placement, and `PluginJvmRoutineProfileTest` prices each
  instruction on its own by compiling one copy and sixty-five, so a change here is
  measured, not guessed at.
- The interpreter takes a fast path where the general one would spend thirty type
  checks to reach the same answer: two `Int`s comparing, two `Int`s in arithmetic,
  an `Int` index. Each must be provably the answer the general path gives, not a
  cheaper approximation of it, and the general path stays for everything else.
- `unsafe.jvm`/`unsafe.xposed` are unscoped. Preserve engine-package guards and
  the primitive-box-class hook refusal. Busy/reentrant Xposed phases bypass.
  Share physical hooks across sessions; remove only the last registration.
  Never hold the hook registry lock while invoking callbacks. Recursion guards
  cover callback phases, not the original method or remaining chain.
- Xposed hook values cross as java objects in one array the host also sizes, minted
  only when a callback reads one. The engine borrows those references for the phase
  rather than holding them, so a hook context reads the call it was given and throws
  `handle-expired` afterwards, and an after phase is handed the invocation again with
  the result last. A site the host knows has no JS `before` dispatches once, after the
  original; `=` keeps an argument the hook left alone, with its identity and boxed
  type, and a null answer keeps the outcome, so a hook that changes nothing allocates
  nothing on either side. A JS hook may carry a `filter`: an `inu.jvm.routine` the host
  runs on the hooked thread, reading the call through its parameters, whose falsy verdict
  skips that hook's phases without entering the engine. It gates one site, which is one
  registration; a filter that fails answers yes, and a native hook takes none.

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

- `pnpm run generate-tl` writes the gitignored `sdk/types/android.tl.d.ts`,
  `src/core/src/main/resources/tl_tables.txt` (read by `TlTables`) and
  `sdk/types/tl-names.txt` (the rpc/update vocabularies the cli ships). `pnpm run setup`
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

### Plugin SDK

`sdk/types/grants.json` is the hand-written grant catalogue and the single source
of truth for what a manifest may ask for; `sdk/types/grants.d.ts` is its shape,
shared by the generator and the cli. `pnpm run generate-grants` renders it
into `GrantCatalog.kt`, which is checked in: `pnpm run setup` and
`pnpm run build-debug` regenerate it, so drift shows up as a diff rather than as a
stale build. It also checks that every `title`/`info`/`icon` the catalogue names
resolves, and that its takeover list still matches `common.d.ts`. Add a grant there, never in
Kotlin. The permission sheet's `KNOWN_GRANTS` stays hand-written, because android
resource ids are compile-time ints; `GrantCatalogTest` pins it against the
catalogue.

`sdk/cli` bundles a plugin with esbuild into one classic script: the engine
evaluates a plugin as a global script, so the output is an iife, never a module,
and no source map, because nothing consumes one. Keep the cli's grant check a
mirror of `GrantValidator`, message for message. `inu dev` is the only client of the
dev broadcast, so the protocol has one implementation (`sdk/cli/src/utils/device.ts`).

`src/routines` compiles every `inu.*.routine(function () {})` in an `onLoad` hook,
parsing with `oxc-parser` and replacing the call where it stands. The compiled call
records the body's source next to its bytecode, and `inu verify` recompiles that
source and compares, so the readable half of a published plugin is checkable
offline. That only holds while the compiler stays a pure function of the AST: no
clock, no filesystem, no ordering that depends on anything but the parse.
Source formatting adds a reversible uniform margin; verification dedents it without
trimming original literal whitespace. Capture names describe the source and errors;
the verifier checks positional count, not esbuild-renamed JS bindings.

The cli itself is built by `@fuman/build` through `sdk/cli/vite.config.ts`: its
`exports`/`bin` point at `src/*.ts` and the published `package.json` is generated
into `dist`, so nothing in the tree carries a release version and the sources are
what the repo imports. `src/meta.ts` holds the version (a vite `define`, `dev`
from the sources) and finds `src/templates` beside itself, which the build copies
into `dist`; the build fails if that module ever lands in a shared chunk.
`@inugram/plugin-types` has no build: it is `.d.ts` and data, published as it is.

### Dev server

- `inu dev` writes to `getExternalFilesDir("plugin-dev")` and sends the
  `desu.inugram.plugins.DEV` broadcast. No install review: the receiver must exist
  only after `PLUGINS_DEV_MODE` consent and require sender permission
  `android.permission.DUMP`.
- Register after store load. Read only plain filenames inside the drop directory,
  never source from broadcast extras. Reply synchronously via ordered-broadcast
  `setResultData`. The `dev` flag describes the latest bytes; ordinary updates clear it.
- Plugin host code logs through `PluginLog`, never `Log` directly: `session.log` (tag
  `InuPlugin/<manifest id>`, install id without one) for anything about one plugin, with the
  subsystem as the `[area]`, and `PluginLog.HOST` only for what belongs to none. `inu dev`
  shows the pushed plugins' channels and the host's warnings and errors; its tags live in
  `sdk/cli/src/utils/device.ts`.

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
