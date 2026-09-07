# Inugram Agent Guide

Inugram is a **patchset**, not a fork. `worktree/` is a stock Telegram checkout with
stgit patches applied on top. Fork code lives in `src/fork`/`src/res` (symlinked
into the worktree). `patches/` and `series` are export targets, not source of truth.

`src/` is named by role, never by language: `fork/` (main kotlin) + `fork-app/` (app module),
`core/` (InuCore, jvm-testable), `native/` (rust engine), `plugins/` (plugin contract),
`test/` (`kotlin/` device suite, `assets/` it reads as files, `plugins/` the js oracles the device
and rust suites both run), `vendor/` (copied-in third party), `res/` (resources). Each maps to a
`forkSyncFiles` entry in `scripts/config.ts`.

`FEATURES.md` is the user-facing list of fork features/bugfixes. Keep it in sync —
when adding, removing or meaningfully changing a patch, update `FEATURES.md` in
the same change.

## Golden rules (never violate)

1. **Edit `worktree/` directly.** Never hand-edit `patches/*.patch` or `series` — they regenerate from stgit.
2. **Do not run `stg` or `git` yourself** unless explicitly asked. Read-only `stg top` / `stg show` is fine. NEVER run `stg export`.
3. **Stock patches stay tiny.** Only wiring/hooks/guards. Real logic goes in `src/fork`. A patch touching only `src/**` is usually wrong.
4. **Default off = stock-identical.** Every behavior change gated behind an `InuConfig.*.getValue()` check. Verify every call site is gated.
5. **Check if stock already does it** before implementing a toggle (e.g. Lite Mode often has it). Tell the user, don't silently re-implement.
6. **Confirm bug repro in unpatched worktree** before treating a visual/behavior issue as a patch regression.
7. **No renames in stock. No removing stock imports** (except `desu.inugram.*`).
8. **Prefer data-layer patches over UI-layer** — one hook in a controller beats fifteen hooks in views.
9. **Never touch `TLRPC.java`** — auto-generated, rebasing changes there is hell.
10. **Never touch stock DB schema or `LAST_DB_VERSION`** — fork state goes in `inu_*` tables / `inu_kv` via `InuDatabaseHelper`.
11. **No LSP.** To verify compilation, run `pnpm run build-debug` (outside the sandbox, slow, use sparingly).
12. **Never install/launch the app yourself.** No `adb install`, `adb shell am start`, `adb uninstall`. Building is fine, read-only operations are fine too; deploying and testing is the user's call.
13. **Debug logs use `android.util.Log.d`**, not `FileLog`.
14. **Prefer non-`_solar` icons** when an alternative exists.

> you are allowed to violate them if the user explicitly asks for this

## Patch groups & naming

Format: `group__name` → `patches/<group>/<name>.patch`. Commit subject = plain human sentence (`Allow editing by double tapping a message`).

| group | when |
| --- | --- |
| `bugfix` | fixes an upstream bug |
| `feature` | adds user-facing capability (qol, ui tweak, customization) |
| `debloat` | hides/disables stock behavior behind a toggle |
| `hooks` | thin stock hooks for fork code to attach to; no user-visible change alone |
| `misc` | build, branding, infra |

`debloat` vs `feature`: only *removes/toggles off* stock → `debloat`. Adds new capability → `feature`. `visual__`, `ui__`, etc. are **not** valid groups.

Propose a patch name (and comment) for every newly made patch — don't touch stgit yourself.

## Writing a stock patch

### Minimal wiring pattern

```java
public void doSomething() {
    if (desu.inugram.InuConfig.MY_TOGGLE.getValue()) {
        MyHelper.handle(this);
        return;
    }
    // ...stock code unchanged...
}
```

- Guard goes **before** stock, early-returns when fork takes over.
- For mode-dependent behavior, prefer an `if`/`else` wrapper with **no re-indentation** of the stock branch — keeps rebases trivial.
- When extending behavior rather than replacing it, **run fork logic after** the stock block. Don't rewrite stock.
- When figuring out stock code history/regressions, make sure to run git **inside** the `worktree/` dir. Root dir is just the fork code, it DOES NOT track stock code.

### Exposing stock internals

