---
name: inugram-rebase
description: >
  Use when rebasing the inugram stgit patchset onto newer upstream Telegram and
  resolving the conflicts it produces. Trigger whenever `pnpm run rebase
  latest`/`<ref>` reports merge conflicts, when worktree files show `<<<<<<<
  current` / `>>>>>>> patched` markers, or the user says "rebase the patchset",
  "fix the rebase conflict", "continue the rebase". Covers the per-conflict loop
  (identify patch → cross-reference its `patches/*.patch` → resolve → `git add
  -f` → `stg refresh` → `stg push -a`), reading `current` (new upstream) vs
  `patched` (the fork's recorded delta), the recurring conflict shapes and their
  fixes, dispatching a subagent for big multi-file refactors, and logging
  questionable resolutions to `TODO.md`. NOT for creating or editing a single
  patch (that's covered by `CLAUDE.md`), and NOT for ordinary git merge
  conflicts unrelated to the stgit stack.
---

# Inugram rebase guide

Rebasing = replaying the whole stgit stack onto a newer stock Telegram. Read
`CLAUDE.md` first — this covers only the rebase loop.

`pnpm run rebase latest` (or `pnpm run rebase <sha>`) fetches upstream and
rebases the stack, stopping at the first conflicting patch. The rebase target
sha is printed (`Rebasing to <sha> (upstream/master)`) — **note it**; you'll
`git show <sha>:<path>` to see exactly what stock changed.

## Authorization

Rebasing IS the task, so running `git add`, `stg refresh`, `stg push -a` is
expected here — this skill is the explicit exception to CLAUDE.md's "never
run stg/git". **Still never `stg export` / `pnpm run export`** (the user does
that at the very end). When the user asks for a rebase, that means end-to-end:
run `pnpm run rebase latest` (or the given ref) yourself and drive the whole
conflict loop to completion — don't wait for them to kick it off.

## The conflict loop

Bundled helpers (they resolve cwd themselves, so run from anywhere):
- `scripts/status.sh` — current patch + subject, conflicted files, marker locations.
- `scripts/continue.sh` — stages resolved files with `-f`, `stg refresh`, `stg push -a`; **refuses if markers remain** (so you never refresh a half-resolved file).

Per conflicting patch:

1. **Identify** — `scripts/status.sh` (or `stg top` + `stg show 2>/dev/null | head -6`). The subject states the fork's *intent* here — anchor your resolution to it.
2. **Read both sides** with enough surrounding context to see the enclosing structure (loop/method/braces), not just the marked lines.
3. **Cross-reference the patch file** — `cat patches/<group>/<name>.patch`. Do this for anything non-trivial. It shows the **exact** `+`/`-` lines the fork changed, which is the only reliable way to separate the real fork delta from stock context that merely surrounds it. Skipping this is how you misread a one-token change as a full rewrite.
4. **Resolve** (see shapes below).
5. **Verify + continue** — `scripts/continue.sh` (or, inline: confirm `rg -n -a '^(<<<<<<<|>>>>>>>)' <file>` is empty, then `git add -f <file> && stg refresh && stg push -a`).

Pause and **explain each conflict before applying** (user preference). One line
for mechanical ones; for anything ambiguous or architectural, lay out the
options and **ask** before deciding.

## Reading the markers — `current` vs `patched`

stgit labels the sides **`<<<<<<< current`** / **`>>>>>>> patched`**, not
`HEAD`/branch names:

- **`current`** = the tree as it is now = **new upstream + patches already
  re-pushed this rebase**. The modern/stock side.
- **`patched`** = what *this* patch recorded when authored = **old base + the
  fork's change**. Stale; its surrounding context predates upstream's recent edits.

So `patched` is almost always `(old stock) + (small fork delta)`. The job is
nearly always: **keep `current`'s stock, re-apply only the fork's delta.** Step 3
(the patch file) is what isolates that delta.

## The one question behind every conflict

**Is the fork's delta still applicable to upstream's new code?**

- Applies as-is → re-apply it onto `current` (shapes 1–2).
- The thing it targeted *moved* → re-target it (shape 3).
- The thing it targeted is *gone* → drop it, log to TODO (shape 5).
- Genuinely incompatible / can't tell → ask the user, then TODO (shape 7).

The shapes below are just the common instances of this question.

## Conflict shapes (most → least common)

1. **Additive / mechanical** — both sides independently add to the same spot
   (two OR-clauses, upstream's new term + the fork's conditional, a fork hook
   beside an upstream-added line). → **Combine both.** ~70% of conflicts.

2. **Modernization** — upstream rewrote the stock line the fork sat on:
   `LocaleController.getString(x)` → `getString(x)`; `getTypeface(x)` →
   `getTypeface(x, true)`; dropped an `SDK_INT >= 21` guard; added a param
   (`getForUserOrChat(currentAccount, user, …)`); switched a pref write to a new
   `HintsController.Hint.*` API. → **Take `current`'s line, re-add only the
   fork's delta.** Confirm via the patch file that the fork didn't touch that
   line itself — the diff context can look like a fork change when it's just
   stock that upstream later modernized.

   **Worked example** (maps patch, `LocationActivity`, hybrid map entry):
   - `current`: `…addSubItem(…, getString(R.string.Hybrid), …)`
   - `patched`: `if (MapsHelper.isHybridAvailable()) …addSubItem(…, LocaleController.getString(R.string.Hybrid), …)`

   The patch file's only `+` was the `isHybridAvailable()` guard.
   `LocaleController.getString`→`getString` is upstream modernization, not a fork
   edit. Resolution: keep `current`'s `getString` line, wrap just it in the guard.

3. **Re-target** — upstream *moved/refactored* the code the fork hooked into, so
   the fork's change has no home. Find the new location and apply the fork's
   intent there:
   - motion-photo / faster-gallery: eager XMP parse in the gallery scan loop →
     upstream made it lazy in `PhotoEntry.isLivePhoto()`. The `DISABLE_MOTION_PHOTOS`
     guard and `GalleryHelper.lookupXmp` cache moved into `isLivePhoto()`.
   - missing-replace-emoji: `replyObjectTextView`/`replyNameTextView` fields →
     became locals from `replyLayout.current()`. Re-targeted the invalidate calls.

4. **Recompute derived values** — a literal that depends on counts. chat-editor's
   `%d/N` media-toggle count: `current`=10, `patched`=12, correct=**13** (upstream
   added one row, the fork added some). **Don't pick a side for a number — count
   it** in the merged code.

5. **Follow upstream removal** — upstream deleted a stock thing the fork tweaked
   (custom-font: `COURIER_NEW_BOLD` removed; the fork's `,true` on it now moot).
   → Drop the fork's change too, then `rg` the symbol across the worktree to
   confirm no dangling reference remains.

6. **Structural / brace** — upstream changed nesting (paranoia: moved a block out
   of a `for` loop; the `patched` side was just deeper-indented because its base
   predated the move). → Take `current`'s structure, insert only the fork line.
   **Then read ~30 lines around and trace braces** to confirm balance, and check
   any variable you moved is still in scope.

7. **Architectural / questionable** — two genuinely incompatible approaches (e.g.
   eager-cached vs lazy). → **Ask the user.** If told to drop the fork side, log
   it to TODO. Often the right call is "follow upstream; the feature's intent
   survives elsewhere."

## Don't over-verify

Fork helpers in `src/kotlin` are **not touched by the rebase**. If a patch called
`SomeHelper.foo()` / `InuConfig.BAR.getValue()` before, it still resolves now —
don't read the helper to "confirm the API exists". It only burns time.

**Do** verify things caused by *your* edit: dangling refs after a removal,
variable scope when you move a line, brace balance after a structural merge,
unused-import vs still-used after re-targeting a call.

## Gotchas (each cost real time)

- **`git add` → "paths are ignored by .gitignore".** The worktree's
  `TMessagesProj/src/main/java` is gitignored though the files are tracked. Use
  **`git add -f`** (`continue.sh` already does).
- **`rg` says "binary file matches", no line numbers.** Some stock files carry a
  stray null byte (e.g. `MediaController.java`). Always pass **`rg -a`** when
  scanning for markers.
- **`stg refresh` → "resolve outstanding conflicts first".** You forgot to
  `git add -f` the resolved file.
- **cwd matters**: `stg`/`rg`-on-stock run inside `worktree/`; `patches/…` and
  `src/kotlin/…` are at repo root (helper sources are **not** under `worktree/`).
  The bundled scripts sidestep this by resolving paths themselves.
- One `stg push -a` advances through all cleanly-applying patches and stops at
  the next conflict — expect to clear several patches per push.

## Dispatch a subagent for big conflicts

When a conflict needs broad exploration — understanding an upstream refactor that
spans multiple large files, finding where moved logic now lives, or reading
chunks of a 20k–46k-line hotspot (`ChatActivity`, `PhotoViewer`,
`MediaController`) — **dispatch an `Explore` (read-only) or `general-purpose`
subagent** to investigate and report the conclusion, keeping the main context
lean instead of dumping huge files inline. Give it: the patch name + subject, the
conflicting file/lines, and the precise question ("where does motion-photo XMP
parsing happen now, and what's the new method signature?"). Resolve the conflict
yourself once it reports.

## Log questionable resolutions to TODO

Maintain a repo-root `TODO.md` while rebasing. Append an entry whenever a
resolution is anything other than obviously-correct — this is the deliverable the
user relies on after a long rebase, so don't skip it for the "interesting" ones:

- **Dropped a fork change** because upstream restructured/removed the target (note
  what was dropped + where to re-apply it on the new code).
- **Judgment call** picking upstream over fork on incompatible approaches.
- **Merged result needs eyeballing** (animation math, computed counts, layout).
- **Coverage gap**: the patch's intent now only partially applies (e.g.
  no-join-discuss — a second, upstream-added handler still has the old check).
- **Redundant with new stock**: stock now ships what the fork did; flag to trim.

Format: `## <patch-name> — <one-line what/why>`, then 2–4 terse lines of what was
done and what to re-check.

## End of rebase

`stg push -a` exits 0 with no conflict when done. Confirm:
`stg series 2>/dev/null | rg -c '^-'` (unapplied count = 0) and
`git diff --name-only --diff-filter=U` (empty). Final sweep for stray markers:
`rg -l -a '^(<<<<<<<|>>>>>>>)' TMessagesProj/src/main/java`. Then hand back — the
user runs `pnpm run export`. Don't commit or push.
