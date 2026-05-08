# Inugram features

> non-exhaustive list of what this fork adds, tweaks or fixes vs stock telegram android.
> keep this updated as patches are added/removed.

most things are toggleable in `Settings → Inugram`, with sensible opinionated defaults.

🐶 - Inugram-exclusive (as far as i know, as of writing)

## appearance & general

- monet (material you) theme support - *ported from [NagramX](https://github.com/risin42/NagramX)*
- 🐶 non-island ui mode for tab bars, global search and chat elements
- icon replacement (currently: solar pack, [480 Design](https://t.me/Design480)) - *ported from [NagramX](https://github.com/risin42/NagramX)*
- show seconds in timestamps
- 🐶 customizable animation speed multiplier (incl. instant)
- show peer id in profile (telegram id / bot api id)
- estimated registration date in profile - *ported & datapoints from [NagramX](https://github.com/risin42/NagramX)*
- hide own phone number from ui
- 🐶 hide fade views
- 🐶 old (pre-12.6) mention/reaction indicator
- 🐶 toggleable scrim blur
- disable number rounding
- export/import settings to/from json file
- cloud sync of settings via web app storage api
- MapLibre-based map view
- customizable map preview provider
- in-app updater - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*

## dialogs list / main page

- bottom tabs: 🐶 compact mode, hide contacts tab, hide bar entirely
- double-tap account tab to switch to next account
- long-tap "chats" tab to pick folder from menu
- folder display modes: titles / titles+icons / icons-only
- folder unread counter modes: hide / regular / exclude muted / 🐶 exclude muted non-dms
- 🐶 dialogs fab customization: main + secondary actions, hide-on-scroll, left-side
- 🐶 "create as supergroup" toggle in group creation
- 🐶 deeplink / username quick-open from global search
- mutual contact icon in contacts list
- open archive directly on pull-down (🐶 done right, without revealing the cell)

## chats

- customizable sticker size - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 remove extra bottom padding under stickers
- show all recent stickers
- minimize sticker creator button in recent stickers
- sticker time overlay modes: show / 🐶 hide time / 🐶 hide on incoming / hide completely
- 🐶 jump-to-discussion button from comments
- jump-to-beginning button in calendar popup - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- remember all clicked replies when jumping back via the down-button
- 🐶 long-press reply panel in "Replies" chat opens discussion group
- hide pinned panel
- hide channel and replies (🐶) bottom bar (mute/join/etc)
- send message to discussion group without joining
- 🐶 search: media-type filter + "show only matches"
- static pinned reactions in the reaction bar
- 🐶 reachable reactions bar (moved to the bottom of message menu)
- 🐶 reachable "seen by" (moved to bottom of message menu)
- double-tap message actions (separate for incoming/outgoing), 🐶 customizable double-tap delay
- hide keyboard on scroll
- always show go-to-bottom button (don't hide on scroll-down)
- web preview: replacements (e.g. twitter→fixupx)
- 🐶 web preview refetch from menu
- 🐶 disable web preview limit on twitter-like websites
- message details from menu (+ show json)
- per-message statistics from message menu
- 🐶 disable custom wallpaper and theme per chat
- read-only chat "admin" page for non-admins
- split media restriction toggles for stickers / gifs / games / inline
- show id in profile, show user json
- long-tap inline callback button to copy text or callback data
- "select between messages" (🐶 done right, with proper capping)
- in-place message translation, with optional web preview translation

## message input / formatting

- 🐶 customizable max input lines (and bumped default)
- 🐶 voice recorder moved into attachments drawer
- 🐶 custom formatting popup ui (better ux for span manipulation)
- 🐶 customizable text classifier (native / improved / off) - reduces false positive expansions
- "delete for both/all" default checkbox state

## photo viewer

- "hide with spoiler" toggle
- "copy photo" / "copy frame" menu actions
- show dc + platform of the photo in menu

## admin / event log

- 🐶 inline diff for message edits
- 🐶 "ban member" confirmation
- 🐶 expanded message details

## accounts

- passkey login
- qr login
- password autofill hints in login (for password managers)
- account limit raised to 8 (premium gating disabled)
- 🐶 customizable account order

## behavior

- call confirmation
- disable predictive back
- disable pull-to-next-channel
- disable swipe-to-unarchive
- disable instant camera in attachments
- disable motion photos by default
- disable notification chat bubbles
- 🐶 disable cloud drafts upload
- 🐶 disable wallpaper parallax
- 🐶 disable scroll-snap in profile
- 🐶 recyclerlistview instant-tap
- ios-style menu gesture (release-to-commit) in bottom menu tabs
- translate menu respects DNT uniformly (drops stock ru/uk forwards/public override)

## annoyances

- hide trending stickers/emoji in egs
- 🐶 hide ai features
- hide stories
- hide voice hint 
- hide paid reaction upsell 
- hide repost to story
- 🐶 hide bot commands and webview buttons
- hide intro greeting + non-clickable custom intro sticker
- 🐶 hide server-pushed suggestions
- disable phone number in chat title
- hide reactions send animation
- 🐶 simple (non-bouncy) attach panel animation
- disable notification bubbles

## 🐶 bugfixes (vs stock)

- gboard image paste no longer skips PhotoViewer
- recyclerlistview double-tap requires same view
- dialogs list pull-to-reveal-archive glitches
- shared media player visual glitches
- attach panel: better perf, safe close before fully open
- paid reaction animation respects litemode
- reaction counter shift during long-tap menu
- bubble jump when ime height changes mid send-animation
- "regular" formatting option with mixed-span selections
- photo viewer ui respects litemode blur
- lazy face detect (only on filters tab)
- lazy chromecast init in photo viewer
- missing `Emoji.replaceEmoji` calls
- background media loading cpu usage (experimental)
- animated photo spoilers respect power-saving setting
- shared media spoiler positioning
- nav stack lockup after rapid back swipes
- click-through area to the left/right of bottom bar tabs
- profile scroll jump when opening uncached user
