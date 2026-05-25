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
11. **No LSP, no local build.** Don't try to compile.
12. **Debug logs use `android.util.Log.d`**, not `FileLog`.
13. **Prefer non-`_solar` icons** when an alternative exists.

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

Propose a patch name for every newly made patch — don't touch stgit yourself.

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
| `onResume(LaunchActivity)` | `LaunchActivity.onResume` | monet refresh, crash sheet |
| `onUpdate(TLObject?, Int)` | update dispatch | fork `LoginHelper`/`UpdateHelper` hooks |
| `onDeepLink(LaunchActivity, Intent?)` | deeplink handling | passcode + settings deeplinks |
| `onAuthSuccess(Int)` | login flow | clear per-account passcode |
| `onMessagesControllerCreated(MessagesController)` | `MessagesController.<init>` | per-instance setup (maps provider, etc.) |
| `syncDoubleTapDelay()` | fork + `init` | propagate `DOUBLE_TAP_DELAY` into stock gesture detectors |
| `syncAnimationSpeed()` | fork + `init` | propagate `ANIMATION_SPEED` into stock animators |
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

## Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu` (`InuHideStories`).
- Subtitle/info strings: same key + `Info` suffix (`InuHideStoriesInfo`).
- Access: `LocaleController.getString(R.string.InuXxx)`.

## Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- New asset dir → add path to `scripts/config.ts` → `forkSyncFiles`.
- Icons: lucide pre-bundled; selection list in `scripts/config.ts` → `ICON_SELECTION`. Tabler pack preferred for visual consistency.

## Java ↔ Kotlin gotchas

- `.value` (Kotlin) → `.getValue()` from Java.
- Kotlin `object` → `InuXxx.INSTANCE.method()` from Java unless `@JvmStatic`.
- For hooks called from stock Java, default to `@JvmStatic fun foo(...)` on a Kotlin `object` — cleanest call site.
- Inside stgit patches, the `worktree/` prefix is omitted from paths.

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
