---
name: inugram-translations
description: >
  Use when syncing, actualizing, auditing, or adding inugram translations — the
  per-locale `src/res/values-<iso>/strings_inu.xml` files against the base
  `src/res/values/strings_inu.xml`. Trigger on requests to "update translations",
  "sync translations", "find missing strings", "translate to X", or to add a new
  locale.
---

# Inugram translations

Base strings: `src/res/values/strings_inu.xml`. Each locale is a sibling
`src/res/values-<iso>/strings_inu.xml`. Every locale must be registered in
`scripts/config.ts` → `forkSyncFiles` so it lands in the worktree.

## Auditing a locale

Always start here:

```bash
pnpm tsx scripts/find-missing-translations.ts <iso>
```

Outputs three sections:

- **Missing** — keys in base, absent in locale. Add them.
- **Extra** — keys in locale, absent in base. Remove them (stale, base key was
  renamed or dropped).
- **Stale** — base line touched after the locale line (git blame). Re-check —
  English wording may have shifted in a way that invalidates the existing
  translation. False positives happen when the base was edited cosmetically;
  use judgement.

The script requires the locale file to be tracked in git HEAD for the stale
check. Brand-new untracked locale files will make it crash before printing —
do parity check inline with the base file in that case.

## Filling in missing strings

- Keep the **same key order/grouping as the base file** where reasonable —
  reviewers diff locales against base.
- Preserve placeholders verbatim: `%1$s`, `%1$d`, `%s`, `\n`, `**bold**`,
  `[link](url)`, escaped quotes `\"`. Do not localize them.
- Preserve plural suffixes: `_one`, `_few`, `_many`, `_other`, `_zero`, `_two`.
  Russian needs `_one/_few/_many/_other` (sometimes drop `_many` if identical
  to `_other`). Languages without plurals (ja, zh) only need `_other`.
- Translate naturally — don't word-for-word. Match the playful tone of the
  base (e.g. `InuWoof`, `InuStickerSizeDialogMessage`).
- Keep technical terms (`Telegram ID`, `Bot API`, `regex`, `MIME`, `GIF`,
  `QR`, `JSON`, `Premium`) untranslated unless the locale already
  consistently translates them.
- Match casing conventions of the locale (Russian: sentence case; Chinese/
  Japanese: no capitalization concept).

## Removing extras

If the script reports extras, the base key was renamed/removed. Delete the
locale line — do not invent a base entry to "rescue" it. If unsure whether
it's a rename, grep usages in `src/` first.

## Re-checking stale

Read the current base value and the locale value side-by-side. If the
translation still conveys the new wording, leave it. Otherwise rewrite.

The stale heuristic also fires for lines you just edited in the same session
— ignore those.

## Adding a new locale

1. Create `src/res/values-<iso>/strings_inu.xml` with the full set of keys.
   Easiest: copy base, translate each value in place.
2. Register in `scripts/config.ts`:
   ```ts
   {
     source: 'src/res/values-<iso>/strings_inu.xml',
     target: 'TMessagesProj/src/main/res/values-<iso>',
   },
   ```
3. Android locale qualifiers: `ja`, `de`, `fr` (language only) or
   `zh-rCN`, `zh-rTW`, `pt-rBR` (with region — note the `r` prefix).
4. Run parity check (see above) before declaring done.

## Workflow

Default loop when asked to actualize translations:

1. List all `src/res/values-*/` dirs.
2. Run `find-missing-translations.ts <iso>` for each.
3. For each locale, apply missing + drop extras + review stale in the
   locale file directly (Edit, not Write — preserve formatting).
4. Re-run the script; report final counts.

Do not commit unless asked.
