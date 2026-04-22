---
name: inugram-patches
description: >
  Use when creating, modifying, or reasoning about inugram patches — the stgit
  patchset over stock Telegram Android. Covers patch layout, hook points, helpers,
  config/UI conventions, and the (user-gated) stgit export workflow. Trigger on
  any request to add a feature/toggle, debloat stock, fix an upstream bug, or
  wire up a hook into `desu.inugram.*`.
---

# Inugram patchset guide

Inugram is a *patchset*, not a fork. `worktree/` is a stock Telegram checkout
with stgit patches applied on top. Fork code lives in `src/kotlin`/`src/res`
(synced into the worktree). Patches in `patches/` are exports, not source.

**Read `CLAUDE.md` first** — this skill complements it, not replaces.

## Golden rules (never violate)

1. **Edit `worktree/` directly.** Never hand-edit `patches/*.patch` or `series`. They regenerate from stgit.
2. **Do not run `stg` or `git` yourself** unless the user explicitly asks.
   The patch stack is live state; a wrong command corrupts it. If you need to
   know the topmost patch, run `stg top` / `stg show`, but no other stg commands are allowed. NEVER run `stg export`.
3. **Stock patches stay tiny.** Only wiring/hooks/guards. Real logic goes in `src/kotlin`. If a patch touches only `src/**`, it's in the wrong place.
4. **Default off = stock-identical.** Every behavior change gated behind an `InuConfig.*.getValue()` check. Verify every call site is gated.
5. **Check if stock already does it** before implementing a toggle. Stock may already expose it (e.g. via Lite Mode), but hidden deep in the menus. Tell the user, don't silently no-op or re-implement it.
6. **Confirm bug repro in unpatched worktree** before treating a visual/behavior issue as a patch regression. Stock has its own bugs.
7. **No renames in stock. No removing stock imports** (except `desu.inugram.*`).
8. **Prefer data-layer patches over UI-layer** — one hook in a controller beats fifteen hooks in views.

## Patch groups

`patches/<group>/<name>.patch` ↔ stgit name `<group>__<name>`.

| group | when |
| --- | --- |
| `bugfix` | fixes an upstream bug |
| `feature` | adds user-facing capability (qol, ui tweak, customization) |
| `debloat` | hides/disables stock behavior behind a toggle |
| `hooks` | thin stock hooks for fork code to attach to, no user-visible change alone |
| `misc` | build, branding, infra |

**`debloat` vs `feature`**: only *removes/toggles off* stock → `debloat`. 
Adds new capability → `feature`. `visual__`, `ui__`, etc. are **not** valid groups.

Patch subject = plain human-readable sentence (`Allow editing by double tapping a message`), not a conventional-commit prefix.

Propose a patch name for every newly made patch, but don't touch stgit yourself.

## Writing a stock patch

### Minimal wiring pattern

