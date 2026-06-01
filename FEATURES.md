# Inugram features

> non-exhaustive list of what this fork adds, tweaks or fixes vs stock telegram android.
> keep this updated as patches are added/removed.

most things are toggleable in `Settings → Inugram`, with sensible opinionated defaults.

🐶 - Inugram-exclusive (as far as i know, as of writing)

## appearance & general

- navigation drawer instead of bottom tabs, like in older Telegram versions
- monet (material you) theme support - *ported from [NagramX](https://github.com/risin42/NagramX)*, plus a quick switcher in appearance settings (light/dark/amoled/auto)
- 🐶 non-island ui mode for tab bars, global search and chat elements
- icon replacement (currently: solar pack, [480 Design](https://t.me/Design480)) - *ported from [NagramX](https://github.com/risin42/NagramX)*
- notification icon: Telegram (default) or Inugram
- show seconds in timestamps
- 🐶 customizable animation speed multiplier (incl. instant)
- estimated registration date in profile - *ported & datapoints from [NagramX](https://github.com/risin42/NagramX)*
- show linked channel in discussion group profile similar to personal channel
- hide own phone number from ui
- fonts: manage a list of fonts (add/remove/reorder) — pick one as the app font (system or user-provided TTF/OTF/TTC), and use any in the media editor text tool
- 🐶 hide fade views
- 🐶 old (pre-12.6) mention/reaction indicator
- 🐶 toggleable scrim blur
- toggle to disable glass glare
- 🐶 reduce menu motion: skip context menu stagger and reaction bar slide-in/scale animations
- material 3:
  - switches
  - fabs
  - predictive back
  - lists & sections
- 🐶 toggle to replace profile photo bottom blur with a plain gradient fade
- disable number rounding
- export/import settings to/from json file
- cloud sync of settings via web app storage api
- search and deeplinks for fork settings
- MapLibre-based map view
- customizable map preview provider
- in-app updater - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 crash report sheet: catches uncaught exceptions, offers to share the log on next launch, posts a tap-to-restart notification
- keep search query after picking a result in peer selection screens

## dialogs list / main page

- bottom tabs: 🐶 compact mode, hide contacts tab, hide bar entirely
- double-tap account tab to switch to next account
- long-tap "chats" tab to pick folder from menu
- folder display modes: titles / titles+icons / icons-only
- folder unread counter modes: hide / regular / exclude muted / 🐶 exclude muted non-dms
- hide "all chats" folder tab
- 🐶 dialogs fab customization: main + secondary actions, hide-on-scroll, left-side
- 🐶 "create as supergroup" toggle in group creation
- 🐶 deeplink / username quick-open from global search
- mutual contact icon in contacts list
- open archive directly on pull-down (🐶 done right, without revealing the cell)
- interactive chat preview (long-tap avatar): tappable bubbles, no tap-to-expand

## chats

- customizable sticker size - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 remove extra bottom padding under stickers
- show all recent stickers
- minimize sticker creator button in recent stickers
- sticker time overlay modes: show / 🐶 hide time / 🐶 hide on incoming / hide completely
- 🐶 jump-to-discussion button from comments
- jump-to-beginning button in calendar popup - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- remember all clicked replies when jumping back via the down-button
- 🐶 keep bot draft messages at the bottom
- 🐶 long-press reply panel in "Replies" chat opens discussion group
- show a "Deleted message" placeholder for deleted messages
- hide pinned panel
- hide channel, group preview (🐶) and replies (🐶) bottom bar (mute/join/etc)
- send message to discussion group without joining
- 🐶 search: media-type filter + "show only matches"
- 🐶 "from user" picker in search also finds users not in chat (like tdesktop)
- static pinned reactions in the reaction bar
- 🐶 reachable reactions bar (moved to the bottom of message menu)
- 🐶 reachable "seen by" (moved to bottom of message menu)
- double-tap message actions (separate for incoming/outgoing), 🐶 customizable double-tap delay
- hide keyboard on scroll
- always show go-to-bottom button (don't hide on scroll-down)
- web preview: replacements (e.g. twitter→fixupx)
- 🐶 strip tracking params (utm_*, fbclid, si, erid, …) from links before opening and/or when pasting — *rules from AdGuard URL Tracking filter*
- 🐶 web preview refetch from menu
- 🐶 disable web preview limit on twitter-like websites
- tap a web preview photo to open it in the photo viewer
- message details from menu (+ show json)
- per-message statistics from message menu
- remove single message's file from cache from the message menu
- customizable message context menu - reorder and hide items + long-tap forward/reply items + bottom bar (*ported from [NagramX](https://github.com/risin42/NagramX)*)
- customizable chat menu + extra actions:
  - Recent actions
  - Go to beginning
  - Go to message by ID
  - Delete my messages
- 🐶 disable custom wallpaper and theme per chat
- read-only chat "admin" page for non-admins
- split media restriction toggles for stickers / gifs / games / inline
- show id in profile, show user json
- long-tap inline callback button to copy text or callback data
- "select between messages" (🐶 done right)
- 🐶 lift 100-message selection cap (forwards/saves/deletes are auto-chunked)
- 🐶 two-finger swipe over messages to select/deselect them
- more bulk actions in message selection mode (save, translate, gallery, pin/unpin)
- in-place message translation, with optional web preview translation and original-text appending
- instant view pages translator
- show original time/date in "forwarded from" header
- long-tap forward bar (above input) to cycle between regular / without sender / without caption
- long-tap a mention in a message to insert a name-mention into the input with custom text
- hide messages from blocked users: with a spoiler or completely - *partially ported from [Nekogram](https://github.com/Nekogram/Nekogram)*

## message input / formatting

- 🐶 customizable max input lines (and bumped default)
- 🐶 voice recorder moved into attachments drawer
- 🐶 custom formatting popup ui (better ux for span manipulation)
- 🐶 customizable text classifier (native / improved / off) - reduces false positive expansions
- show custom emoji *after* regular ones in `:smile` emoji suggestion popup
- "delete for both/all" default checkbox state
- hide "send as" picker (long-tap stickers button to reveal)
- round recorder:
  - zoom slider below the video feed
  - keep zoom on pinch release
  - gentler exponential zoom curve (like in normal camera apps)
  - toggle to disable dual-camera mode

## photo viewer

- "hide with spoiler" toggle
- "copy photo" / "copy frame" menu actions
- show dc + platform of the photo in menu
- seek bar for mp4 gifs

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
- per-account passcodes, hidden accounts, panic code, hidden settings deeplink - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 paranoia mode: pick chats/channels to hide everywhere; all secret chats hidden too; exit by typing a custom code in chat search
  - optional whitelist mode: hide everything *except* the picked chats (service notifications stay visible)
  - optionally hide the Inugram settings entirely when enabled
  - optionally disguise as stock Telegram when enabled
  - optionally silence all notifications while enabled
  - optionally hide all other accounts while enabled
  - optionally collapse folder tabs (show only All Chats) while enabled

## behavior

- call confirmation
- 🐶 reaction confirmation in non-joined chats
- 🐶 internal link confirmation (tg://, t.me/…)
- 🐶 disable swipe-down to minimize the in-app browser (only the minimize button collapses it)
- predictive back mode selector
- disable pull-to-next-channel
- disable swipe-to-unarchive
- disable instant camera in attachments
- disable motion photos (rendering + detection, in picker and in messages)
- disable notification chat bubbles
- 🐶 disable cloud drafts upload
- 🐶 disable wallpaper parallax
- 🐶 disable scroll-snap in profile
- 🐶 reduce profile motion (skip various enter animations, disable avatar scale-on-scroll effect)
- 🐶 prefer "Media" tab in profile over Gifts/Posts
- 🐶 recyclerlistview instant-tap
- open bottom-tab menus early by swiping up; flat highlight (not ripple) on menu hover
- faster downloads/uploads
- original video quality option in quality picker when sending videos

## annoyances

- hide trending stickers/emoji in egs
- 🐶 hide ai features
- hide stories
- hide voice hint
- hide paid reaction upsell
- hide hashtag suggestions in chat input
- hide repost to story
- 🐶 hide bot commands and webview buttons
- hide intro greeting + non-clickable custom intro sticker
- 🐶 hide server-pushed suggestions
- disable phone number in chat title
- hide call button in chat title (still in overflow menu)
- hide reactions send animation
- 🐶 simple (non-bouncy) attach panel animation
- disable notification bubbles
- disable volume keys playing visible video with sound in chat
- disable quick share (long-tap share button → send to frequent contact without confirmation)

## 🐶 bugfixes (vs stock)

- gboard image paste no longer skips PhotoViewer
- photo crop silently not applied to the sent image
- sticker creator output sent as photo when high-quality default is on
- recyclerlistview double-tap requires same view
- dialogs list pull-to-reveal-archive glitches
- shared media player visual glitches
- attach panel: better perf, safe close before fully open
- paid reaction animation respects litemode
- reaction counter shift during long-tap menu
- reactions silently disappearing right after being sent (stale server read race)
- bubble jump when ime height changes mid send-animation
- "regular" formatting option with mixed-span selections
- applying a style over a mixed-span selection smearing one span (e.g. mono) across the whole range
- photo viewer ui respects litemode blur
- lazy face detect (only on filters tab)
- lazy chromecast init in photo viewer
- stale video seekbar leaking onto photos in photo viewer
- fix photo zoom/video progress resetting on message edit
- round video recorder cancel crash when leaving chat
- missing `Emoji.replaceEmoji` calls
- background media loading cpu usage (experimental)
- animated photo spoilers respect power-saving setting
- shared media spoiler positioning
- nav stack lockup after rapid back swipes
- click-through area to the left/right of bottom bar tabs
- profile scroll jump when opening uncached user
- stale unread badges on global-search top peers
- stale unread mention pointer after reading mention on another device (mention button jumping to old message)
- photo/video gallery performance improvements
- messages consisting of only 2 or 3 emojis are huge in chat search results
- admin logs scroll jumping when loading events
- fix glitch when quickly dismissing photo editor after cropping
- persist crop when rotating photo in photo editor
- chat preview no longer marks visible reactions/poll votes as read
- fix camera2api a/v sync issue in round messages
- cross-peer reply: clear stale quote so a leftover quote-reply target doesn't override the new one at send
- phantom empty dialog rows after peeking a non-joined channel / discuss group
- expand emoji tabs when there's enough space to fit without scrolling
- "pause music on media" now lets external players auto-resume (transient focus instead of indefinite)
- reply box right padding when the sender-name line is wider than the message text line
- fix lingering webpage when quickly sending
- fix sponsored message media not respecting data saver
- fix non-joined channels history getting stuck in the past
- a bunch of stock memory/resource leaks
