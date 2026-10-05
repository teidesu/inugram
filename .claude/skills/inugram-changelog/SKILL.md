---
name: inugram-changelog
description: >
  Use when drafting release notes for the next inugram release - the
  `changelogs/<build>.md` file the release workflow posts to Telegram and GitHub.
  Trigger on "write the changelog", "draft release notes", "what changed since the
  last release", or before dispatching `release.yml`.
---

# Inugram changelog

The release workflow reads `changelogs/<build>.md`, where `<build>` is the build
number of the release being made. It puts the file inside the collapsed
"Changelog" block of the Telegram post, followed by an auto-generated
"Full changelog" link, and uses it as the GitHub release notes. Without the file,
the post only has that link.

## Find the range

```sh
git fetch --tags
base=$(git describe --tags --abbrev=0 --match 'v*')
```

Release tags are `v<APP_VERSION_NAME>-<build>`. The next build is the highest
build among `git tag -l 'v*'` plus one. Confirm with `gh variable get INU_BUILD`
(outside the sandbox) when available; trust the variable if they differ.

## Gather

- `git log --no-merges --format='%h %s%n%b' $base..HEAD`
- `git diff $base..HEAD -- FEATURES.md`: the best source of user-facing wording.
- `git diff $base..HEAD -- <path>` for commits whose subject is unclear.
- `git diff $base..HEAD -- patches/` shows stock changes; the patch name tells the area.

## Write

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
- Describe what the user sees, not the code. Start with the area (screen, setting, feature) when it helps. 
- For new/updated settings, add deep links and paths where applicable (e.g. [Messages › Pinned reactions](tg://settings/inu/pinned-reactions))
- `## Plugins` is for plugin authors: api, grants, SDK, CLI. Keep the title exactly
  `Plugins`: pluginless builds hide the section by that name.
- Skip `infra:`, CI, refactors, tests, docs, and upstream rebases unless they change behavior. 
- A rebase onto a new Telegram version is one bullet in New, with a short breakdown of the changes (one sentence at most, only keep the most important).
- Short and plain. No em-dashes, no marketing tone, no trailing periods, no mannered prose.
- Avoid empty sections, and make sure to include empty lines between sections.
- No version heading or links to the full changelog; CI adds those.

Write the file, show it to the user, and let them edit. Do not commit it.
Remind the user to verify translations before releasing.