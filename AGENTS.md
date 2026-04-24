# Inugram Agent Guide

## Rules

- Fork logic lives in `src/kotlin`, fork-owned resources in `src/res`.
- Stock patches: minimal. No renames. Prefer one-line hooks into `desu.inugram`.
- Never add fork Kotlin files to stock patches.
- Never remove stock imports (except `desu.inugram.*`).
- `patches/` and `series` are export targets, not source of truth. Do not hand-edit.
- Patch commit subject = plain human-readable explanation.
- Don't run stgit yourself unless explicitly asked.
- "topmost" = what `stg top` returns; read via `stg show`.
- If a requested feature is already in stock (even partially), notify the user first.
- Prefer data-layer patches over UI-layer patches.
- Edit the worktree directly — never touch patch files.

When you'd add fields/methods to stock classes: first try exposing `private` fields instead. Do not be afraid to do so,
this is cleaner than writing a large chunk of logic inside the patch.
If you must add them, prefix with `inu_` (including overloads, e.g. `inu_addTab`).

When stock logic needs mode-dependent behavior (e.g. width calc), prefer making an if/else wrapper with no indentation for easier rebasing.

## Patch naming

Format: `group__name` → `patches/<group>/<name>.patch`.

Allowed groups (anything else is wrong):

| group | when |
| --- | --- |
| `bugfix` | fixing a bug in stock |
| `feature` | adding a contained feature, qol, ui tweak, customization |
| `debloat` | hiding/disabling a stock feature behind a toggle ("un-feature") |
| `hooks` | thin hooks into stock so fork code in `src/kotlin` has something to attach to |
| `misc` | build, branding, infra — everything else |

Rule of thumb: if the patch only *removes* or *toggles off* stock behavior, it's `debloat`. If it adds new user-facing capability, it's `feature`. If it exists purely so another patch can wire into stock, it's `hooks`.

Example:

```bash
stg new feature__double-tap-to-edit -m 'Allow editing by double tapping a message'
```

## Patch scope

- Patches only add hook calls, wiring, and tiny stock changes.
- Feature logic goes in `src/kotlin` and `src/res`.
- A patch touching only `src/**` is wrong — move it out.

## Database

- Never touch stock schema or `LAST_DB_VERSION` (rebase conflicts).
- Fork versioning goes in the `inu_kv` table via `InuDatabaseHelper`.
- Fork tables get `inu_` prefix (e.g. `inu_folder_meta`), managed in `InuDatabaseHelper.migrate()`.
- Hook stock load/save to populate fork fields; don't edit stock SQL.

## Helpers

- One helper per feature area (e.g. `FolderHelper` owns icons + DB + layout + drawing).
- Helpers called from stock should be self-contained — derive defaults from stock constants, don't accept them as params.
- Under ~5–7 lines of change, inline it in the patch instead of adding a helper.
- For bigger changes, prefer running logic *after* stock code rather than rewriting it (easier rebase).
- Reference stock constants directly from the helper (make them `public` if needed).

## Project landmarks

### Entry points

- `desu.inugram.InuHooks` — central bus for hooks into stock lifecycle (`init`, `onResume`, etc.). Prefer adding a `@JvmStatic` method here for a one-line stock patch call site, instead of exposing each helper to Java.
- `desu.inugram.InuConfig` — all fork prefs. SharedPreferences name: `inugram`. Loaded once from `InuHooks.init`.
- Inside stgit patches, `worktree/` prefix is omitted from paths, something to keep in mind when editing/referencing existing patches.
- Never touch `TLRPC.java` in your patches - that file is auto-generated and rebasing changes to it will be pure hell.

### `InuConfig` pattern

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

- Always `@JvmField` (so Java sees it as a field, not `getHIDE_STORIES()`).
- Types: `BoolItem`, `IntItem`, `FloatItem`, `StringItem`. Extend `Item<T>` for anything else.
- `BoolItem` has `.toggle()`.
- Java access: `desu.inugram.InuConfig.HIDE_STORIES.getValue()` (not `.value`).
- Pref key = snake_case of the field name. Default is the second arg.

### Settings UI

- Extend `desu.inugram.ui.InuSettingsPageActivity` (wraps `UniversalFragment` with edge-to-edge + insets + `showRestartBulletin()` helper). Register the page in `InuSettingsActivity`.
- Existing category pages: `InuAppearance`, `InuBehavior`, `InuChats`, `InuDialogs`, `InuAnnoyances` settings activities. Prefer adding items to an existing page over making a new one.
- Call `showRestartBulletin()` after toggling anything that needs a restart.
- Custom cells: `SliderCell`, `ExpandableBoolGroup`, `RadioDialogBuilder`, `StickerSizePreviewMessagesCell`.

### Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu`. Subtitle/info strings use `Info` suffix (e.g. `InuHideStories` + `InuHideStoriesInfo`).
- Access: `LocaleController.getString(R.string.InuXxx)`.

### Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- `scripts/config.ts` → `forkSyncFiles` controls where fork files land in the worktree. Add new paths here when introducing a new asset dir.
- Icons: lucide pre-bundled; selection list in `scripts/config.ts` → `ICON_SELECTION`.

### Java ↔ Kotlin gotchas

- `.value` (Kotlin) → `.getValue()` from Java.
- Kotlin `object` → `InuXxx.INSTANCE.method()` from Java unless the method is `@JvmStatic`.
- For hooks called from stock Java, default to `@JvmStatic fun foo(...)` on a Kotlin `object` — cleanest call site.