- `private` field/method needed from fork? Change to `public`. That is the whole patch.
- Adding a new field/method/overload to a stock class? Prefix `inu_` (Java fields too: `inu_addTab`, `inu_internalType`, etc.).
- Prefer exposing over adding. Adding to a base class is especially rebase-fragile — look for an existing extension point first.

### Helper boundary

- <~5–7 lines of logic → **inline** in the patch.
- Bigger → extract to a Kotlin helper.
- Helper reads `InuConfig` itself; don't pass config values as parameters.
- Helper references stock constants directly (make them `public` if needed).
- One helper per feature area (e.g. `FolderHelper` owns icons + DB + layout + drawing).

### Where logic must live

- Bugfix in a specific stock class → write the fix **inline in that Java class**. `EditTextBoldCursor` bugs get fixed in `EditTextBoldCursor.java`. Don't detour through a Kotlin helper just to keep the patch "clean".
- Non-trivial feature logic → Kotlin helper.
- Pure config toggle with no Java wiring → don't write a stock patch at all.

## Commonly touched stock files

Paths under `worktree/TMessagesProj/src/main/java/`. Line counts approximate.
**Files >2k lines: never Read top-to-bottom.** `rg` for the exact symbol, then Read with `offset` + small `limit`.

| file | ~lines | owns |
| --- | ---: | --- |
| `org/telegram/ui/ChatActivity.java` | 46k | chat screen |
| `org/telegram/ui/Cells/ChatMessageCell.java` | 29k | message bubble |
| `org/telegram/ui/PhotoViewer.java` | 24k | photo/video viewer + preview for ChatAttachAlert |
| `org/telegram/messenger/MessagesController.java` | 24k | messages domain state |
| `org/telegram/ui/ProfileActivity.java` | 17k | profile screen |
| `org/telegram/ui/Components/ChatActivityEnterView.java` | 15k | message input — voice, attach, text |
| `org/telegram/ui/DialogsActivity.java` | 14k | main page / dialogs list |
| `org/telegram/ui/Components/SharedMediaLayout.java` | 13k | profile shared-media player |
| `org/telegram/messenger/MediaDataController.java` | 10k | stickers, reactions, recent data |
| `org/telegram/ui/LoginActivity.java` | 10k | login flow |
| `org/telegram/ui/LaunchActivity.java` | 9k | root activity |
| `org/telegram/ui/Components/ChatAttachAlert.java` | 7k | attachments panel |
| `org/telegram/ui/Cells/DialogCell.java` | 6k | single dialog row |
| `org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java` | 5k | attach panel photo grid |
| `org/telegram/messenger/LocaleController.java` | 4.5k | i18n |
| `org/telegram/ui/Components/ReactionsContainerLayout.java` | 2.6k | reactions bar in message menu |
| `org/telegram/ui/Components/FilterTabsView.java` | 2k | folder tabs strip in DialogsActivity |
| `org/telegram/messenger/SharedConfig.java` | 2k | stock prefs |
| `org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java` | 1.9k | inline reaction chips on messages |
| `org/telegram/ui/Components/EditTextBoldCursor.java` | 1.3k | text input base (used by ~every input) |
| `org/telegram/ui/MainTabsActivity.java` | 1k | main bottom tabs |
| `org/telegram/ui/Components/glass/GlassTabView.java` | 0.6k | liquid-glass tab rendering |
| `org/telegram/messenger/LiteMode.java` | 0.4k | perf flag presets |

When adding to a hotspot, check `patches/hooks/` first — it likely already exposes the surface you need.

## `patches/hooks/` — shared extension points

Standalone hook patches expose surfaces (menu builders, callbacks, `public` field promotions, `inu_*` helpers) that multiple features consume. Intentionally **no user-visible effect on their own**.