```java
// stock method
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
- **Never re-indent stock lines** just to wrap them — use early returns or factored-out branches.

### Exposing stock internals

- `private` field/method needed from fork? Change to `public`. That is the whole patch.
- Adding a new field/method to a stock class? Prefix `inu_` (including overloads: `inu_addTab`, `inu_needExpand`, `inu_getCurrentPhotoFile`).
  Applies to Java fields too, not just Kotlin.
- Prefer exposing over adding. Adding to a base class is especially rebase-fragile — look for an existing extension point first.

### Helper boundary

- <~5–7 lines of logic? **Inline** in the patch. A helper for two lines is noise.
- Bigger? Extract to `src/kotlin/desu/inugram/helpers/XxxHelper.kt`.
- Helper reads `InuConfig` itself — don't pass config values as parameters.
- Helper references stock constants directly (make them `public` if needed).

### Where logic must live

- Bugfix in a specific stock class? Write the fix **inline in that Java class**
  — don't detour through a Kotlin helper just to keep the patch "clean".
  `EditTextBoldCursor` bugs get fixed in `EditTextBoldCursor`.
- Feature logic (non-trivial) → Kotlin helper.
- Pure config toggle with no Java wiring? Don't write a stock patch at all.

## `InuHooks` — central hook bus

`src/kotlin/InuHooks.kt` exposes `@JvmStatic` methods so stock patches become
one-liners like `InuHooks.foo(this)`.

**Currently-exposed hooks (update this list when adding one):**

| method | called from | purpose |
| --- | --- | --- |
| `init(Context)` | `ApplicationLoader.onCreate` | bootstraps `InuConfig` + one-time syncs |
| `onResume(LaunchActivity)` | `LaunchActivity.onResume` | e.g. monet theme refresh |
| `syncChatBubbles()` | fork + `init` | mirrors `DISABLE_CHAT_BUBBLES` → `SharedConfig.chatBubbles` |
| `syncDoubleTapDelay()` | fork + `init` | propagates `DOUBLE_TAP_DELAY` into stock gesture detectors |
| `isLocalOnlyString(key)` | `LocaleController` | marks `Inu*` keys as non-server-localized |
| `addDialogsActivityOptions(...)` | `DialogsActivity` menu builder | inserts Profile/Contacts/Settings entries when bottom tabs hidden |

When adding a new hook: add `@JvmStatic fun` on `InuHooks`, reference it from
one line in the stock patch, and **update the table above**.

## `patches/hooks/` — shared extension points

Some hook patches live as standalone patches under `patches/hooks/` instead of going through `InuHooks`. 

These are **shared extension points**: a single patch exposes hooks (menu builders, callbacks, 
`public` field promotions, `inu_*` helpers on stock classes) that multiple features consume. 

They intentionally have **no user-visible effect on their own** — the features that use them ship in separate patches further down `series`, or are entirely implemented in Kotlin.

**Currently-shipping hook patches (update when adding one):**

| patch | extension point | consumed by helper |
| --- | --- | --- |
| `hooks/chat-menu-items.patch` | `ChatHelper.addMenuItems` + `ChatHelper.processMenuOption` in `ChatActivity` message menu; exposes `undoView`, `replyingMessageObject`, `createUndoView`, `processSelectedOption` | `ChatHelper` |
| `hooks/profile-menu.patch` | `ProfileHelper.addMenuItems` + `ProfileHelper.handleMenuClick` on profile action menu | `ProfileHelper` |
| `hooks/photo-viewer-menu.patch` | `PhotoViewerHelper.{addMenuItems, updateMenuItems, resetMenuItems, handleMenuClick}` + `inu_getCurrentPhotoFile` on `PhotoViewer`; exposes `containerView`, `menuItem`, `showDownloadAlert` | `PhotoViewerHelper` |
| `hooks/internal-web-app.patch` | `WebViewRequestProps.inu_internalType` + `WebAppHelper.getInternalBotName`; routes bot web-sheet UI for internal apps | `WebAppHelper` |

**When to add a `hooks/` patch vs a normal patch:**

- You're adding a new stock surface (menu, callback list, reusable field
  promotion) that **more than one future patch will wire into** → `hooks/`.
- One-off wiring for a single feature → **don't** put it in `hooks/`. Keep it
  inside the `feature/` or `debloat/` patch that needs it.
- **Rule of 3**: if 3+ existing patches touch roughly the same stock surface
  (same menu builder, same callback switch, same field promoted over and
  over), consolidate into a `hooks/` patch and have the features consume it.
  Duplicate touchpoints in stock = rebase pain multiplied.
- A `hooks/` patch must be functionally a no-op with the fork's helpers stubbed — removing all features that use it should still leave the app building.

Conventions for `hooks/` patches:
- Expose the minimum: one add/handle helper pair per menu is often enough.
- Promote `private` → `public` rather than duplicating data.
- New fields on stock classes: `inu_` prefix, even for plain Java ints.
- Entry point is always a call to `desu.inugram.helpers.XxxHelper.*`, never inline logic.

## Commonly touched stock files

Absolute paths (under `worktree/`). When working on a concern below, open
these directly instead of grepping. All paths are prefixed with
`worktree/TMessagesProj/src/main/java/`.

Line counts are approximate (drift over rebases) — use them to decide whether
to Read + offset vs `rg` first. **Files >2k lines: never Read top-to-bottom.**
`rg` for the exact symbol/method, then Read with `offset` + small `limit`.

**Top hotspots (edited by many patches):**

| file | ~lines | owns |
| --- | ---: | --- |
| `org/telegram/ui/ChatActivity.java` | 46k | chat screen  |
| `org/telegram/ui/DialogsActivity.java` | 14k | main page which is also dialogs list |
| `org/telegram/ui/Components/ChatActivityEnterView.java` | 15k | message input view - voice recorder, attach button, text input |
| `org/telegram/ui/ProfileActivity.java` | 17k | profile screen |
| `org/telegram/ui/PhotoViewer.java` | 24k | photo/video viewer - both a viewer and a preview for ChatAttachAlert |
| `org/telegram/ui/Components/ChatAttachAlert.java` | 7k | attachments panel |
| `org/telegram/ui/Components/ReactionsContainerLayout.java` | 2.6k | reactions bar in message menu |
| `org/telegram/ui/Cells/ChatMessageCell.java` | 29k | message bubble |
| `org/telegram/ui/MainTabsActivity.java` | 1k | main bottom tabs |
| `org/telegram/messenger/MessagesController.java` | 24k | messages domain state  |
| `org/telegram/messenger/MediaDataController.java` | 10k | stickers, reactions data, recent stickers, reactions list |
| `org/telegram/messenger/LocaleController.java` | 4.5k | i18n |
| `org/telegram/messenger/SharedConfig.java` | 2k | stock prefs |
| `org/telegram/messenger/LiteMode.java` | 0.4k | perf flag presets |
| `org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java` | 1.9k | inline reaction chips on messages inside the message bubble |
| `org/telegram/ui/Components/EditTextBoldCursor.java` | 1.3k | text input base, used by almost every text input in the app |
| `org/telegram/ui/Components/FilterTabsView.java` | 2k | folder tabs strip inside DialogsActivity |
| `org/telegram/ui/Components/SharedMediaLayout.java` | 13k | profile shared-media player state |
| `org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java` | 5k | attach panel photo grid |
| `org/telegram/ui/Components/glass/GlassTabView.java` | 0.6k | liquid-glass tab rendering |
| `org/telegram/ui/Cells/DialogCell.java` | 6k | single dialog row inside DialogsActivity |
| `org/telegram/ui/LaunchActivity.java` | 9k | root activity |
| `org/telegram/ui/LoginActivity.java` | 10k | login flow  |

- When adding to a hotspot (`ChatActivity`, `DialogsActivity`, `ProfileActivity`), check `patches/hooks/` first — it likely already exposes the surface you need.
- If a new file becomes a recurring target, add it to this table.

## Helpers — current inventory

`src/kotlin/helpers/` — one helper per feature area. Current helpers (grep `src/kotlin/helpers/` for the full list; these are the most commonly used ones):

`FolderHelper`, `BlurBehindHelper`, `ChatHelper`, `PhotoViewerHelper`, `ProfileHelper`, 
`InuDatabaseHelper`, `InuUtils`, `MonetHelper`, `MainTabsHelper`, `NonIslandHelper`

Before creating a new helper, check whether an existing one owns the area.
`ChatHelper` owns chat-related features; `ProfileHelper` owns profile-related features;
`NonIslandHelper` owns non-island UI gating; etc.

## `InuConfig`

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

- Always `@JvmField` so Java sees a field, not `getHIDE_STORIES()`.
- Types: `BoolItem`, `IntItem`, `FloatItem`, `StringItem`. Subclass `Item<T>`
  for anything else (including enums, see `FoldersDisplayModeItem`, `FormattingPopupConfig`).
- `BoolItem` has `.toggle()` (returns new value).
- From Java: `InuConfig.HIDE_STORIES.getValue()` — **never `.value`** (Kotlin prop, not visible as a field).
- Pref key = snake_case of the field name; default is the second arg.
- Kotlin `object` method from Java becomes `InuXxx.INSTANCE.foo()` unless `@JvmStatic`.
  For things called from stock, default to `@JvmStatic`.

## Database

Telegram has a per-account database used for internal caches and stuff like that.

- **Never** touch stock schema or `LAST_DB_VERSION` (rebase conflicts guaranteed).
- Fork versioning lives in the `inu_kv` table, managed by `InuDatabaseHelper`.
- Fork tables: `inu_*` prefix, created/migrated in `InuDatabaseHelper.migrate()`.
- To populate fork-owned fields from stock load/save paths, **hook** the stock call (like `patches/feature/folders-display-mode.patch` does) — don't edit stock SQL.

## Settings UI

- Extend `desu.inugram.ui.InuSettingsPageActivity` (wraps `UniversalFragment` with edge-to-edge, insets, and `showRestartBulletin()` helper).
- Register new pages in `InuSettingsActivity`.
- **Prefer adding to an existing page** over making a new one:
  - `InuAppearanceSettingsActivity` - general appearance settings
  - `InuChatsSettingsActivity` - chat-related appearance settings (bubbles, menus, etc.)
  - `InuDialogsSettingsActivity` - dialogs list related appearance settings (i.e. the main page, `DialogsActivity.java`)
  - `InuAnnoyancesSettingsActivity` - a specific kind of "appearance" settings that remove annoying stuff from the app (only put it there if asked by the user)
  - `InuBehaviorSettingsActivity` - general behavior settings
- **Any toggle that needs a restart → call `showRestartBulletin()` in the click handler.** Easy to forget; user will notice. But verify the restart is actually needed.
- Cells available: `SliderCell`, `ExpandableBoolGroup`, `RadioDialogBuilder`.

## Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu` (e.g. `InuHideStories`), for consistency.
- Subtitle/info strings: same key + `Info` suffix (`InuHideStoriesInfo`).
- Access from Kotlin/Java: `LocaleController.getString(R.string.InuXxx)`.

## Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- New asset dir? Add its path to `scripts/config.ts` → `forkSyncFiles` (although most of the time you shouldn't need this)
- Icons: lucide is pre-bundled; selection list in `scripts/config.ts` → `ICON_SELECTION`. 
  We use icons from the Tabler icon pack, because of it's visual consistency with Telegram.

## Common pitfalls (from prior sessions)

These are mistakes prior agents have made.

1. **Running `stg`/`git`.** You will not do this. Period. Only read-only `stg top` / `stg show`, but most of the time you don't even need to know that.
2. **Hand-editing `patches/*.patch`.** They're exports. Edit `worktree/` files directly; the user will re-export.
3. **Oversized stock patches.** "That patch is too much. make it smaller, preferably single-line." Any logic beyond a guard+helper call = move to Kotlin.
4. **Making a helper for 2–5 lines.** Inline it. Only extract when >5–7 lines or genuinely reused.
5. **Replacing stock behavior instead of running after it.** Stock code stays intact; fork logic runs before (early return) or after, gated by config.
6. **Routing a trivial set through a helper method.** If the patch just needs to assign a field based on config, assign it in-place at the stock call site, no wrapper.
7. **Modifying stock base classes.** Look for an existing extension hook first (stock often has setup hooks for themed things — e.g. liquid-glass uses one). Base-class edits rebase poorly.
8. **Writing Kotlin helpers for what must be a Java fix.** If the bug is in `EditTextCaption`, fix it **in** `EditTextCaption.java`. Don't detour.
9. **Ungated fork behavior.** Every change must be gated behind an `InuConfig` flag, so default-off = stock appearance. Verify every call site.
10. **Java using `.value`.** It's `.getValue()` from Java. Kotlin `.value` is a property; `@JvmField` only exposes the wrapper, not the inner value.
11. **Forgetting `inu_` prefix** when adding fields/methods/overloads to stock classes. Including Java fields AND temporary variables.
12. **Re-indenting stock** to wrap it in an `if`. Kills rebases. Use early returns, add after stock, or keep indentation the same

## stgit workflow — **user-initiated only**

Do not run these yourself. Documented here so you can tell the user what to run (or answer questions).

### New patch
```bash
stg new feature__my-patch -m 'Allow editing by double tapping a message'
# ...edit worktree/ files...
stg refresh
pnpm run export
```

### Modify existing patch (stable order)
```bash
stg goto feature__my-patch
# ...edit...
stg refresh
stg push -a      # return to top
pnpm run export
```

### Modify existing patch (float to top — preferred for non-trivial changes)
```bash
stg float feature__my-patch
# ...edit...
stg refresh
pnpm run export
```

After edits the user typically runs `pnpm run export` to rewrite `patches/`
and `series` from the stack. You never run it.

If the user asks "which patch am I on" — run `stg top`.

## Self-maintenance

When you add a new `InuHooks` method, new settings page, or new shared `hooks/` hook, 
update this file so future agents see it. Skill content beats tribal knowledge.
