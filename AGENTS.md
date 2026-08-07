# Inugram Agent Guide

Inugram is a **patchset**, not a fork. `worktree/` is a stock Telegram checkout with
stgit patches applied on top. Fork code lives in `src/kotlin`/`src/res` (symlinked
into the worktree). `patches/` and `series` are export targets, not source of truth.

`FEATURES.md` is the user-facing list of fork features/bugfixes. Keep it in sync —
when adding, removing or meaningfully changing a patch, update `FEATURES.md` in
the same change.

## Golden rules (never violate)

1. **Edit `worktree/` directly.** Never hand-edit `patches/*.patch` or `series` — they regenerate from stgit.
2. **Do not run `stg` or `git` yourself** unless explicitly asked. Read-only `stg top` / `stg show` is fine. NEVER run `stg export`.
3. **Stock patches stay tiny.** Only wiring/hooks/guards. Real logic goes in `src/kotlin`. A patch touching only `src/**` is usually wrong.
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
| `app-loader.patch` | custom `ApplicationLoaderImpl` instead of stock |
| `chat-activity.patch` | various ChatActivity hooks — message menu (`ChatHelper.addMenuItems`/`processMenuOption`), `undoView`, `replyingMessageObject` etc. |
| `icon-replacement.patch` | custom resource loader for icon replacement |
| `internal-web-app.patch` | `WebViewRequestProps.inu_internalType` + `WebAppHelper.getInternalBotName` for internal bot web sheets |
| `loginactivity.patch` | hooks inside LoginActivity |
| `messagescontroller.patch` | access `MessagesController` instances as they're created |
| `notifications-controller.patch` | hooks inside NotificationsController |
| `photo-viewer-menu.patch` | `PhotoViewerHelper.{addMenuItems,updateMenuItems,resetMenuItems,handleMenuClick}` + `inu_getCurrentPhotoFile`; exposes `containerView`, `menuItem`, `showDownloadAlert` |
| `popup-swipeback.patch` | foreground translation + unified touch coords on swipeback popup |
| `profile-menu.patch` | `ProfileHelper.addMenuItems` + `ProfileHelper.handleMenuClick` |
| `universal-recycler.patch` | extra features in `UniversalRecyclerView` used by settings pages |

**When to add a `hooks/` patch vs a normal patch:**

- New stock surface that **>1 future patch will wire into** → `hooks/`.
- One-off wiring for a single feature → keep inside the `feature/`/`debloat/` patch.
- **Rule of 3**: if 3+ existing patches touch roughly the same stock surface, consolidate.
- A `hooks/` patch must be functionally a no-op with its consumers stubbed out.

Conventions: expose the minimum, promote `private` → `public` over duplicating data, `inu_` prefix on new fields, entry point is always a call to `desu.inugram.helpers.XxxHelper.*` — never inline logic.

## Helpers

Live in `src/kotlin/helpers/`. Sub-packages by feature area: `chat/`, `dialogs/`, `menu/`, `translate/`, `search/`, `media/`, `font/`, `update/`, `cloud/`, `security/`, `theme/`, `profile/`, `icons/`, `maps/`, `notifications/`. Cross-cutting / standalone ones stay flat.