| patch | what it exposes |
| --- | --- |
| `admin-logs.patch` | hooks inside admin logs activity |
| `app-loader.patch` | custom `ApplicationLoaderImpl` instead of stock; `InuHooks.onAppBoot` at the tail of `postInitApplication` |
| `chat-activity.patch` | various ChatActivity hooks — message menu (`ChatHelper.addMenuItems`/`processMenuOption`), `undoView`, `replyingMessageObject` etc. |
| `icon-replacement.patch` | custom resource loader for icon replacement |
| `internal-web-app.patch` | `WebViewRequestProps.inu_internalType` + `WebAppHelper.getInternalBotName` for internal bot web sheets |
| `loginactivity.patch` | hooks inside LoginActivity |
| `messagescontroller.patch` | access `MessagesController` instances as they're created |
| `notifications-controller.patch` | hooks inside NotificationsController |
| `photo-viewer-menu.patch` | `PhotoViewerHelper.{addMenuItems,updateMenuItems,resetMenuItems,handleMenuClick}` + `inu_getCurrentPhotoFile`; exposes `containerView`, `menuItem`, `showDownloadAlert` |
| `popup-swipeback.patch` | foreground translation + unified touch coords on swipeback popup |
| `profile-menu.patch` | `ProfileHelper.addMenuItems` + `ProfileHelper.handleMenuClick` |
| `send-preview.patch` | `ChatActionsHelper.showSendPreview` (owns the sheet's `show()`, so fork rows can be appended) + `onSendPreviewDismissed` |
| `universal-recycler.patch` | extra features in `UniversalRecyclerView` used by settings pages |

**When to add a `hooks/` patch vs a normal patch:**

- New stock surface that **>1 future patch will wire into** → `hooks/`.
- One-off wiring for a single feature → keep inside the `feature/`/`debloat/` patch.
- **Rule of 3**: if 3+ existing patches touch roughly the same stock surface, consolidate.
- A `hooks/` patch must be functionally a no-op with its consumers stubbed out.

Conventions: expose the minimum, promote `private` → `public` over duplicating data, `inu_` prefix on new fields, entry point is always a call to `desu.inugram.helpers.XxxHelper.*` — never inline logic.

## Helpers

Live in `src/fork/helpers/`. Sub-packages by feature area: `chat/`, `dialogs/`, `menu/`, `translate/`, `search/`, `media/`, `font/`, `update/`, `cloud/`, `security/`, `theme/`, `profile/`, `icons/`, `maps/`, `notifications/`. Cross-cutting / standalone ones stay flat.

Naming (don't mass-rename):
- `*Helper` = feature-coordinator singleton
- `*Config` = `InuConfig.Item` subclass / data model
- `*Utils`/`*Parser`/`*Drawable`/`*Resources` = concrete type or algorithm

Common entry-point helpers: `ChatHelper` (chat features), `ProfileHelper` (profile menu), `PhotoViewerHelper` (photo viewer), `FolderHelper` (folder tabs), `MainTabsHelper` (bottom tabs), `MonetHelper` (theming), `NonIslandHelper` (non-island UI gating), `InuDatabaseHelper` (fork DB), `InuUtils` (id generation etc.).

Before creating a new helper, check whether an existing one owns the area.

## `InuHooks` — central lifecycle bus

`src/fork/InuHooks.kt`. Generic lifecycle dispatch only — feature-specific code goes on its own helper.

Currently exposed (update this table when adding):

| method | called from | purpose |
| --- | --- | --- |
| `init(Context)` | `ApplicationLoader.onCreate` | bootstrap `InuConfig`, fonts, crash reporter, etc. |
| `onAppBoot()` | `ApplicationLoader.postInitApplication` tail | boot the plugins that the headless paths dispatch to, before the app applies anything |
| `onResume(LaunchActivity)` | `LaunchActivity.onResume` | monet refresh, crash sheet |
| `onUpdate(TLObject?, Int)` | update dispatch | fork `LoginHelper` hook |
| `onDeepLink(LaunchActivity, Intent?)` | deeplink handling | passcode + settings deeplinks |
| `onAuthSuccess(Int)` | login flow | clear per-account passcode |
| `onMessagesControllerCreated(MessagesController, Int)` | `MessagesController.<init>` | per-account setup (maps provider; registers the `didReceiveNewMessages` → `onNewMessage` observer) |
| `onNewMessage(TLRPC.Message, Int)` | `didReceiveNewMessages` observer | generic new-message dispatch (all arrival paths incl. difference catch-up); fans out to `UpdateHelper` etc. |
| `syncDoubleTapDelay()` | fork + `init` | propagate `DOUBLE_TAP_DELAY` into stock gesture detectors |
| `syncAnimationSpeed()` | fork + `init` | propagate `ANIMATION_SPEED` into stock animators |
| `syncChatInputRowHeight()` | fork + `init` | propagate classic-ui input row height/padding into `ChatActivityEnterView` statics |
| `getCurrentAppIconLicense()` | About page | current launcher icon's license string |

New hook → `@JvmStatic fun` on `InuHooks`, one-line call site in the patch, **update this table**.

## `InuConfig` pattern

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

- Always `@JvmField` so Java sees a field, not `getHIDE_STORIES()`.
- Types: `BoolItem`, `IntItem`, `FloatItem`, `StringItem`. Subclass `Item<T>` for anything else (enums — see `FoldersDisplayModeItem`, `FormattingPopupConfig`).
- `BoolItem` has `.toggle()`.
- From Java: `InuConfig.HIDE_STORIES.getValue()` — **never `.value`** (`@JvmField` exposes the wrapper, not its inner value).
- Pref key = snake_case of the field name; default is the second arg. SharedPreferences name: `inugram`. Loaded once from `InuHooks.init`.

## Database

- Stock schema and `LAST_DB_VERSION` are off-limits.
- Fork versioning lives in `inu_kv`, managed by `InuDatabaseHelper`.
- Fork tables: `inu_*` prefix, created/migrated in `InuDatabaseHelper.migrate()`.
- Populate fork fields by **hooking** stock load/save calls (see `patches/feature/folders-display-mode.patch`) — don't edit stock SQL.

## Settings UI

- Extend `desu.inugram.ui.settings.SettingsPageActivity` (wraps `UniversalFragment` with edge-to-edge + insets + `showRestartBulletin()`). Register pages in `InuSettingsActivity`.
- Prefer adding to an existing page:
  - `AppearanceSettingsActivity` — general appearance
  - `ChatsSettingsActivity` — chat-related appearance (bubbles, menus)
  - `MessagesSettingsActivity` — message bubble / inline reactions / sticker size
  - `DialogsSettingsActivity` — dialogs list (main page) appearance
  - `AnnoyancesSettingsActivity` — removes annoying stock stuff (only when user explicitly asks)
  - `BehaviorSettingsActivity` — general behavior
- Any toggle needing a restart → call `showRestartBulletin()` in the click handler (verify restart is actually needed).
- Custom cells: `SliderCell`, `ExpandableBoolGroup`, `RadioDialogBuilder`, `StickerSizePreviewMessagesCell`.

### Settings search & deeplinks

- `desu.inugram.SearchRegistry` wires fork pages into stock settings search (`ProfileActivity.SearchAdapter`) and routes `tg://settings/inu/<slug>` deeplinks.
- Each searchable `*SettingsActivity` declares a `@JvmField val PAGE = SearchRegistry.Page(...)` in its companion: page `slug`, title res, icon res, factory, list of `SearchRegistry.Entry(slug, titleRes, itemId)` — one per searchable `UItem`. `itemId` reuses the page's `InuUtils.generateId()` constant (also used as the `UItem.id`).
- Register in `SearchRegistry.pages`. Slugs are persistent identity (deeplinks + recents), globally unique — uniqueness asserted at first access. Renaming a slug is a breaking change.
- Row highlight on open: `SettingsPageActivity.withHighlight(itemId)` + existing `onTransitionAnimationEnd` hook. No extra wiring per page.

## Plugin TL typings

`pnpm run generate-tl-typings` regenerates, from stock's tgnet sources — **run it after every
rebase** (the gitignored typings rebuild themselves; the three committed tables do not):

| output | what |
| --- | --- |
| `src/plugins/android.tl.d.ts` | every TL type as `tl.Raw*` / `tl.Type*`, plus `tl.RpcCallReturn`. header stamps `@layer` + `@appVersion` (read from `TLRPC.java` / `gradle.properties`) so a copy outside the repo still says which build it describes |
| `src/core/.../TlNamesTable.kt` | the wire name of every class whose java name doesn't read as it |
| `src/core/src/main/resources/tl_flags.txt` | which flag word + bit gates each optional field |
| `src/core/src/main/resources/tl_ctor_ids.txt` | canonical name -> every constructor id incl. legacy/layer variants, plus method/update kind markers — backs grant scope validation and the TL api filter |

It parses `TLRPC.java` + `tgnet/tl/**` (`scripts/tl-parser.ts`) and joins the result against stock's
own layer dumps in `TMessagesProj_AppTests/tlscheme/*.json` by constructor id. The three tables the
app ships are committed; `android.tl.d.ts` is not - it is gitignored and regenerated by
`typecheck-plugins` (`--typings-only`, so a check never rewrites a committed table). The tables are
checked against the classes they describe by `TlTablesTest`, on a device, because the subject is
stock's own tree. Never hand-edit them — and never hand-edit `TlNamesTable.kt`/`tl_flags.txt` to "fix" a
wrong name or flag, since the bridge and the typings only agree because one script writes both.

- **Names.** `TlNames.classNameToTlName(cls)` needs the class, not its name — it reads the enclosing
  container. The name is whatever the layer dumps call the constructor, so the table carries both
  namespace and member (`TL_statsGetPollStats` → `stats.getPollStats`). The exception is a legacy
  variant colliding with a live one: `TL_message_old7` is `message` on the wire too, so it keeps its
  derived name and stays distinct from `TL_message`.
- **Flags.** `TlFlags` owns `flags`/`flags2`: they're hidden from plugins, fields whose bit is clear
  are omitted from reads, and assigning a field recomputes its bit from the value (`null`/`0`/`''`/
  `[]` clear it). Layout is read out of each constructor's `serializeToStream`, **not** the schema —
  where stock lags a layer, the bytes it writes are what a round-tripped object must match.
- Only `_layerNNN` classes are dropped from the typings; the flag table still covers them, because
  the bridge can hand a plugin one loaded from local storage.

## Plugin engine invariants

`src/plugins/common.d.ts` is normative. If code differs, fix code or the contract. Run `pnpm run typecheck-plugins` after a change to the contract or bridge. It generates `android.tl.d.ts`, checks the contract and bundled plugins, and checks every JS file separately.

### Layout

- `src/native`: plugin API in `api/`, JNI in `jni/`, grants/limits/registry in `sandbox/`, internal plumbing in `utils/`.
- Native implementation is Rust only. No C/C++ under `src/native`; C++ is limited to thin C ABI wrappers in LSPlant patches.
- `src/fork/helpers/plugins`: host implementation. Its packages mirror native domains.
- Build the `inu` object once in `nativeCreate`; pass it to every `install_*`.
- `EngineBindings` owns install order. Do not move `QuickJs`: its package is part of JNI export names.
- `PluginRpc` owns RPC chains; `PluginUpdates` owns updates. Keep their dispatch IDs separate.
- `TlReflect` owns TL class lookup, fields, and flags. `PluginStore` owns installed files; `PluginManager` owns running engines.
- Rust callbacks go only through `PluginBridge` and its `PluginListener` interfaces. JNI caches methods from `PluginBridge`; do not change its name or listener signatures without updating native code and `jni/tests.rs`.

### Contract and identity

- Grants have no `inu.` prefix. All grant checks go through the single fail-closed gate.
- Unknown grant names are ignored; unknown scopes for known grants fail install.
- Install IDs are minted at install. Do not derive them from a manifest. They key `kv` and `fs`; uninstall must wipe all per-install stores from `PluginManager.remove`.
- Keep API surfaces installed unless the API itself requires a grant. A missing member must return `not-granted` when the contract says it exists.
- `TlFlags`, `TlNames`, and generated TL tables come only from `pnpm run generate-tl-typings`. Never edit generated tables or `TLRPC.java`.

### Boot and lifecycle

- Start plugins in `ApplicationLoader.postInitApplication`. It must block for the early cohort so headless push updates see registrations.
- The early cohort is determined by declared grants. Bound the whole cohort by `BootCohort.EARLY_BUDGET_MILLIS`; load the rest at first UI.
- `BootGuard` protects one plugin start, not a full process or pass. It must survive a process death during that plugin start.
- A plugin reload updates in place. Do not derive identity from plugin name.

### Dev server

`PluginDevServer` + `scripts/push-plugin.ts`: `adb push` into `getExternalFilesDir("plugin-dev")`, then a `desu.inugram.plugins.DEV` broadcast installs or hot-reloads it. Off until `PLUGINS_DEV_MODE` is accepted through `PluginConsentSheet`.

- It installs with no trust sheet and no permission review. Two things gate it and both must stay: the receiver only exists while the toggle is on, and it demands `android.permission.DUMP` of the sender (adb shell holds it; an app cannot be granted it).
- Read source from the drop dir, never from a broadcast extra, and reject any file name that is not a plain name inside it.
- Register after `PluginStore.load()`, so a push that arrives as the receiver goes up finds the installed set.
- Reply through `setResultData` on the ordered broadcast - that string is what the script prints, so the receiver answers synchronously on the main thread.
- The `dev` bit on `PluginInstall` describes the last bytes written, not the install: an ordinary update of a dev-pushed plugin clears it.

### Wires, TL, and ownership

- Keep value wires and nullable error wires separate. A nullable error wire is a bare message or `P`/`R`, never an `E` wire.
- TL objects are lazy per-plugin handles. Reject forged, expired, and read-only writes. Interceptor handles are dispatch-scoped; invoke and update handles are plugin-scoped.
- A response that crosses queues needs `disableFree` and exactly one later free.
- Generate TL flags from `serializeToStream`, not the published schema. Presence recomputes a bit from its current value.

### Threads and RPC chains

- Ordinary engine work runs on `Utilities.globalQueue`; JVM Runnables and Xposed phases enter on their caller thread through the native serialized engine lease. No engine-owned Rc/Persistent state may escape that lease.
- Keep rquickjs `parallel` enabled. Reject recursive JNI entry before taking its runtime lock; promise jobs remain on globalQueue. Quiesce caller callbacks before host teardown.
- `onUnload` promises share a 2-second cleanup phase. Only JVM runnables created during cleanup bypass stopped callback admission. Poll without holding the engine lease; defer reload starts and uninstall wipes until teardown completes.
- Synchronous Xposed phases run on the hooked thread with bounded engine admission; busy/reentrant phases bypass. Hooks on the eight primitive box classes are refused: the lsplant stub boxes its own arguments through them and would recurse before any dispatch.
- `PluginXposed` shares one physical ART hook per member across engines. Keep session-local sites/handles independent; release the physical hook only after its last registration. Never hold the shared registry lock while dispatching plugin callbacks.
- Xposed session recursion guards cover callback phases, not the original/remaining plugin chain: stock calls nested inside an original must still reach their own hooks.
- Return app responses on `stageQueue`; keep chain bookkeeping on `globalQueue`.
- Raw RPC chains have one 10-second budget per scope; chains containing `interceptSendMessage` get 60 seconds. On timeout, abandon deeper stages first, release handles last, and fail unless passthrough already replied.
- Every chain continuation must verify that its pending dispatch is still current after a queue hop.
- A bypass is a request lease. Keep it through stock retries; remove it when the delegate replies or cancellation removes the request.
- The chain owns the request free after it sets `disableFree`; post that free to `stageQueue` after queued sends.

### Testing

- Put Rust tests in adjacent `*_tests.rs` files and include them with `#[path]`. Do not use inline test modules.
- `build.rs` compiles every JS prelude to QuickJS bytecode. Keep bytecode little-endian and tied to the exact QuickJS build. Do not use `include_str!` for preludes.
- Bridge tests run on a device in `src/test/kotlin`. Do not use a fake as the source of a fact the test asserts.
- Off-device source tests protect stock hooks and boot wiring. Keep them when a rebase could silently remove a call site.
- Plugin checks: `cd src/native && cargo check`; native tests: `cd src/native && cargo test`.
- Local JVM tests: `cd worktree && ./gradlew :InuCore:test`. Device bridge tests: `cd worktree && ./gradlew :TMessagesProj:connectedDebugAndroidTest`.
- Install all queue recorders in `resetBridge`. Use a fresh install ID per test; wipe fixed-name test storage where required.
- Native JNI failures abort the process. Clear pending exceptions, take the app offline in `resetBridge`, and initialize every container that a native callback reads.
- Keep device test method names snake_case and without spaces.

## Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu` (`InuHideStories`).
- Subtitle/info strings: same key + `Info` suffix (`InuHideStoriesInfo`).
- Access: `LocaleController.getString(R.string.InuXxx)`.

## Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- New asset dir → add path to `scripts/config.ts` → `forkSyncFiles`.
- Icons: tabler pack (`@iconify-json/tabler`), selection list in `scripts/config.ts` → `ICON_SELECTION`; `pnpm run setup` writes them into the worktree as `inu_tabler_*`. Dropping a name from the list does **not** delete the generated file: remove it by hand, or it lingers and looks available.
- **Eye icons: use stock's pair.** `msg_message` is the plain eye and `menu_hide_gift` the eye-with-slash. Both are named for where stock first used them, not for what they draw, so they don't turn up in a search for "eye": the fonts list (`FontsSettingsActivity`) and stock's gift menu both use them as the show/hide pair. Don't generate a new eye.

## Monet themes

`src/res/assets/monet_{light,dark,amoled}.attheme` — stock attheme format, values resolved by
`MonetHelper.getColor` (hooked into `Theme.getThemeFileValues` by `feature/monet-theme.patch`).

- Values are palette tones (`a1_600`, `n1_50`), M3 semantic tokens (`monet_surface_container_light`), custom names (`monetGreen`), or raw ints.
- Modifiers: `(a=)` alpha %, `(s=)` blend→white %, `(l=)` blend→black %, `(t=)` absolute HCT tone, `(c=)` HCT chroma multiplier % (0–400, relative so monochrome palettes stay gray). Comma-separated: `monet_secondary_container_light(t=90,c=75)`.
- Debug hot reload: `pnpm run push-theme [light|dark|amoled] [--watch] [--clear] [-s <serial>]` — adb-pushes the asset to the app's external files dir and broadcasts `desu.inugram.RELOAD_THEME`. Debug builds only (`getThemeOverrideFile` is a no-op otherwise); the app must be running.

## Java ↔ Kotlin gotchas

- `.value` (Kotlin) → `.getValue()` from Java.
- Kotlin `object` → `InuXxx.INSTANCE.method()` from Java unless `@JvmStatic`.
- For hooks called from stock Java, default to `@JvmStatic fun foo(...)` on a Kotlin `object` — cleanest call site.
- Inside stgit patches, the `worktree/` prefix is omitted from paths.
- `LayoutHelper.createLinear` / `createFrame` margin args are dp either way (both int and float overloads pass through `AndroidUtilities.dp(...)`). But Kotlin won't auto-promote `Int → Float`, and several overloads exist **only in the Float variant** — notably the 6-arg `createLinear(w, h, l, t, r, b)`. Write `12f` not `12` for margins or you'll hit "actual type is Int, but Float was expected".

Don't overuse `@JvmStatic`, only add it if the method/field is actually accessed from Java.

## Common pitfalls (from prior sessions)

1. **Running `stg`/`git`.** Don't. Read-only `stg top` / `stg show` only.
2. **Hand-editing `patches/*.patch`.** They're exports. Edit `worktree/`; user re-exports.
3. **Oversized stock patches.** Logic beyond a guard + helper call → move to Kotlin.
4. **Helper for 2–5 lines.** Inline it. Only extract when >5–7 lines or genuinely reused.
5. **Replacing stock behavior instead of running after it.** Stock stays intact; fork logic runs before (early return) or after, gated by config.
6. **Routing a trivial set through a helper method.** If the patch just assigns a field based on config, assign in-place at the stock call site.
7. **Modifying stock base classes.** Look for an existing extension hook first (stock often has setup hooks for themed things). Base-class edits rebase poorly.
8. **Writing Kotlin helpers for what must be a Java fix.** Bug in `EditTextCaption` → fix it **in** `EditTextCaption.java`. Don't detour.
9. **Ungated fork behavior.** Default-off must equal stock. Verify every call site.
10. **Java using `.value`.** It's `.getValue()`. Kotlin `.value` is a property; `@JvmField` only exposes the wrapper.
11. **Forgetting `inu_` prefix** when adding fields/methods/overloads to stock classes. Including Java fields.
12. **Re-indenting stock** to wrap it in an `if`. Kills rebases. Use early returns, add-after-stock, or keep indentation the same.

## stgit workflow (user-initiated only)

You never run these unless explicitly asked — documented so you can answer questions / suggest commands.

```bash
# create a new patch
stg new feature__my-patch -m 'Allow editing by double tapping a message'
# ...edit worktree/...
stg refresh
pnpm run export

# modify existing patch in-place
# ...edit worktree/...
stg refresh -p feature__my-patch  # --index for staged-only

# modify existing patch, floating to top (preferred for non-trivial changes)
stg float feature__my-patch
# ...edit...
stg refresh
pnpm run export
```

`pnpm run export` rewrites `patches/` + `series` from the stack. User runs it.

If user asks "which patch am I on" → `stg top`.

## Self-maintenance

When adding a new `InuHooks` method, settings page, or shared `hooks/` patch — update this file. Tribal knowledge rots.
