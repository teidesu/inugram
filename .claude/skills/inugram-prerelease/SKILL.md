---
name: inugram-prerelease
description: >
  Use before an inugram release: sync translations (the per-locale
  `src/res/values-<iso>/strings_inu.xml` files against the base) and draft the
  release notes (`changelogs/<build>.md`, posted to Telegram and GitHub by the
  release workflow). Trigger on "prerelease", "prepare the release", "update/sync
  translations", "find missing strings", "translate to X", adding a new locale,
  "write the changelog", "draft release notes", "what changed since the last
  release", or before dispatching `release.yml`.
---

# Inugram prerelease

Two steps, in order: translations, then changelog. If the user asks for only one,
do only that one.

## 1. Translations

Base strings: `src/res/values/strings_inu.xml`. Each locale is a sibling
`src/res/values-<iso>/strings_inu.xml`. Every locale must be registered in
`scripts/config.ts` → `forkSyncFiles` so it lands in the worktree.

### Audit

Locales are the `src/res/values-*/` dirs that contain `strings_inu.xml` (skip
`values-v31`, `values-night-v31` and the like). For each:

```bash
pnpm tsx scripts/find-missing-translations.ts <iso>
```

`tsx` must run outside the sandbox. Output sections:

- **Missing**: keys in base, absent in locale. Add them.
- **Extra**: keys in locale, absent in base. Remove them (base key was renamed
  or dropped). If unsure whether it's a rename, grep usages in `src/` first. Do
  not invent a base entry to rescue it.
- **Stale**: base line touched after the locale line (git blame). Compare base
  and locale side-by-side; rewrite only if the translation no longer conveys the
  new wording. Ignore lines you edited in this session.

The stale check needs the locale file tracked in git HEAD; a brand-new
untracked locale crashes the script, so do parity inline for it.

### Translate

- Keep the base file's key order/grouping: put a new key next to its base
  neighbour. Edit, don't rewrite whole files.
- Preserve placeholders verbatim: `%1$s`, `%1$d`, `%s`, `\n`, `**bold**`,
  `[link](url)`, `\"`.
- Escape apostrophes as `\'` (Android XML), e.g. Turkish suffixes `Inugram\'ın`.
- Plural suffixes: `_one`, `_few`, `_many`, `_other`, `_zero`, `_two`. Russian/
  Ukrainian need `_one/_few/_many/_other` (`_many` may be dropped if identical
  to `_other`). ja/zh only need `_other`.
- Translate naturally, keep the playful tone of the base (`InuWoof`,
  `InuStickerSizeDialogMessage`).
- Keep `Telegram ID`, `Bot API`, `regex`, `MIME`, `GIF`, `QR`, `JSON`,
  `Premium` untranslated unless the locale already translates them.
- Match locale casing (Russian: sentence case; ja/zh: none).

### New locale

1. Create `src/res/values-<iso>/strings_inu.xml` with all keys (copy base,
   translate in place).
2. Register in `scripts/config.ts`:
   ```ts
   {
     source: 'src/res/values-<iso>/strings_inu.xml',
     target: 'TMessagesProj/src/main/res/values-<iso>',
   },
   ```
3. Qualifiers: `ja`, `de` (language only) or `zh-rCN`, `pt-rBR` (region, `r`
   prefix).

Re-run the script for every locale and report final counts.

## 2. Changelog

The release workflow reads `changelogs/<build>.md`, where `<build>` is the build
number of the release being made. It puts the file inside the collapsed
"Changelog" block of the Telegram post, followed by an auto-generated
"Full changelog" link, and uses it as the GitHub release notes. Without the file,
the post only has that link.

### Find the range

```sh
git fetch --tags
base=$(git describe --tags --abbrev=0 --match 'v*')
```

Release tags are `v<APP_VERSION_NAME>-<build>`. The next build is the highest
build among `git tag -l 'v*'` plus one. Confirm with `gh variable get INU_BUILD`
(outside the sandbox) when available; trust the variable if they differ.

### Gather

- `git log --no-merges --format='%h %s%n%b' $base..HEAD`
- `git diff $base..HEAD -- FEATURES.md`: the best source of user-facing wording.
- `git diff $base..HEAD -- <path>` for commits whose subject is unclear.
- `git diff $base..HEAD -- patches/` shows stock changes; the patch name tells the area.
- Previous `changelogs/*.md` for tone.

### Write

Only `##` sections with `-` bullets, in this order, omitting empty ones, with an
empty line between sections:

```md
## New
- Translator: plugins can register their own translation providers

## Improved
- ...

## Fixed
- ...

## Plugins
- `openChatHistory` api for plugin-driven chat screens
```

- One bullet per user-visible change; merge commits that touch the same feature.
  Group many small tweaks of one area into one bullet (e.g. "Classic UI: improved
  backdrop blur throughout the app").
- Describe what the user sees, not the code. Start with the area (screen,
  setting, feature) when it helps.
- For new/updated settings, add deep links and paths where applicable (e.g.
  [Messages › Pinned reactions](tg://settings/inu/pinned-reactions)).
- `## Plugins` is for plugin authors: api, grants, SDK, CLI, engine fixes. Keep
  the title exactly `Plugins`: pluginless builds hide the section by that name.
- Skip `infra:`, CI, build tooling, refactors, tests, docs, and upstream rebases
  unless they change behavior.
- A rebase onto a new Telegram version is one bullet in New, with a short
  breakdown of the changes (one sentence at most, only the most important).
- Short and plain. No em-dashes, no marketing tone, no trailing periods.
- No version heading or full changelog links; CI adds those.

Write the file, show it to the user, and let them edit. Do not commit anything.