Naming (don't mass-rename):
- `*Helper` = feature-coordinator singleton
- `*Config` = `InuConfig.Item` subclass / data model
- `*Utils`/`*Parser`/`*Drawable`/`*Resources` = concrete type or algorithm

Common entry-point helpers: `ChatHelper` (chat features), `ProfileHelper` (profile menu), `PhotoViewerHelper` (photo viewer), `FolderHelper` (folder tabs), `MainTabsHelper` (bottom tabs), `MonetHelper` (theming), `NonIslandHelper` (non-island UI gating), `InuDatabaseHelper` (fork DB), `InuUtils` (id generation etc.).

Before creating a new helper, check whether an existing one owns the area.

## `InuHooks` — central lifecycle bus

`src/kotlin/InuHooks.kt`. Generic lifecycle dispatch only — feature-specific code goes on its own helper.

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
rebase** (the two gitignored outputs rebuild themselves; the three committed tables do not):

| output | what |
| --- | --- |
| `src/plugins/android.tl.d.ts` | every TL type as `tl.Raw*` / `tl.Type*`, plus `tl.RpcCallReturn`. header stamps `@layer` + `@appVersion` (read from `TLRPC.java` / `gradle.properties`) so a copy outside the repo still says which build it describes |
| `src/core/.../TlNamesTable.kt` | the wire name of every class whose java name doesn't read as it |
| `src/core/src/main/resources/tl_flags.txt` | which flag word + bit gates each optional field |
| `src/core/src/main/resources/tl_ctor_ids.txt` | canonical name -> every constructor id incl. legacy/layer variants, plus method/update kind markers — backs grant scope validation and the TL api filter |
| `src/core/src/bridgeTest/tlstubs/**` | the same type tree as compile-only java (names, nesting, superclasses, ctor ids, public fields; no bodies), so the bridge test harness reflects over stock's real shape — `pnpm run generate-tl-stubs`, and `generate-tl-typings` runs it for you |

It parses `TLRPC.java` + `tgnet/tl/**` (`scripts/tl-parser.ts`) and joins the result against stock's
own layer dumps in `TMessagesProj_AppTests/tlscheme/*.json` by constructor id. The three tables the
app ships are committed; the two big derived trees are not - `android.tl.d.ts` is gitignored and
regenerated by `typecheck-plugins` (`--typings-only`, so a check never rewrites a committed table),
and `tlstubs/**` is gitignored and rebuilt by `:InuCore:generateTlStubs`. Never hand-edit them — and never hand-edit `TlNamesTable.kt`/`tl_flags.txt` to "fix" a
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

The engine crate is `src/rust/inu_native`, one folder per area under `src/`: `jni/` (the
`extern "system"` entry points, the one Java object every upcall goes through, and the host-trait
impls), `engine/` (argv, the property forms a rust-built prototype uses, the cpu and memory
ceilings, the error vocabulary, the globals, the
registration bookkeeping, the timer wheel), `tl/` (the handle proxy plus the `inu.Message` and
`inu.utils` preludes), `tg/` (account reads/writes and the rpc, update and deserialize
interception), `io/` (blob, fs, fetch), `draw/` (canvas, geometry, css), `ui/` (settings pages,
action rows, icons, screens), `platform/` (jvm, xposed, elf, lsplant, the notification centre),
`api/` (kv, dialogs, clipboard, `openUrl`) and `testing/`. A module's test suite lives beside it as
`<name>_tests.rs`, pulled in with `#[path]`; a `.js` prelude sits next to the module that
`include_str!`s it.

`src/plugins/common.d.ts` is the **normative** contract — if code and doc disagree, the doc wins or
the disagreement is a bug. `private/plugins-plan.md` is the roadmap.

**`pnpm run typecheck-plugins` is what keeps the contract and its consumers honest**, and it is two
projects. `src/plugins/tsconfig.json` checks the typings themselves plus `test.ts`;
`src/plugins/tsconfig.bundled.json` checks the **bundled dev plugins** in
`src/res/assets-debug/inu_plugins` against them with `checkJs`, which is the only thing standing
between an oracle and a member the contract does not declare: in a suite written out of
`expectThrows`/`expectRejects`, a member that *vanished* reads as a refusal and passes. It dials
`noImplicitAny`/`useUnknownInCatchVariables` off (they are plain js and always will be) and keeps
everything that can catch drift, so a deliberately-wrong argument needs a `// @ts-expect-error`,
which then fails loudly if the api ever widens to accept it. Run it after touching either side. It
regenerates `android.tl.d.ts` on the way (gitignored, so it may not be there at all) and starts with
`check-plugin-syntax`, which is a **loop**: `node --check` takes one file and silently
ignores every argument after it, so `node --check a.js b.js` parses `a.js`, reports clean, and says
nothing about `b.js`. Never write that form; run the script.

The host half is `src/kotlin/helpers/plugins`, grouped to mirror the crate: `tg/` (`PluginRpc`,
`PluginReads`, `PluginWrites`, `PluginMedia`, `PluginDeserialize`), `tl/` (`TlHandles`, `TlJson`,
`TlFilter`), `ui/` (`PluginUi`, `PluginActions`, `PluginIcons`, `PluginScreens`, `PluginCanvas`),
`io/` (`PluginFetch`, `PluginFs`, `PluginBlobs`), `platform/` (`PluginJvm`, `PluginXposed`,
`PluginNotifications`) and `api/` (`PluginApi`, `PluginKv`), with `Plugin`, `PluginManager`,
`PluginDispatch` and `QuickJs` left at the root. `QuickJs` cannot move: its package is half of every
`Java_desu_inugram_helpers_plugins_QuickJs_*` symbol name in `jni/exports.rs`. The subpackages are
still under the prefix `PluginJvm.ENGINE_PACKAGE` refuses, so reflecting back into the engine stays
`forbidden`.

**Everything below is testable, and most of it only here.** `src/kotlin/helpers/plugins` is compiled
into `:InuCore`'s `bridgeTest` source set and run on a plain JVM — `./gradlew :InuCore:bridgeTest`,
or `:InuCore:check` for that plus the pure-core suite. The stock types it reflects over are
generated (`src/core/src/bridgeTest/tlstubs`, rebuilt by the `generateTlStubs` task the
bridge compile depends on, so a rebase needs nothing done by hand); the queues,
`SystemClock`, `ConnectionsManager`, `QuickJs` and `PluginManager` are doubles in
`src/core/src/bridgeTest/{fakes,kotlin}`. A new bridge file is in the harness by default; one that
cannot be (it needs a `Context`, an `Activity`, the JNI library) goes in `bridgeExcluded` in
`src/core/build.gradle`. See `src/core/src/bridgeTest/README.md`. **A change to any rule below
without a bridgeTest case is how the last three of them were broken.**

Two shapes of test cannot fail, and both have already shipped here. **A ceiling is pinned to the
sentence that states it**, never to itself: every behaviour test injects its own limit or spells the
constant, so a suite full of those stays green with the number raised to its maximum. `testutil::
stated_number` (and Kotlin's `statedNumber`) read it back out of `common.d.ts`/`fs.d.ts` instead, and
every shipped ceiling has one such test. **A bundled oracle is held to an exact assertion count**
(`testutil::assert_oracle_exact`), never a floor, and every oracle has a run site: a floor is cleared
by every number it is not equal to, and an oracle nobody runs is one nobody notices going green on a
broken engine — or, as `api-filter-test.js` had been, red on a working one.

- **Grants are unprefixed** (`kv`, `interceptRpc(users.getUsers)`), never `inu.kv`. Every decision
  goes through one gate: rust `check_grant` → JNI `onCheckGrant` → `PluginPermissions.allows`,
  fail-closed. `GrantValidator` additionally rejects unknown *scopes* at install; unknown grant
  *names* stay ignored. An **unscoped grant satisfies every scope check**, so anything that must
  hold regardless of grants (the `auth.*`/`account.*` takeover list) needs its own call-time check.
- **Identity is minted at install**, never derived from the manifest: `PluginInstalls.mintId()` (32
  hex chars), persisted in `PLUGINS_STATE` next to the file it was installed from. There is no
  `@namespace` and `@name` is a label, so renaming keeps a plugin's data and a name-squatter gets an
  empty store. The install id keys `kv` (prefs file `inuplugin_kv_<id>`) and will key the `fs` scoped
  directory, and uninstall wipes both. Load-from-file is always a *new* install, the per-plugin
  reload action always an update in place — nothing ever matches on a name.
- **The boot point is `ApplicationLoader.postInitApplication`, and it blocks.** That is stock's own
  "the app is really starting" gate, idempotent behind `applicationInited` and called from ~22 entry
  points, so one hook covers the push wakeup that hands a decrypted `TL_updates` to `processUpdates`
  with no activity ever created - where a plugin loaded at first ui is absent for every `onUpdate`,
  `interceptUpdate` and `interceptRpc` that arrival fires. It cannot be `InuHooks.init`
  (`ApplicationLoader.onCreate` runs *before* `NativeLoader.initNativeLibs`, so
  `System.loadLibrary("inu_native")` is not safe there), and it cannot be asynchronous, or
  `processUpdates` races the registrations. That puts plugin startup on the critical path of every
  push, so **who boots there is derived from the declared grants** (`BootCohort.HEADLESS_APIS`: the
  apis a headless path dispatches into) and the app stops waiting after
  `BootCohort.EARLY_BUDGET_MILLIS`; everything else loads at first ui, where `start()` no-ops on
  whatever is already running. **The crash guard spans one plugin, not the process and not the pass**
  (`BootGuard`): armed durably before that plugin's own code and dropped when it comes back. Not the
  process, because android starts this one without a ui all the time and kills it again - a guard
  that waits for an activity is left armed by every push and every reboot, and the next start the
  user actually sees then silently runs nothing. Not the pass either, because a pass is unbounded:
  the app stops *waiting* at `EARLY_BUDGET_MILLIS` while the plugins behind that keep evaluating, so
  a flag held across all of it turns the reap of a process android considers idle into a crash
  nobody had. So safe mode is no longer cold-start-only, and it is *shown* in `PluginsActivity`: a
  session that ran nothing has to say so somewhere, since every row still reads as enabled.
- **Errors** are `inu.PluginError` with a code from `common.d.ts` (`not-granted`, `forbidden`,
  `handle-expired`, `quota-exceeded`, `invalid-argument`, …), carried as a `P` wire.
- **Two wire channels, different rules.** *Value* channels (`tlGet`, `onKv`) tag every string, so
  `E<message>` is unambiguous there. The `String?` *error* channels (`RpcListener`, `tlSet`) carry
  nothing but errors, so native cannot tell an `E` wire from a message beginning with `E` — return a
  bare message or a `P`/`R` wire, never `E`.
- **TL objects reach JS as lazy views**, never eager JSON. Handle wire is `H` + `O|V` + `W|R` + id.
  `TlHandles` is **per-plugin** (a plugin can forge the handle-marker symbol, so a shared table would
  let one plugin read another's objects by guessing an id). Writes are refused on both sides: rust
  traps and Kotlin `tlSet`/`resolveSetValue`. A read-only handle coming *back* over the bridge is
  refused too, or it would be re-minted writable.
- Lifetimes: `interceptRpc` views are dispatch-scoped (`releaseScope`), `invokeRpc` results and
  `onUpdate` payloads are plugin-lifetime. `next()` may rewrite the request's fields but **not** its
  method. **Any response that crosses a queue hop needs `disableFree` plus exactly one matching
  free**, because stock frees it the moment the delegate returns (`freeResources()` on
  `upload.getFile`/`cdnFile`/`webFile` hands the payload's `NativeByteBuffer` back to a pool). That
  holds for `invokeRpc` results (owned by the plugin's table) *and* for the chain's passthrough
  response, tracked in `ownedResponses[scopeId]` and consumed by that chain's single top-level
  `finalize`, *and* for a write's own response, which `PluginWrites.send` takes over on `stageQueue`
  and frees after the settle it posted to `globalQueue` has run. Every early exit must either free
  it or leave the obligation on the map, never both.
- An engine is entered **only from a `globalQueue` runnable, never from inside a JNI upcall**, and
  there is no exception. `Context::with` takes the runtime's `RefCell`, so a same-engine re-entry is
  a `BorrowMutError` panic out of an `extern "system"` fn, i.e. a process abort. It is reachable with
  one plugin: registering twice for one method puts it in its own chain twice, and `next()` runs
  inside the outer `with`. `PluginRpc.onNext`/`onComplete` therefore post rather than call inline.
  Entering from an app subsystem thread aborts too (quickjs stack check). **rquickjs's `parallel`
  feature stays off**, and turning it on to let a foreign thread in is the trap: it swaps that
  `RefCell` for a non-reentrant `std::sync::Mutex` (`safe_ref.rs`), so the abort becomes a *silent
  deadlock* of whichever thread re-entered, on a queue shared with the whole app - it was on for one
  release and `platform/xposed.rs` deadlocked on `inu.account().getUser()` inside a hook. So a surface the app
  calls *synchronously* from its own thread either posts and returns nothing
  (`inu.jvm.runnable`), or posts and blocks the caller on the answer - `interceptDeserialize`'s
  middleware form, and `inu.xposed`, which is the same shape one phase further: `PluginXposed`
  parks the calling thread on `dispatch_before`, **calls the original itself** (a hooked method may
  be one only the ui thread may run), then parks on `dispatch_after`, each for
  `xposed::HOOK_BUDGET_MS`, and skips the hooks outright when the caller is already `globalQueue`.
  A member that can do neither is `unsupported`, which is `inu.jvm.defineClass`: a js-backed
  override has to answer java with a value, on whichever thread java called on.
- **`interceptDeserialize`'s declarative tier sidesteps that by never entering an engine at all.** A
  rule is data: `tg/deserialize.rs` validates it, hands the host one normalized JSON array at
  registration, and `PluginDeserialize` alone evaluates it. The wire is built from the values that
  module *read*, never a re-stringification of the plugin's object, because a rules object may carry
  getters and a re-read after the grant check could say something else. The hook is stock's own
  `TLObject.TLdeserialize`, which every one of the 607 live `TLdeserialize` implementations in
  `org.telegram.tgnet` routes through, so it sees the local cache as well as the wire - and it is
  keyed by the constructor id already in hand, so an object no rule named costs one volatile read
  and one `SparseArray` probe on the cold-start path.
- **The middleware tier is the same hook, one blocking queue hop further on.** `PluginDeserialize`
  posts to `globalQueue` and parks the parsing thread on a latch for at most
  `MIDDLEWARE_BUDGET_MS` (250 ms); past that the object is delivered as parsed and the walk stops at
  the next engine boundary, so a slow plugin costs a quarter second per object and never the object.
  It is **skipped outright when the parse is already on `globalQueue`**, which is a plugin reaching
  a stock parser through `unsafe.jvm`: waiting there is waiting on ourselves. The rewrite is in
  place and the return value is ignored - the app keeps what it parsed, so there is nothing to
  substitute - and the view is an ordinary dispatch-scoped `TlHandles` mint, which is what makes
  `TlFilter` and `DeserializeGuards` (a `guarded` scope, inherited by every child handle) cover it
  without a second implementation. The two tiers share the 32-rule budget and one token space,
  because both cost the app the same thing: one more constructor id every parsed object is tested
  against. Stock's gate is `PluginDeserialize.hot`, one volatile read answering for both.
- **A rewritten object is written back to sqlite, which makes this the one api whose mistakes
  outlive the plugin.** Stock re-serializes its live `users`/`chats`/`messages_v2`/`dialogs` rows, so
  a rule reaches disk on the first save after it fires and a row the server never re-sends keeps it
  for good; uninstalling does not undo it. Hence `DeserializeGuards`: `id`, any `*_id`,
  `access_hash`, `dc_id` and `file_reference` may not be `set`, because those *address* the object,
  a wrong one is well-formed so the app never notices, and a refetch writes to the row the wrong id
  names. That guard is **not** a filter and `unsafe.disableApiFiltering` does not lift it. It is a
  rule about the *slot*, so `TlHandles` applies it to the value rather than to the name written:
  `d.peer = {_:'peerUser',user_id:x}` lands where `d.peer.user_id = x` does, a vector element has no
  name at all, and a live handle carries the addressing of wherever it was parsed - so a guarded
  scope walks the whole construct payload (`TlJson.findProtectedField`) and refuses a handle value
  outright. Checking the assigned name alone refuses the first form and allows every other. Three
  refusals that *are* the deserialize-side half of rules holding elsewhere: secret-chat traffic is
  unreachable (stripped by constructor id, since `TL_message_secret` rides inside `idsOf("message")`),
  the takeover surface is not a rule target, and **a `when` may only name a field the plugin could
  read** - matching on a value is reading it, so a rule firing on a guess would confirm the guess.
- **The app is answered from `stageQueue`, where stock answers it from.** `sendRequestInternal`'s
  own tail runs there, and its delegate-less `Updates` branch calls `KeepAliveJob.finishJob()` then
  `processUpdates`, which is documented "must be run from `Utilities.stageQueue`" and mutates pts/seq
  from that queue with no locking. Chain bookkeeping stays on `globalQueue`; only the `when`-block
  and the response free hop over, and the hop lives inside `finalize` so no caller can forget it.
- The `interceptRpc` chain has one **10 s budget keyed by `scopeId`**, suspended across the real
  passthrough (`SystemClock.uptimeMillis`, matching `Handler.postDelayed`). On expiry `collapseChain`
  abandons every live stage deepest-first, removing each from `pendingDispatches` *before* telling
  its engine (or a rejection continuation re-enters `onNext`/`onComplete`) and releasing the handle
  scopes only after all of them (or that same continuation reads `handle-expired` off every field of
  its own request). Same order in `detach`, hence `releaseAll()` last. The app's request fails with
  `TL_error(-1000, "INTERCEPTOR_TIMEOUT")`, **unless the passthrough already answered**
  (`ChainBudget.passthrough`), in which case the real response is delivered instead; a synthetic
  timeout there would report a committed `messages.sendMessage` as failed and earn a duplicate. It
  never falls through, because earlier stages have already mutated the request in place and
  fall-through would make any stall a reliable bypass of a blocking interceptor.
- **A settled stage answers upward, so anything that ends a stage must first abandon the sub-chain
  below it** (`abandonBelow`), or the deeper stages keep advancing and the real request still goes
  out after the app was told the request failed. Every continuation that resumes after a queue hop
  re-checks `pendingDispatches[dispatchId] === pending` first: a due expiry timer sorts ahead of a
  runnable posted now, so liveness is never implied by having been live one hop ago.
- Chain order is **derived from `PluginManager.plugins()` on every publish** (`publishInterceptors`,
  stable sort, `IdentityHashMap` because `Plugin` has no `equals` and reload swaps instances), not
  from registration order. Every structural mutation of the list goes through
  `PluginManager.republishOrder()`, which re-publishes the snapshot *and* calls
  `PluginRpc.refreshChainOrder()` — a drag that skipped it would not move a live chain until the
  process restarted.
- **A runaway plugin can only be stopped from inside quickjs.** Every engine op shares `globalQueue`
  with the chain's own expiry timer, so nothing posted there can preempt a `while (true) {}`. Hence
  `engine/deadline.rs`: every JNI export that can run plugin JS arms a thread-local deadline (2 s per entry,
  10 s for `nativeEvaluate`), and the interrupt handler raises an uncatchable `InternalError`. It
  bounds **one entry only**: the handler is polled on interpreter back-edges, so a callback blocked
  inside a synchronous host call is not interrupted until it returns, and an async stall is the
  chain budget's problem. So **a native op that can move an unbounded amount of data carries its
  own bound**, refused before it starts and in a unit the contract can state (`io/blob.rs`'s
  `BUILD_LIMIT_BYTES` is the only one so far). What the deadline still covers is a loop of bounded
  ops: it is wall clock, not cpu spent in js, so time inside host calls counts against it.
- **A plugin that throws is switched off, and rust is the only layer that can tell that from the
  host having a bad day.** Every diagnostic crosses as one `onConsole(level, message)`, so the
  engine marks the ones plugin code caused (`fault()` in `lib.rs`, stripped again by
  `classify_log`) and only those go out at `QuickJs.LEVEL_FAULT`, which `PluginManager` stores as
  the plugin's failure before disabling it. **The list is exactly the `crate::fault(` call sites**,
  and is re-derived from them rather than remembered: an `interceptRpc`/`interceptSendMessage` stage
  that threw or rejected, an `interceptUpdate` middleware that threw, rejected, or answered with
  something that is not a verdict, a throwing `onUpdate`/`onUnload`/`onAppVisibilityChange`/
  `onScreenChanged` callback, a throwing `unsafe.notificationCenter` handler, a throwing
  `jvm.runnable` callback, a throwing settings callback / menu-item callback / `onClose`, a throwing
  action `callback`, an action or settings-page render that failed as a whole (a throwing `items()`,
  or `try_render` itself throwing, which plugin code reaches only by polluting `Object.prototype`),
  an unhandled rejection, and an entry the execution deadline cut down. The last one is a fault
  because the uncatchable `InternalError` unwinds into whichever of the sites above was running: the
  interrupt handler's own diagnostic is deliberately *not* a fault, `common.d.ts` promising that only
  the turn dies. The one carve-out inside a fault site is **an `inu.RpcError`**
  (`describe_stage_failure`), because throwing one is the documented way to fail an intercepted
  request and an abandoned stage's parked `next()` rejects with one the plugin did not cause. A JNI
  failure, a throwing *host* listener, a wire the bridge could not decode, a failed install of our
  own prelude and a render asked of a page the engine no longer has stay
  ordinary errors: disabling a plugin for the app's mistake is a bug that reads as a plugin bug.
  So is **one action row's `visible`/`text` throwing**, which `common.d.ts` states drops that row
  and nothing else: a render predicate that fails on one chat must not switch off every other
  feature the plugin provides, whereas an action the user clicked failing is a real fault.
  `console.*` binds levels 0..4, and the marker is only ever written at the front of a message the
  engine composed itself, so plugin text cannot forge one. A heap ceiling reached inside plugin
  code arrives as a fault too, since `format_thrown` names it (`describe_heap_exhaustion`) at the
  same site that reports the throw.
- **Memory is bounded per engine, in two counters that cannot see each other.** `engine/deadline.rs` also
  owns the ceilings: 32 MiB of JS heap (`JS_SetMemoryLimit`, applied in `nativeCreate`) and 64 MiB
  of native memory behind JS objects (`ExternalMemory`, charged by an in-memory `Blob` and later by
  `OffscreenCanvas`). They stay separate because a shared pool would need a whole-heap
  walk per charge and could leave the heap ceiling *below* the live heap, which nothing in JS can
  recover from. The heap ceiling's failure is **catchable and is not a `PluginError`** - quickjs
  raises `InternalError: out of memory`, or bare `null` when it has no room left to build even
  that, which is what a gradually grown heap hits and is indistinguishable from a plugin's own
  `throw null` once the turn has unwound. `describe_heap_exhaustion` names it `quota-exceeded` on
  the way out to the host, the only place it can be named. An external charge *is* refused with a
  real `quota-exceeded` `PluginError`, because that allocation has not happened yet; it runs a GC
  first, since a cycle holding a canvas is a few dozen bytes of JS and gives quickjs no reason to
  sweep.
- **The sandbox globals are three different kinds of thing, and only one is ours.** Everything in
  `common.d.ts`'s globals block that quickjs-ng ships (`atob`/`btoa`, `DOMException`,
  `performance`, `queueMicrotask`, `BigInt`, `Proxy`/`Reflect`, `WeakRef`, all of ES2022) arrives
  through **`Context::full`**, which calls quickjs-ng's own `JS_NewContext` - rquickjs's
  `intrinsic::All` is *not* the same list and omits `JS_AddIntrinsicAToB`/`DOMException`, so moving
  off `Context::full` silently deletes documented surface. A test can only pin a context it built
  itself, so `install_globals` checks the *engine's* context instead: a context missing any of
  `REQUIRED_INTRINSICS` is refused outright, making a swapped constructor an install failure on the
  first plugin rather than a `DOMException is not defined` inside someone's bundled library. The
  shapes with no host state (`TextEncoder`/`TextDecoder`, `crypto`, `AbortController`,
  `structuredClone`) are prelude JS in `globals.js`, handed its native helpers as a **factory
  argument** so nothing reachable from plugin code holds a reference to them. `Blob`/`File` install
  from `engine/globals.rs` too, *before* the prelude, so `structuredClone` can capture the real
  constructor: a blob clones **by reference** (a second handle over the same backing, the same
  relation as a slice), which needed one more native helper and no JNI export. `structuredClone`
  preserves reference identity, not only cycles, so a graph that shared a node before the clone
  shares one after it. `structuredClone`
  **throws `DataCloneError` on a TL view**, duck-typed on `Symbol.for('inu.tl.handle')`: a view is a
  host object, cloning one would walk the graph over the bridge a field at a time and could expire
  halfway, and `toJSON()` already detaches one in a single hop. **None of the host's own marshalling
  goes through a global**, which is the other half of that sentence: `JSON` is writable and plugin
  code shares this context, so `api::json_parse`/`json_stringify` and `proxy::json_parse_tl`/
  `json_stringify_tl` call `Ctx`'s `JS_ParseJSON`/`JS_JSONStringify` instead. Reading `JSON.parse`
  off the globals handed a plugin every host wire *before* the grant gates that rebuild the exposed
  object from it ran - which is how `getCurrentScreen`, ungated by design, disclosed the `dialogId`
  it is `account.read(dialogs)` that gates. The bytes reviver moved into rust with it
  (`proxy::revive_bytes`, own enumerable keys only, or a polluted `Object.prototype` makes every
  parsed object a Uint8Array). The same reasoning is why `PluginIcons` re-checks a spec's shape
  (`core.plugins.IconSpec`) rather than trusting that rust validated it: it crossed as
  plugin-serialized text.
- **A `Blob` is content with three possible homes and one owner.** `io/blob.rs` mints a `Backing`
  (memory / spill file / a file the *app* owns) and any number of `BlobHandle` ranges over it, of
  which only the root `owns_backing`. That is what makes the two disposal rules `common.d.ts`
  states different: disposing the root frees the backing and every slice then fails *reads* with
  `handle-expired` while still answering `size`/`type`/`name`/`lastModified` (they live in the
  slice's own object), whereas disposing a slice ends that handle alone. A handle whose own
  `dispose()` ran is dead for every member. **One construction may assemble 32 MiB**
  (`BUILD_LIMIT_BYTES`, `quota-exceeded` past it, refused before the part that crosses it is read):
  copying parts is native work, and the entry deadline is polled on js back-edges, so it cannot see
  a single host call at all. A byte bound rather than a deadline poll, because the same call has to
  succeed or fail on what it was handed and not on how much of the entry budget was already spent.
  A *loop* of constructions is still cut down, the deadline being wall clock. Content crosses
  **2 MiB** to a spill, and the native
  budget refusing a charge spills too rather than throwing (`try_charge`) - a blob has somewhere
  else to put its bytes, so it only ever fails for want of disk (2 GiB of live spill per plugin, or
  ENOSPC, or **64 spill files**, since the fd below is a process-wide resource the byte ceiling
  bounds nothing about: content spills at four bytes once the native budget is full). Both spill
  ceilings **collect before they refuse**, like `try_charge` does, or content held by a reference
  cycle is quota a plugin has no way to get back. What stays in memory is charged for its
  **capacity as it grows**, never for its length at the end: a `Vec` that doubled its way there
  holds twice what it says, and a budget told only at the end has already been overrun by the
  allocation that overruns it. The spill's **fd is held for the backing's life**, so android
  evicting the cache dir cannot break a read; the app-owned kind holds none and re-checks a sealed
  `size`/`mtime` per
  read, since stock re-downloads into the same path and fewer bytes than `size` promised is a
  quieter lie than `handle-expired`. `bytes()`/`arrayBuffer()` refuse past **16 MiB** (half the
  heap) *before* reading, because the alternative is the read succeeding and then dying as an
  unattributable OOM; they reject rather than throw. `text()` stops at **8 MiB**, since one char
  past latin-1 widens a quickjs string to 2 bytes per char and an ascii byte is one char, so the
  byte ceiling would be the whole heap. Identity needs no marker symbol
  (`tl_proxy`'s does only because its object is a `Proxy`): a blob is a real `Class`, so the host
  wire's id table exists only for values crossing to Kotlin and is **per engine** anyway. An id
  resolves to the *handle's* range and not to its `Backing`, or handing over a four-byte header
  sliced off a download would hand over the download; the table is swept of dead `Weak`s as it
  grows, since nothing else ever removes from it. Nothing
  here holds a `Persistent`, and `File.prototype` is stashed on the blob prototype under a
  non-writable symbol rather than read off `globalThis.File`, which a plugin can reassign.
  Kotlin's whole share is `PluginBlobs`: the app's *internal* `cacheDir` (never
  `AndroidUtilities.getCacheDir()`, which can be a card the user ejects), one directory per
  process-session per install id, wiped after `engine.close()` and swept of every *other* session
  from `PluginManager.init` - but posted to `cacheClearQueue`, that being `ApplicationLoader
  .onCreate` and the sweep an unbounded recursive delete. An engine the host could not make a
  directory for (`dirFor` answering `""`) cannot spill at all, which is the one case where a blob
  does fail for want of ram.
  Reads stay in rust: an `onBlobRead` upcall would put 16 MiB per read on the app-wide *Java* heap,
  handing a plugin back the lever the per-plugin heap ceiling exists to take away.
- **Timers are per plugin and the host is only an alarm clock.** `engine/timers.rs` owns the whole wheel;
  the only thing that crosses to Kotlin is one outstanding "wake me in N ms" (`onTimerSchedule`,
  `-1` withdraws), re-armed only when the earliest deadline moves, answered by `runTimers()` on the
  engine's own `globalQueue`. So unloading a plugin cancels every timer it armed by dropping that
  state, and a stray `setInterval` cannot outlive it. A tick fires only what was due when it
  started, so a timer armed *by* a callback waits for the next wake; an interval that came due
  several times over re-arms from now and fires **once**.
- **The wake is paced by the host, because the queue is the host's** (`PluginApi.TimerThrottle`).
  The 2 s entry deadline bounds one entry, not entries per second, so `setTimeout(function f() {
  setTimeout(f, 0) }, 0)` is an unbounded claim on `globalQueue` that no in-engine limit can see.
  A tick is timed and the next wake is held to `max(what the wheel asked for, end of tick + 9x its
  cost, end of tick + 4 ms)`, i.e. a tenth of the queue per plugin, with the 4 ms covering ticks too
  cheap to measure. It is *not* in `engine/timers.rs`: the wheel's job is when a plugin wants to be woken,
  and the cost of serving that is a property of a queue rust does not know exists. Delaying a wake
  can only fire timers later, never fewer, so it composes with the background floor by `max`.
- **Backgrounding throttles the timers, not the queue.** `timers::set_visible` floors *the wheel*
  (1 s while hidden, 60 s once hidden five minutes), measured from the last tick rather than from
  each timer's delay, so ten intervals cost one wake per period instead of ten. Returning to the
  foreground lifts the floor and fires everything overdue exactly once, which the "an interval
  fires once" rule above already bought. **The floor is also lifted while the plugin holds one of
  the app's own requests** (`Lifecycle::has_blocking_dispatches`, set from the size of `rpc`'s
  dispatch table): an `interceptRpc` stage that `await`s a timer is how a debounce or a backoff is
  written, and a 60 s floor against a 10 s chain budget would fail the app's request because the
  user switched apps. The relaxation is keyed on *something waiting on us*, not on which timer is
  awaited - the wheel cannot see what a promise is chained to - and it cannot outlive the dispatch
  that took it. Taking or releasing it is not itself a re-sync; the next `sync_wake` (for the case
  it exists for, the `await` arming the timer) picks it up. Updates, interceptors and every host callback keep their
  timing in both states, or a message arriving while hidden would never reach `onNewMessage`. The
  signal is `nativeAppVisibilityChanged` into `timers::set_visible` plus
  `api::app_visibility_changed` (`inu.onAppVisibilityChange`, transitions only, deduped on both
  sides). It needs **no stock hook**: `PluginApi.watchVisibility` counts started activities through
  `Application.registerActivityLifecycleCallbacks`, registered from `PluginManager.init` so it
  cannot miss the first start, and debounced 700 ms because a configuration change stops the old
  activity before starting its replacement. An engine starts out believing it is in the foreground,
  so `PluginApi.attach` pushes `false` when it is not: a process a push notification woke has no
  activity and never will.
- **`onProgress` is coalesced on time, never per chunk** (`tg/progress.rs`): leading edge, then one
  report per 100 ms, and a withheld report is kept rather than dropped. Nothing drives the throttle
  but the reports themselves, so whatever ends a transfer *must* call `finish`/`release` or the last
  numbers a plugin saw are whichever ones happened to land on a window boundary. Its callers are
  the media transfers: `tg/writes.rs` holds one reporter per request and `write_result` is what owes it
  the terminal call - `finish(total, total)` for a transfer that arrived, `abandon()` for one that
  did not, and `release()` from `dispose`. The host reports per chunk and nothing between it and
  `tg/progress.rs` throttles, so the contract is one implementation rather than five.
- **A chain's token does not exist for native until the passthrough sends it**, so everything the app
  addresses by token has to be caught in java. `cancelRequest` and `cancelRequestsForGuid` call
  `PluginRpc` from **inside** stock's own `stageQueue` runnable, never before it: a cancel issued
  the instant `sendRequest` handed the token over would otherwise
  look up a chain `sendRequestInternal` had not armed yet, and the passthrough would then send a
  request the app cancelled. `bindRequestToGuid` has no such runnable in stock, so its hook runs on
  whichever thread the caller bound from and `onRequestBoundToGuid` does the `globalQueue` hop itself
  before it reads anything. A cancel collapses the chain and, when nothing has gone out yet, runs
  the caller's `onCancelled` itself - stock hangs that off the native callbacks the passthrough is
  what creates, and `FileLoadOperation` counts those down before a download is cancelled. A guid is
  remembered (bounded ring, since the app binds it before the request has even reached
  `sendRequestInternal`) and re-applied with `native_bindRequestToGuid` right behind the passthrough,
  or `cancelRequestsForGuid` walks straight past the request.
- **The bypass entry is a lease, not a token the first send consumes.** On CONNECTION_NOT_INITED
  stock re-sends the very request instance it was handed, with a fresh token and without invoking the
  delegate, so a bypass that ended at the first send would let the retry start a *second* chain over
  a request the first one still holds - every middleware twice, and the nested chain's `finalize`
  freeing the response the outer one is about to walk up. It is dropped by whatever proves no further
  send can follow: the delegate answering, or a cancel taking the request away from native. Not by
  the chain collapsing, which does not end the flight.
- **The request is freed by the chain, not by `sendRequestInternal`,** which frees it the instant it
  has serialized it and hands `upload.saveFilePart`'s `bytes` (and the secret-chat sends') back to a
  shared pool - gutting the writable view a stage parked in `await next()` still holds. `disableFree`
  goes on for the send and the free happens in `collapseChain`, once the handle scopes are released
  and the views are dead — but **posted to `stageQueue`, not done on `globalQueue`**, because that is
  the only thing that orders it behind a send the chain still has queued there. Same reason the send
  itself re-checks the flag the collapse sets: a collapsed chain must not put its request on the
  wire, or a cancel would be answered and the request sent anyway.
- **The takeover filter lives at materialization, not per api method.** `TlFilter` gates every read
  path (`TlHandles` live views, `TlJson` snapshots, and `setObjectField`, so a hidden field is not
  write-through), which is why a filtered surface can't be reached by adding an api. It carries a
  second rule for the same reason, and that is what `TlFilter.Policy` is: **draft text is hidden
  from a plugin without `account.read(draft)`**, keyed on the field's declared type (`Dialog`,
  `ForumTopic`, `savedDialog` and `updateDraftMessage` all carry a `DraftMessage`) rather than on
  the api that happens to be named after the scope. A policy is built once per plugin at
  `PluginRpc.attach` and threaded through every materialization; only its takeover half is lifted
  by `unsafe.disableApiFiltering`. It **must never
  mutate** the app's object: the app still needs the real text. `ApiFilter.HIDDEN_FIELDS` is matched
  by **constructor id**, since `TL_message_old7`'s canonical name is `message_old7` and a name check
  walks past every legacy variant. Redaction is same-length, so entity offsets never need adjusting.
  All four rules, install-time *and* call-time, are lifted by `unsafe.disableApiFiltering` and only
  by that.
- **A verdict recomputed per read must have sealed inputs.** Redaction re-derives the sender on every
  read, and `invokeRpc`/`interceptRpc` views are writable, so `ApiFilter.REDACTION_EVIDENCE_FIELDS`
  refuse writes *and* hand their peers out read-only — otherwise `m.from_id = null`, or
  `m.from_id.user_id = 0` one level down, reads the code in clear. Any future filter keyed on
  mutable fields needs the same treatment.
- A message's sender is `from_id`, **or `fwd_from.from_id`, or the dialog peer**: `from_id` is
  `flags.8?Peer` and the server omits it in a 1:1 dialog, backfilled by stock only on the cache-load
  path. Anything keying on the sender has to handle all three or it misses everything read straight
  off the wire. Stock's own `spoilLoginCode` keys on `from_id` alone and is *not* precedent — it only
  ever runs on a message stock synthesised locally with `from_id` set.
- **The pure surface stays pure, and that is what makes it safe.** `inu.Message` (`tl/message.rs`) and
  `inu.utils` (`tl/utils.rs`) are prelude JS over one argument each — a raw TL value, or nothing at all.
  Every `Message` getter is a *lazy* read through `raw`, so the takeover filter composes for free
  (`text` is whatever `TlFilter` lets `raw.message` say) and a wrapper over a dispatch-scoped view
  expires with it. Caching a field, snapshotting `raw` at construction, or deriving the sender from
  anything but the fields `ApiFilter.REDACTION_EVIDENCE_FIELDS` seals would each turn the wrapper
  into a way to read a redacted login code. `isSecret` keys on the `message_secret*` constructors
  **and** on `dialog_id` carrying `DialogObject`'s encrypted-dialog bit, never on
  `layer`/`seq_in`/`seq_out`: nothing in stock ever writes `Message.layer`, and a secret chat's
  *service* messages are ordinary `messageService`. The two modules share one implementation of TL
  name normalization and peer arithmetic, which is why `install_utils` hands its helpers to
  `install_message` rather than each having its own. **`utils.format*` does not reach the app's
  formatter** (there is no bridge to `LocaleController`, so it is english names, a 24-hour clock,
  and the device timezone only); `common.d.ts` says so where it used to promise the opposite, and
  the output is for display, never for parsing.
- **Every `on*`/`intercept*`/`register*` returns a `Disposer`, and they all behave identically**:
  dispatch snapshots its handler list up front (register mid-dispatch lands next dispatch, dispose
  mid-dispatch lets the in-flight run finish), keyed registrations replace and unkeyed ones stack,
  a second `dispose()` is a no-op, and registering after unload began is a no-op returning a no-op
  disposer. `engine/registry.rs` holds all of that once; do not hand-roll a second mechanism. Every
  disposal that the host has bookkeeping for gets an unregister upcall carrying **the callback id**,
  because the host tracks registrations and not plugins. Tokens are allocated before the entry
  exists (a host upcall needs the id and must be able to refuse without leaving anything behind) and
  are never reused, so a dispatch in flight for a disposed registration can't be taken for a later
  one's.
- **An `Account` is pinned to a slot for life.** `id`/`userId` are read at mint time and only
  `isCurrent()` moves, so handing one to a helper can't silently retarget on a switch;
  `withCurrentAccount` is the form that follows switches, and it re-runs when the slot is re-used by
  a *different* login (slot indices are recycled). `tg/account.rs` caches the slot list rather than
  asking the host per call, because every `onUpdate` payload and every `interceptRpc` dispatch
  carries an `Account`; a lookup that misses refreshes once before answering. `PluginApi` re-reads
  the list on `activeAccountChanged`/`mainUserInfoChanged`/`appDidLogout` and only fans out when the
  snapshot actually differs, since the last two fire for renames and premium purchases too.
- **The `Account` read surface is one gate, one spec vocabulary, one materialization point.**
  `tg/reads.rs` checks the `account.read` scope its op belongs to and then hands the host a *spec* -
  `S` (myself), `D<dialog id>`, `U<username>` - never a peer, so `PluginReads` parses no TL and the
  normalization has one implementation, the one `utils.js` already owns. A batch is **one crossing
  whose misses stay `null` in place**, which is why it answers one wire per element joined with a
  newline instead of a TL vector handle: a vector cannot hold a null, and `tlCopy` drops them. An
  empty batch still crosses, so the grant is checked exactly where every other read checks it.
  Everything minted is `mintForPlugin(readOnly = true)` - that *is* "everything read off an
  `Account` is read-only" - and the takeover filter needs no help here, living inside `TlHandles`.
  The getters live on **one prototype per engine**, parked on `AccountState` at install, because
  `dispatch_account` mints a handle for every update and every intercepted request; the slot comes
  off `this.id`, so a torn-off method fails by name rather than reading slot 0. `rpc` and `reads`
  share **one `TlViews`** (built in `nativeCreate`), or a write through an `invokeRpc` result would
  leave a cached field on an `Account` read stale, which `common.d.ts` promises it cannot.
- **A synchronous getter answers from memory or not at all.** Entities come from
  `MessagesController.users`/`chats` (concurrent maps), dialogs and messages from `dialogs_dict`/
  `dialogMessage`, which are `LongSparseArray`s the app writes from the ui thread and this reads off
  it deliberately - stock does the same from `MessagesStorage`'s queue, a *synchronous* getter
  cannot hop, and a lost race answers `null`, which is already a legal answer. So `getMessage` sees
  only the chat list's own messages and everything else is `getHistory`'s to fetch. `getUser('me')`
  falls back to `UserConfig.getCurrentUser()` rather than depending on whether the app cached
  itself. **Naming yourself needs `account.read(self)` on top of the read's own scope**, wherever it
  appears (`'me'`, `inputPeerSelf`, your own `User`): `getUser('me').id` and `getDialog('me').peer`
  are the identity `Account.userId` and `inu.accounts()` gate, and a plugin holding one handle per
  slot would rebuild that list out of them. The op's own scope is checked first on both sides, so a
  plugin missing both is told about the wider one; `PluginReads.allowsSelf` and `tg/reads.rs`'s
  `check_self_grant` have to keep agreeing. The **one exception is `OutgoingMessage.peer`**, which
  resolves `inputPeerSelf` through `account::self_user_id` with no check: `common.d.ts` promises
  reading it needs no grant, and a getter that throws there fails the *user's* send to Saved
  Messages and faults the plugin for it, which is worse than the disclosure under either reading.
  The ungated lookup reaches `sendmsg.js` as a factory argument, so nothing a plugin can hold
  reaches it, and `Account.userId` stays gated.
  `resolvePeer` is the one member that may go to the network and **only for a username** - an id
  with nothing cached has no `access_hash` anywhere on the device, and stock's `getInputPeer`
  inventing a zero one is the deferred failure `null` exists to avoid. "not cached"
  (`not-found`, worth a lookup) and "cached, wrong kind" (`invalid-argument`, never resolvable) are
  different answers, hence `buildInputPeer` returning three states rather than a nullable.
- **A paging cursor is a token, never an encoding.** `getDialogs`/`getTopics` hand back a `Cursor`
  the plugin can only give back: the offsets live in `tg/reads.rs`'s per-engine `Cursors` table, keyed
  by which list minted them, and JS holds `c<n>`. So opaque is structural rather than a promise, the
  `Cursor<List>` brand is enforced a second time at runtime, and a *forged* token can only ever name
  a cursor the same plugin already holds. The table is bounded (32, oldest dropped), which is the
  one behaviour the contract has to state. Never widen it into a signed blob: the payload is an
  `offset_peer` the host turns back into an `InputPeer`, and a plugin that could write one could
  page any peer it likes past the `resolvePeer` gate.
- **The async reads are the same materialization, one queue hop later.** `getHistory`/`getDialogs`/
  `getTopics`/`getUserFull`/`getChatFull` mint through the same `mintForPlugin(readOnly = true)` the
  getters use, so the takeover filter covers a *new fetch path* for free - `getHistory` on the
  service peer comes back `Login code: *****` because the filter lives in `TlHandles`, and that is
  pinned by a bridgeTest rather than inherited on faith. Two rules they add: a peer is resolved
  **before** anything is sent (an uncached id is `not-found` with no request, a wrong kind is
  `invalid-argument`), and nothing settles inline - `PluginReads.answer` posts to `globalQueue`,
  because settling from inside the JNI upcall is the same-engine re-entry that aborts the process,
  and it re-checks `plugin.engine === engine` since a reload restarts request ids. None of these
  responses owns a `NativeByteBuffer`, so unlike an intercepted response they need no `disableFree`.
  `getDraft` is deliberately **not** in this group: a draft is app state, so it stays synchronous.
  It is also the only text this surface hands over outside `TlHandles`, so it is built by snapshotting
  the `DraftMessage` through `TlJson` and renaming `message` to `text` rather than copying the field:
  a rule the materialization point gains later has to reach it too.
- **The write surface is one send path and one peer path, and that is where both of its rules
  live.** `common.d.ts` states two for the whole block: the request an op sends never re-enters the
  interceptors, and none of them reaches a secret chat. Neither is decidable in `tg/writes.rs` (a peer
  is still a spec there, and there is no request yet), so both are enforced once in `PluginWrites`:
  every op builds a request and hands it to **`send`**, which goes out through
  `PluginRpc.sendWithoutInterceptors` (the same bypass *lease* `invokeRpc` takes, held until the
  delegate answers because stock re-sends the very instance on CONNECTION_NOT_INITED), and every op
  names its peer through **`writePeer`**, which refuses an encrypted dialog id with `forbidden` -
  the write-side counterpart of `PluginReads.dialogIdOf` answering `null`. A write that skipped
  either would have to build its own request *and* its own peer. The delegate runs on `stageQueue`,
  which is both where stock answers one and the only queue `processUpdates` may run on, so the app
  applies what a plugin sent exactly where it applies what the ui sent; only the engine settle hops
  to `globalQueue`. `PluginReads`'s spec decoder (`dialogIdOf`/`buildInputPeer`/`describeSpec`) and
  its `mintForPlugin(readOnly = true)` are `internal` rather than private for this: they are
  properties of the `Account` handle, not of reading through it, and a second copy on the write side
  is how the secret-chat refusal would come apart.
- **The `InputPeerLike` resolver is `utils.js`'s, and both surfaces consume it there.** `toSpec` and
  the argument vocabulary around it (`toSpecList`, `toMessageId(s)`, `toOptions`, `toCount`,
  `slotOf`) live with the peer arithmetic `message.js` already shares, so `reads.js` and `writes.js`
  normalize a peer the same way by construction rather than by agreement. A write is an op that
  passes the same `S`/`D<id>`/`U<name>` spec; nothing else about a peer ever crosses.
- **The `Account` prototype is a two-link chain, and taking it is not reading it.** `tg/writes.rs`
  installs after `tg/reads.rs` and chains its own frozen prototype behind the read one, so one handle
  answers for both families and neither file knows the other's members. The reads prototype is taken
  out of `AccountState` (`account::take_prototype`) rather than read, because a `Persistent` has no
  `Drop`: overwriting the account's without releasing it first leaks a GC root and aborts
  `JS_FreeRuntime`. The chaining happens *inside* `writes.js`, before its `Object.freeze` - a frozen
  object's prototype can no longer be set.
- **Content a plugin hands a send becomes a file before it crosses.** Stock's uploader takes a path
  and Kotlin cannot read a `Blob`'s backing (that is rust's, deliberately), so a `Blob` or a
  `Uint8Array` in a file position is staged into the engine's own spill directory by `tg/writes.rs` and
  the *path* is what the host gets (`F<json>`, alongside the name and mime a `File` carries). The
  staged copy is deleted by `take_pending`, which every exit goes through, so it outlives its
  transfer by nothing. Staging is native work no interpreter deadline can interrupt, so it carries
  its own bound in the unit the contract states: `TRANSFER_LIMIT_BYTES` (256 MiB), refused before
  the copy starts. A blob whose backing is gone is `handle-expired` rather than "not a file",
  which needs the *class* check (`Class::<BlobHandle>::from_value`) and not a duck type - a plugin
  can reassign `globalThis.Blob`. `{ path }` is gated where the contract puts it: `unsafe.fs` for an
  absolute path, `fs` for a relative one - and the relative form then throws `unsupported`, since
  the scoped directory it resolves against arrives with `inu.fs`.
- **Stock's loader does the transferring, and `PluginMedia` only does the bookkeeping.** It is what
  knows about datacenter migration, cdn redirects, refreshing a stale file reference (hence the
  *message* being passed as `parentObject`, which is why a download's argument crosses as a live
  handle rather than a snapshot) and the per-account file-path database that has the final say on
  where media lives. So a plugin's download is the app's download and lands where picking it in the
  ui would have. Progress arrives on `NotificationCenter`, keyed by the name stock gave the file,
  observed and released on the ui thread; every report hops to `globalQueue` and is handed straight
  to `tg/progress.rs`, which is the *only* place coalescing happens. A completed transfer ends on
  `total`/`total` and a failed one flushes whatever the window withheld, so the numbers a plugin is
  left on are the transfer's own. It follows that **a transfer's own rpcs are not the plugin's
  send**: `upload.saveFilePart`/`getFile` reach `interceptRpc` like any other app request, because
  one operation serves every caller waiting on that file and no bypass could say whose it is.
  `common.d.ts` states the carve-out where it states the rule it carves out of.
- **A transfer is settled by a notification or not at all, so `PluginMedia` owns every observer it
  registers.** Stock's `FileLoader.uploadFile(path, callback)` is deliberately unused: it matches
  the notification's path with `==` and a second upload of a path already in `uploadOperationPaths`
  never starts an operation of its own, so its callback is handed a different `String` instance than
  the running operation reports under and never fires - one waiter per path, silently. Listening for
  `fileUploaded`/`fileUploadFailed` here instead settles every waiter on the one operation and puts
  the observer where `PluginMedia.detach` can end it. That detach is not optional: there is no event
  for a transfer stock declined to start, so an unloaded plugin's observer would sit on the centre
  for the life of the process holding the dead engine, and it runs from **both** places
  `PluginManager` drops a plugin (`stop`, and the load-failure path).
- **`onUpdate` filtering is per registration and happens before materialization.** The constructor
  list is required (`common.d.ts`), so `PluginRpc` mints a handle only for a plugin some
  registration of which named that constructor, and the engine narrows again per callback. Two
  families never reach a plugin at all: `updateServiceNotification` (a takeover rule, lifted by
  `unsafe.disableApiFiltering`) and the four **secret-chat** updates (`updateNewEncryptedMessage`/
  `updateEncryption`/`updateEncryptedChatTyping`/`updateEncryptedMessagesRead`), which nothing
  lifts - the same rule `PluginReads.dialogIdOf` enforces by refusing an encrypted dialog id.
- **The demuxed events are `onUpdate` registrations, not a second stream.** `onNewMessage`/
  `onMessageEdited`/`onMessageDeleted` register for the fixed constructor lists in `tg/rpc.rs`'s
  `DEMUX_EVENTS`, holding the listener `events.js` builds around the plugin's callback — so they
  inherit the arrival paths, `rememberDispatch`'s once-per-arrival dedup, the takeover filter and
  the `Disposer` rules for free, and a plugin holding both forms over one constructor is dispatched
  **once** and fans out in its own engine. Adding a dispatch site for them would be the bug.
  Their **grant scope is the event name, never the constructors**: `onUpdate(new_message)` and
  `onUpdate(updateNewMessage)` do not imply each other in either direction, which is why the
  register upcall carries a `scope` string the host gates on instead of deriving one from the types,
  and why `UpdateListener` remembers which scopes authorized a plugin for a constructor rather than
  re-deriving that at dispatch. `dialogId` is **null for a non-channel deletion**:
  `updateDeleteMessages` carries no peer at all and resolving one means a query against the app's
  message database that the fan-out cannot wait on.
- **`interceptUpdate` takes the whole arriving batch over, because that is the unit the app applies
  and the unit it is blocked on.** `PluginRpc.onUpdates` answers *true* and stock's `processUpdates`
  early-returns; the walk runs on `globalQueue` and the batch is handed back by calling
  `processUpdates` again on `stageQueue`, marked in `takenOver` so it is not taken over twice. Three
  things follow. The budget is **one 2 s per batch**, not per update — a per-update budget would let
  one arrival hold the stream for its size times the budget — and on expiry everything undecided is
  **delivered**, as is anything a middleware threw on: `drop` is the one verdict that desyncs pts,
  and producing it out of a stall or a plugin's typo would lose the user's messages. Batches queue
  **per account behind whichever is being walked**, claimed or not, because the app applies updates
  in arrival order and a plugin must not be able to reorder them. And observation runs off the
  *hand-back*, so `onUpdate` sees exactly what the app sees and a dropped update never happened for
  it either. The `updateServiceNotification` and secret-chat rules are `chainFor`'s, the same two
  `dispatchUpdate` applies. The known cost, accepted rather than overlooked: a batch nothing claims
  is only left with the app while that account is *idle* (`onUpdates` answers false for it), so once
  any batch is being walked every arrival behind it pays the `globalQueue`/`stageQueue` round trip
  and up to the full 2 s. That is the price of arrival order, and it is bounded by the budget rather
  than by the plugin. What is *not* accepted is a hand-back that throws: `deliverBatch` wraps
  `deliverable` and `processUpdates` and advances the queue from a `finally`, because the head of a
  per-account fifo that never retires stops that account receiving anything for the life of the
  process, and nothing may escape onto `stageQueue`, which every account's update pipeline runs on.
- **The two compressed short forms are the one arrival `interceptUpdate` cannot answer in place.**
  The app applies `updateShortMessage`/`updateShortChatMessage` from their own fields and never
  builds the `TLRPC.Update` a middleware was handed (`normalizeShortMessage`'s synthetic
  `updateNewMessage`, the same one `onUpdate` gets), so a rewrite has nowhere to land. `deliverable`
  therefore compares an unfiltered `TlJson` snapshot taken before the walk against one taken after,
  and **substitutes** the `TL_updates` the server would have sent only when something actually
  changed: stock's own short-form branch prefetches the sender and does its own pts bookkeeping, and
  leaving that path for a plugin that only *looked* would be a behaviour change bought for nothing.
  A `TL_updates` that lost every update is still handed over, empty — it carries the seq and date
  advance, and withholding that desyncs strictly more than the drop already did. The substituted
  batch carries the `pts`/`pts_count` and the date and **nothing else**: stock groups a `TL_updates`
  by `getUpdatePts`/`getUpdatePtsCount` before it looks at anything, and only then does
  `processUpdateArray` resolve every peer the message names (the batch's own `users`, then
  `MessagesController.getUser`, then `MessagesStorage.getUserSync`) and answer a miss by returning
  false, which its caller turns into `needGetDiff`. So an uncached sender is caught and backfilled
  exactly as on the short-form branch, `users`/`chats` stay empty on purpose, and falling back to
  the compressed form over a cache miss would only throw the rewrite away. The difference
  catch-up (`onDifference`) stays observation-only; it is applied by its own code path, which
  `common.d.ts` says out loud.
- **`interceptSendMessage` is a narrowing of the `interceptRpc` chain, not a chain of its own.**
  `sendmsg.js` wraps the plugin's verdict middleware into an ordinary one over `tg/rpc.rs`'s
  `SEND_METHODS`, so the 10 s budget, the plugin-list order, `collapseChain`, the cancel handling
  and the "a plugin's own send never re-enters" lease are the ones already tested — `'send'` is
  `next(request)` and `'drop'` is an `inu.RpcError(-1000, MESSAGE_DROPPED_BY_PLUGIN)` returned, which
  is why the app surfaces it as a failed send. Adding a dispatch site for it would be the bug. The
  grant is the **api's own**, carried as the `scope` argument `on_register` now takes for exactly
  the reason `on_update_register` takes one: `interceptSendMessage` and
  `interceptRpc(messages.sendMessage)` do not imply each other in either direction. Anything that is
  not one of the two verdicts is treated as a throw and **drops** — the opposite direction from
  `interceptUpdate`, because a failed send is visible and retryable and a silently sent one is not.
- **`OutgoingMessage` reads its shape off the method, never by probing the request.** A flag-gated
  field whose bit is clear is omitted from reads exactly like one the constructor never declared, so
  `'silent' in raw` cannot tell "this send is not silent" from "an edit has no such field" -
  `sendmsg.js`'s `SHAPES` table is what makes `silent`/`reply`/`media` answer correctly per method.
  `media` may be **replaced but not resized**: changing the count means sending a different method
  than the app is awaiting a response type for, which `next()` refuses anyway, so it is `unsupported`
  where it is decidable and `common.d.ts` points at `drop` plus `account.sendMedia` instead.
  Retargeting `peer` goes through the account handle's own `resolvePeerCached`, so it costs
  `account.read(peers)` and needs no bridge of its own.
- **The difference catch-up is a second dispatch site, because stock never routes it through
  `processUpdates`.** `updates.getDifference`/`getChannelDifference` walk their own
  `other_updates`/`new_messages`, so `PluginRpc.onDifference` is hooked at the **top of each one's
  `stageQueue` runnable** — above the point `getDifference` appends the secret-chat messages it
  decrypted, which plugins have no business seeing. A `new_messages` entry is a bare `Message`, and
  is wrapped in the update the server would have sent had the client been online
  (`updateNewChannelMessage` when its peer is a channel, else `updateNewMessage`, pts 0 since a
  difference carries one state for the whole batch): the alternative is a plugin listening for
  `updateNewMessage` seeing nothing at all for anything that arrived while it was offline. Two
  dispatch sites are safe only because `rememberDispatch` keys on object identity — for the
  difference that key is the `Message`, since the update around it is one we synthesised.
- **The update hook fires more than once per arrival, and before the app has applied anything.**
  `processUpdates` parks a batch whose pts/qts/seq doesn't line up (`updatesQueuePts`/`Qts`/`Seq`/
  `Channels`, sometimes repacked into a fresh `TL_updates` around the *same* `TLRPC.Update`
  instances) and re-feeds it later as `processUpdates(_, true)`, so `onUpdates` sees one arrival
  twice; `fromQueue` alone can't tell them apart, because a wrapper is also how sub-updates the
  first pass could not apply come back. `PluginRpc.rememberDispatch` therefore dedups on **object
  identity** (bounded ring, no `TLRPC` class overrides `hashCode`), keyed on what stock would re-feed
  — the `Update` for a real batch, the batch itself for the `updateShort*` forms, whose normalized
  `updateNewMessage` is a new instance every pass. Unpacking must stay **synchronous** in the hook
  (the batch is only whole on entry), while the fan-out takes a `stageQueue` hop first, so plugins
  read the objects after `processUpdateArray` backfilled them rather than racing those writes — the
  two queue hops are also the only happens-before edge to `globalQueue`.
- **`inu.canvas` records; the host only rasterizes.** A drawing op appends to a per-canvas command
  buffer in `draw/canvas.rs` and the buffer crosses once, at the three points that need real pixels:
  `convertToBlob`, `getAverageColor`, and any op naming *another* canvas as a source - which flushes
  that canvas first, because the spec promises a snapshot and a stale bitmap is the wrong picture.
  `common.d.ts`'s consequence is stated where a plugin can act on it: a rasterizer failure surfaces
  at the read, not at the `fillRect`. A command is **self-contained** (its own transform, its own
  fully-described paint) rather than mutating a paint the host holds between commands, which is what
  makes a `CanvasGradient` render with the stops it has *when it is drawn with* - the spec's rule -
  without the host owning a state machine to get it right. **A recorded command names an image by
  id, so the buffer holds a reference to every source it names** (`Encoder.sources`, dropped after
  the host call, and `Encoder::paint` carries a scratch paint's along with its bytes): the id is
  resolved at replay, and one that resolves to nothing fails the *whole* flush, silently discarding
  every command after it too. `dispose()` therefore flushes the live surfaces first and only then
  frees the bitmap, which keeps `canvas.d.ts`'s promise that the memory is back when it returns.
- **Everything is emitted under the current transform, in user space.** `draw/geom.rs` keeps the path in
  device space because that is what the canvas spec says a path *is* (`moveTo`, `translate`,
  `lineTo` puts two points in different user spaces and one device space), and every draw hands over
  the transform plus the path pulled back through its inverse. Not a detail: a stroke's pen, a
  gradient's coordinates and a pattern's tiling are all defined in the user space of the draw, and a
  device-space path under the identity gets all three wrong. The two things the spec defines in
  *device* space - the shadow's offset and its blur - are therefore pushed the other way through the
  same inverse at emit time. A transform with no inverse flattens the canvas onto a line, so those
  draws are dropped rather than approximated.
- **Every curve is decomposed in rust, and `arcTo` is why.** The canvas `arcTo` is tangent-based and
  is *not* `Path.arcTo`, so a host handed the canvas arguments would be computing the tangent circle
  itself - in java, on a device, where nothing can test it. `arc`/`ellipse`/`roundRect` follow, plus
  one more reason: an ellipse under a rotation or a non-uniform scale is not an oval the platform can
  name. `draw/geom.rs` emits move/line/cubic/close only, which is also the whole of what `PluginCanvas`
  has to understand. **A composite other than `source-over` draws into a `saveLayer`**: `source-in`,
  `copy` and the rest are defined against the whole destination, not against the shape.
- **Three things the platform cannot do are refused, and `canvas.d.ts` names all three**: the
  separable blend modes below api 29 (decided in rust, off one `OP_CAPABILITIES` answer read at
  install, so the refusal is testable), a non-concentric `createRadialGradient` (no two-point conical
  shader exists; the concentric case is exact, by folding the inner radius into the stop positions),
  and a `createPattern` repetition other than `repeat` below api 31, where `TileMode.DECAL` arrives.
  Approximating any of them renders the wrong picture and tells nobody.
- **`inu.fs` normalizes and only then asks whether it is allowed** (`io/fs.rs`'s `resolve_path`), and
  that order is the whole security property: a containment check on the path a plugin typed passes
  for `a/../../etc` (a string prefix), for `a//..//b` (a `..` behind a doubled separator) and for a
  symlink the plugin planted in its own directory. `walk` therefore takes one component at a time,
  popping on `..`, skipping `.` and reading through every link it meets (40 hops, then
  `invalid-argument`), and containment is `Path::starts_with` on the *result* — component-wise, so
  `<root>-evil` is not inside `<root>`. `..` pops what was already resolved rather than being
  collapsed lexically, so a link followed by `..` lands where the kernel would have gone. The root
  is canonicalized once at install, or a data directory reached through a link makes every op read
  as an escape. Landing outside is **`not-granted` naming `unsafe.fs`**, not `forbidden`: a grant
  exists that would allow it. `unsafe.fs` is the same code with containment and the quota off (and
  relative paths still landing in the plugin's own directory), and the gate asks for whichever token
  the host installed the engine with, so a manifest that lost the grant is still refused.
- **The whole of `fs` is native, and that follows from `Blob`.** There is no upcall: the host hands
  over one directory, one quota and one flag at `nativeInstallFs` and nothing else crosses.
  `fs.write` takes a `Blob`, and routing those bytes through an upcall would put every written
  megabyte on the app-wide *Java* heap — the lever the per-plugin ceilings exist to take away — so
  blob→file is a 256 KiB-chunked copy inside rust. `fs.read` carries `blob.bytes()`'s 16 MiB
  ceiling for the same reason `bytes()` does, refused before the read. The quota is checked before
  a byte is written, charged by *difference* on a rewrite (or a file at the cap could never be
  replaced), not charged at all for a `move` (inside the scope it cannot add, outside it there is
  no cap), and cached with the cache dropped by anything whose delta is not obvious. The directory
  is `filesDir`, never the cache area (`PluginFs`): `fs.d.ts` promises durability, and it is the one
  plugin-owned tree that outlives its engine — only uninstall clears it.
- **`fetch`'s two egress rules can only be enforced where the connection is made**, which is why
  `io/fetch.rs` does a pre-flight and `PluginFetch` does the deciding. **Every redirect hop is
  screened, not just the url the plugin passed** (`instanceFollowRedirects` off,
  `PluginFetch.runExchange` walking the chain itself): a client that follows them checks the grant
  once, which turns any open redirect on an allowed host into a proxy to every other, and the
  response then looks like it came from the host that was allowed. **Addresses are screened after
  resolution, per hop, and *every* answer must be public** — the resolver picks per connection, so
  taking the first address makes the refusal a coin flip; a host inside a granted domain can still
  point at 127.0.0.1 or 169.254.169.254. The residual DNS-rebinding window between that resolution
  and the socket's own is documented rather than pretended away: closing it needs a pinned-address
  socket with `Host`/SNI set by hand. A url whose host is not what it reads as (userinfo, a scheme
  other than http/https) never crosses at all, and since a hop's url is a *`Location` header*, that
  same check is what keeps `302 file:///...` on a granted host from becoming a local file read.
  `Flight.cancelled` is read at the top of every hop and again once its name has resolved, because
  the socket `Flight.cancel` disconnects does not exist yet during the queue hop, the pool dispatch
  or the name resolution, which is most of the window an abort lands in. Both reads are in
  `runExchange` rather than in `send`, which is private and opens a real connection: a check only
  the socket path can reach is a check no test can. There is **no http client in the crate**
  and there will not be one; the app already has a network stack.
- **A prelude is not a trust boundary, so a refusal it states is stated again where it is acted on.**
  `fetch.js` runs in the plugin's own realm, so the header rules `common.d.ts` promises (a name that
  is an rfc7230 token, none of the ones the transport owns, no control character in a value) are
  re-applied in `PluginFetch.Spec.parse`, which is the side that opens the socket - android's
  `HttpURLConnection` is okhttp, which has no restricted-name list of its own and supplies `Host`
  only when it is absent, so a forged `Host`/`Transfer-Encoding` really does go on the wire. For
  the same reason **the prelude hands the spec across as an object and rust serializes it**
  (`api::json_stringify`, never `globalThis.JSON`, which is writable and shared): a spec the plugin
  stringified itself is one none of the above ran on. The rule generalizes - a security check
  written in prelude js buys the error message, never the refusal.
- **A fetched body is a `Blob` over a file the host wrote**, minted with `blob::mint_app_file`, so a
  download never touches either heap, `response.blob()` costs nothing, and `bytes()`/`text()`
  inherit that type's ceilings and its `handle-expired`. A *request* body does cross, bounded by
  `blob::BUILD_LIMIT_BYTES`. Bodies live under the engine's own `PluginBlobs` directory and die with
  it, with a per-plugin byte budget on top since nothing else reclaims one before then, so
  `dispose()` on a fetched body ends the handle and reclaims nothing, which is what `common.d.ts`
  now says. **Only a body somebody is handed stays charged**: a hop is read before the chain can
  know it is a redirect, so `Hop.discard` and `Delivery.drop` credit the bytes back where the file is
  deleted. A counter nothing decrements is one a *remote server* drives to the ceiling, by answering
  every request with a 302 and a large body until every later `fetch` is `quota-exceeded` with zero
  bytes on disk. The prelude
  owns `timeout`/`AbortSignal` and whichever of the three outcomes lands first wins — a response
  that arrived just before an abort is not un-settled by it — and every failure **rejects**,
  including the grant refusal decided before anything is sent, because `fetch(...).catch(...)` is
  the only shape anyone writes.
- **A settings page is a model the engine owns and the host renders, and every crossing is two
  queue hops.** Android ui may only be touched on the ui thread and an engine may only be entered
  from `globalQueue`, so `PluginSettingsActivity.requestRender` posts to `globalQueue`, `uiRender`
  answers one JSON tree, and the model is applied back on the ui thread; an interaction goes the
  other way and re-renders from the same runnable. What crosses is therefore *whole renders*, never
  a live view - `ui/pages.rs` holds the `items()` function, `onClose`, the bottom button and one
  `Persistent` per callback slot, and every render drains the slot table and allocates fresh
  (monotonic) ones, so a stale event is a no-op rather than a misdirected call.
- **Row identity is the engine's, and that is what a `UIAnchor` is.** `alloc_row_key` stamps every
  element with a `key` (`i:<id>#n` for an explicit `id`, else `t:<type>:<text>#n`) and Kotlin hashes
  *that* into the differ's int rather than deriving its own, or the anchor and the list would
  disagree about which row is which. An anchor carries the page and that key, **never a slot and
  never a `View`**: the auto-invalidate that follows every callback has already reallocated the
  slots by the time an `await`ed continuation resumes, and a row's view is recycled onto other rows.
  So `openMenu` resolves the view at open time, and the two ways it stops working answer
  differently - a **disposed page is `handle-expired`** (decided in rust, so it is testable), while
  a live page whose row is not on screen simply does not open the menu and settles it dismissed,
  which is the ui thread's to decide and is the same answer as tapping outside. A menu item's own
  `onClick` is dispatched with no arguments, which is what makes "a menu cannot open a menu" true
  rather than merely documented.
- **A page is torn down by four different things and only one of them is the plugin's throw.**
  `dispose()` and a `transient` page's close free the definition (the transient one *after*
  `onClose` returns); a plugin unload closes any view still on screen, `PluginUi.detach` running
  from both places `PluginManager` drops a plugin and using `removeSelfFromStack` because a plugin
  page can be buried under one the user opened from it; and the engine's own `dispose` releases the
  roots. What is left of a disposed page is a frozen view, so the host asking it for one more render
  is **`LEVEL_ERROR`, not `fault()`** - the app naming a page the engine no longer has is the app's
  bad day, and disabling a plugin for disposing its own open page is the bug that reads as a plugin
  bug. A settings callback, an `items()` and an `onClose` that *throw* stay faults. Without a ui at
  all (a process a push notification woke) `openPage` is a no-op, `prompt` resolves `null` and
  `dialog` answers `'dismissed'`; a configuration change keeps the page but not its views, so
  `createView` drops every cached one and re-attaches the sticky button from the surviving model.
- **An icon is a description, never a drawable, which is what keeps it off the ui thread.**
  `ui/icons.rs` mints one spec string - `r<drawable name>` or `s<svg source>` - and that is all that
  ever crosses, inside whatever element carries it; `PluginIcons.resolveDrawable` turns it into a
  real `Drawable` in `bindView`, on the ui thread, against *that cell's* context. So minting an
  icon needs no `Activity` (a resource-table lookup and an svg parse, both on `globalQueue`), a
  rotation or an icon-pack change re-resolves the same spec with nothing to invalidate, and the
  drawable is fetched through `context.resources` rather than `Context.getDrawable`, which resolves
  against the base `ContextImpl` and walks straight past `LaunchActivity`'s `IconsResources`
  override. `inu.icons.common` is sugar over a *stock* drawable name (never a `_solar` variant, or
  the icon opts out of the pack it is meant to follow), so there is one table and it is in rust; a
  cargo test pins it against the union `common.d.ts` declares, and the bundled `icons test` plugin
  asks the real app for all 31 because only a device can answer whether the asset still ships. The
  host is asked one thing, `onIconResolves`, and answers a bool - **which error a plugin sees is
  decided in rust**, so `not-found` (nothing answers to that name) and `invalid-argument` (that is
  not a name) do not depend on the host. Two rules the host cannot enforce and rust therefore does:
  a resource name is a bare `[A-Za-z0-9_]` identifier, since `getIdentifier` also takes a qualified
  `package:type/name` and would let a plugin reach any resource of any type in any package; and an
  svg is at most **64 KiB** with **no `<!DOCTYPE>`**, because it is handed to the platform's own
  SAX reader and a doctype is the one xml construct that names external files or expands to more of
  itself. Both are re-checked wherever a spec is *read* (`opt_icon`), not only where it was minted:
  the object carrying it is an ordinary one a plugin can build itself.
- **An action row is host state; only its label needs the engine.** `inu.register*Action`
  (`ui/actions.rs`) registers through an upcall, so `PluginActions.rowCount(kind)` answers *how many*
  rows a menu has synchronously on the ui thread, and only `text`/`visible` cost a `globalQueue`
  hop. That split is the whole design: a menu built by the gesture that opens it (the message menu)
  reserves its rows at their measured size, kicks one render off, and parks the show until it lands
  or `RENDER_BUDGET_MS` expires - never growing under the user's finger, since `AndroidUtilities
  .runOnUIThread` always posts, so a render can never land inside the turn that asked for it. A
  menu built long before it opens (the chat and profile headers, the drawer) just redraws. The
  ordering `common.d.ts` promises and the liveness a stale menu needs are the *same* list: rows come
  out in `PluginManager.plugins()` order, and an engine missing from it is neither asked nor
  dispatched to - which is why a row is owned by the `QuickJs` that drew it and never by the
  `Plugin`, a reload restarting tokens at 1. Menu-item ids come from one space
  (`PluginActions.OPTION_BASE`, far above stock's and the fork's), so every attach point routes a
  tap the same way. `ActionRegistry` in `:InuCore` holds the per-owner-per-kind bookkeeping and the
  8-row cap so both are unit-tested, and `PluginActions` itself is in the bridge harness. The cap is
  consulted **per id, not per token**: `ui/actions.rs` allocates the replacement's token and registers
  it *before* retiring the one it displaces, so a registry that counted tokens refuses the keyed
  re-registration that is the documented way to change a row - leaving a plugin at the cap unable to
  update any of its rows for the rest of the process. It reaches the plugin as `quota-exceeded`,
  which is what a limit is called everywhere else here.
  Secret chats are refused once, in `PluginActions.Surface`, the same rule
  `PluginReads.dialogIdOf` enforces for reads.
- **The editor rows are the second gesture-built menu**, and the only kind whose surface is a live
  object rather than a description. `ChatActionsHelper.inu_showSendPreview` parks
  `MessageSendPreview.show()` behind one render exactly as the message menu parks its popup, and
  opens a `PluginActions` editor surface the composer's `replace`/`send` reach - closed by
  `inu_onSendPreviewDismissed`, because a callback settling after the sheet is gone has nothing to
  write into. `send` goes through `ChatActivityEnterView.sendMessage()`, the send button's own path,
  so schedule mode and the premium conversions still happen; `sendMessageInternal` is `protected`
  and stays that way (`PeerStoriesView` overrides it, so promoting it is a second stock file).
- **The navigation event is a diff, and the diff is `:InuCore`'s.**
  `INavigationLayout.setFragmentStackChangedListener` is one slot and `LaunchActivity` already
  claims it, so the patch **chains** at that call site instead of taking it, and the listener
  carries no payload at all - what happened is derived by keeping the previous stack and diffing.
  `PluginScreens` keeps it as `ScreenRef` **values**, never fragment instances: an activity
  destroyed and recreated comes back holding different objects describing the same screens, and
  instance identity would report every configuration change as a navigation. `ScreenStack.diff`
  names the verdict - old stack a prefix of the new is `push`, new a prefix of old is `pop`, equal
  depth is `replace`, anything else takes the label its depth implies - and **an unchanged top is
  nothing at all**, which is the dedup a rebuild and a `removeFragmentFromStack` on something
  buried both land in. It is pure logic over a list, so it lives in `:InuCore` and is unit-tested
  there; `PluginScreens` itself reaches `LaunchActivity` and the concrete fragment classes and is
  in `bridgeExcluded`. The snapshot is published on the ui thread and read from `globalQueue`
  (`@Volatile`, immutable list) and kept up to date **even with no plugins loaded**, or one
  installed later would answer `null` until the user navigated. `ScreenChange.stack` is a js getter
  memoized in a closure (`ui/screens.rs`), so a plugin that only reads `screen` never mints an
  `Account` per stack entry; `dialogId`/`topicId` cost `account.read(dialogs)` and are **omitted,
  not refused**, since `type` already says whether there was one to give. A secret chat is an
  ordinary `ChatActivity` whose `dialog_id` is `makeEncryptedDialogId`, so `describe` drops that id
  to 0 and the field goes away with it - the same refusal `PluginReads.dialogIdOf` and
  `PluginActions.Surface` make, on the one surface that had not been counted among them.
- **`openUrl` opens a page, and the clipboard is two grants.** `api::screen_external_url` allows
  **http/https only**: every other scheme names an *action* rather than a page (`tg:` is the app's
  own deeplink surface, `intent:` names an activity and its extras, `file:`/`content:` name the
  storage the sandbox exists to gate), and a scheme allowlist is the only check that can tell them
  apart before something else has acted on the string. Userinfo, a backslash in the authority,
  whitespace and control characters are refused for `fetch`'s reason: a url that is not the host it
  reads as, re-parsed by whatever receives it. The host side is one `ACTION_VIEW` and deliberately
  **not `Browser.openUrl`**, which appends the account's `autologin_token` on an autologin domain -
  precisely what the takeover filter strips out of `config`. `clipboard.read` and `clipboard.write`
  are separate grants two tiers apart, and the read hands over the clip's own text and **never
  `coerceToText`**, which would dereference a `content://` uri through the app's permissions and
  turn "read what the user copied" into "read any provider telegram can reach".
- **The notification centre hands over scalars and nothing else, and that is a refusal rather than
  a gap.** `PluginNotifications` is where a payload is decided: numbers, strings and booleans cross,
  everything else is `null`, because there is no runtime value on the other side to make a java
  object into until `unsafe.jvm` ships and a class name in its place would be an approximation of
  the event rather than the event. `android.notification-center.d.ts` narrows the handler argument
  types to say so (`NotificationArg`), while `NotificationCenterEventsMap` keeps describing what the
  *app* posted. The encoding happens **inside the observer**, not after the queue hop: the array is
  stock's and observers downstream of ours rewrite it in place. The event vocabulary is read by
  **reflecting over `NotificationCenter`'s own `public static final int` fields**, so a rebase moves
  it and there is nothing to regenerate; an unknown name refuses the whole registration, which a
  plugin could not otherwise tell from an event that never fires. Delivery hops to `globalQueue`
  like every other engine entry, and **every observer is torn down in `detach`** - the centre holds
  its observers strongly and one of these closes over the engine, so a registration left behind
  keeps an unloaded plugin's engine alive for the life of the process (the bug `PluginMedia` hit).
  `notifications::dispose` frees only the GC roots; the host's observers are `detach`'s, because an
  engine being destroyed cannot answer an upcall.
- **The `iter*` reads are the paging reads, called through the prototype.** `iterDialogs`/
  `iterHistory`/`iterTopics` are async generators in `reads.js` that page by calling
  `proto.getDialogs.call(this, ...)` and friends, so argument normalization, the grant gate and
  materialization have exactly one implementation rather than an agreeing second one. That also
  means an iterator inherits the `Cursors` bound (32 per engine, oldest dropped): steps interleaved
  with that many other paged reads end it with `invalid-argument`, which is a real case and is
  pinned as one. `iterHistory` holds no cursor - the offset is ours to compute - so it carries its
  own termination: a page shorter than the batch, and an `offset_id` that did not move. `limit` is
  a total and `batchSize` is per request (default 100).
  `resolvePeerMany` is `resolvePeer` in a scheduler (8 in flight): the cache answers first, and a
  miss that comes back **`not-found` is `null` in place while anything else fails the whole batch**,
  since a `null` there cannot be told from an unknown peer. Its grant check is its own native
  (`checkPeers`), or an empty list would be answered without the gate every other read runs.
- **`inu.jvm` hands over the app, so the scope list is the only boundary and every crossing checks
  it on the side that owns the data.** `unsafe.jvm` takes `ScopeMatch.NAMESPACE` scopes; `platform/jvm.rs`
  checks the *name* `cls` was handed before anything crosses, and `PluginJvm` checks the **runtime
  class of every reference it mints** and the **declaring class of every member it reaches** - a
  `Class` value by the class it *names*, not by `java.lang.Class`. Which class a name resolves to,
  what an overload takes and what a member declares are facts about a heap rust cannot see, which
  is why the whole of it is Kotlin and in `bridgeTest`. Two rules are call-time rather than scope
  entries, because **an unscoped grant satisfies every scope check**: `loadDex` asks for
  `unsafe.jvm(*)` (dex runs with the app's permissions and never crosses this bridge again), and
  the engine's own package is `forbidden` outright - a reflected call runs on `globalQueue` inside
  a JNI upcall, so reaching `QuickJs`/`Plugin`/`PluginManager` from one is the `BorrowMutError`
  abort, not an error. That is a guard against one hop, not a boundary: `java.lang.reflect` walks
  around it and the contract says so. A `runnable` is the only java object this api mints and it is
  exempt from the class check (the engine made it, at the plugin's request) while every *member* of
  it is refused, or `this$0` would walk back to the session; its callback is **posted** to
  `globalQueue`, so java calling `run()` inside a reflected call cannot re-enter the engine, and a
  `plugin.engine === engine` re-check keeps a `Runnable` java kept hold of from firing into a
  reload's successor. Handles are per engine and die with it (`handle-expired` after), released
  early by a `FinalizationRegistry` so a loop over rows does not pin the app's objects; the id lives
  in a `WeakMap` in `jvm.js`, not a symbol property, so a handle carries nothing to copy. Values are
  bounded at 1 MiB in either direction because a string costs several times its size on the *app's*
  heap, and a js number is an integer or a double and nothing narrower, so the **parameter type
  decides** and one that does not fit is refused rather than truncated (a `long` past 2^53 crosses
  as a `bigint`). `defineClass` is a dex generator *and* the synchronous answer for java the
  bullet on engine entry says this engine has none of, `callSuper` only means anything inside one,
  so both throw `unsupported` and are `@not-implemented`.

## Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu` (`InuHideStories`).
- Subtitle/info strings: same key + `Info` suffix (`InuHideStoriesInfo`).
- Access: `LocaleController.getString(R.string.InuXxx)`.

## Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- New asset dir → add path to `scripts/config.ts` → `forkSyncFiles`.
- Icons: lucide pre-bundled; selection list in `scripts/config.ts` → `ICON_SELECTION`. Tabler pack preferred for visual consistency.

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
