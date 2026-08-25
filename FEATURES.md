# Inugram features

> non-exhaustive list of what this fork adds, tweaks or fixes vs stock telegram android.
> keep this updated as patches are added/removed.

most things are toggleable in `Settings → Inugram`, with sensible opinionated defaults.

🐶 - Inugram-exclusive (as far as i know, as of writing)

## appearance & general

- navigation drawer instead of bottom tabs, like in older Telegram versions
  - 🐶 an option to (ab)use predictive back to open the drawer
- monet (material you) theme support - *based on [NagramX](https://github.com/risin42/NagramX)*, 🐶 improved. plus a quick switcher in appearance settings (light/dark/amoled/auto)
- 🐶 classic ui mode for folders bar, shared media tabs, global search and chat elements (reverts the >12.6 "liquid glass" look)
- icon replacement (solar pack by [480 Design](https://t.me/Design480) - *ported from [NagramX](https://github.com/risin42/NagramX)*; vkui pack by [VK](https://github.com/VKCOM/icons) - *ported from [Catogram](https://github.com/Catogram/Catogram)*)
- notification icon: Telegram (default) or Inugram
- show seconds in timestamps
- override Telegram's detected device performance class
- 🐶 customizable animation speed multiplier (incl. instant)
- estimated registration date in profile - *ported & datapoints from [NagramX](https://github.com/risin42/NagramX)*
- join/creation date in group & channel profiles
- show linked channel in discussion group profile similar to personal channel
- hide own phone number from ui
- fonts: manage a list of fonts used in the media editor text tool; device system fonts can be toggled on/off. install fonts from .ttf/.otf/.ttc files right from the chats.
- extra meme-style outlined text style in the media editor text tool
- 🐶 app-font *stack*: pick a user-provided TTF/OTF/TTC family as the whole-app font, optionally with fallbacks (e.g. for other scripts)
- 🐶 separate monospace font: pick any roster font for code blocks (inline + pre), previewed live alongside the stack
- 🐶 hide fade views
- 🐶 old (pre-12.6) mention/reaction indicator
- 🐶 toggleable scrim blur
- toggle to disable glass glare
- 🐶 reduce menu motion: skip context menu stagger and reaction bar slide-in/scale animations
- material 3:
  - switches
  - fabs
  - predictive back
  - navigation animation
  - lists & sections
  - avatars (tonal on-container initials instead of white)
  - profile action buttons
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
- custom title text: Inugram / @username / first name / "Chats"
- 🐶 dialogs fab customization: main + secondary actions, hide-on-scroll, left-side
- 🐶 "create as supergroup" toggle in group creation
- 🐶 deeplink / username quick-open from global search
- mutual contact icon in contacts list
- customizable dialogs list pull-down action: reveal archive (stock), open archive directly (🐶 done right, without revealing the cell), open saved messages, open search, or disabled entirely. when the pull-down no longer leads to the archive, the archive row is hidden from the list and an "Archived Chats" entry appears in the drawer/overflow menu instead
- interactive chat preview (long-tap avatar): tappable bubbles, no tap-to-expand
- 🐶 community display modes: regular / open on avatar long-tap / invisible
- "select all" in the chat selection three-dot menu (selects all loaded chats in the current folder tab)

## chats

- customizable sticker size - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 remove extra bottom padding under stickers
- 🐶 full-quality sticker previews in sticker sheets & emoji panel (instead of blurry 90px thumbs; only on unmetered network or when already downloaded)
- show all recent stickers
- minimize sticker creator button in recent stickers
- sticker time overlay modes: show / 🐶 hide time / 🐶 hide on incoming / hide completely
- "Refresh" in the sticker/emoji pack menu
- compact edited indicator: pencil icon instead of the "edited" label
- toggleable message bubble tails
- 🐶 jump-to-discussion button from comments
- jump-to-beginning button in calendar popup - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- remember all clicked replies when jumping back via the down-button
- 🐶 keep bot draft messages at the bottom
- 🐶 long-press reply panel in "Replies" chat opens discussion group
- show a "Deleted message" placeholder for deleted messages
- hide pinned panel
- hide channel, group preview (🐶) and replies (🐶) bottom bar (mute/join/etc)
- send message to discussion group without joining
- 🐶 member count on the join request sheet (instead of "private group/channel"; like tdesktop)
- 🐶 search: media-type filter + "show only matches"
- 🐶 "from user" picker in search also finds users not in chat (like tdesktop)
- static pinned reactions in the reaction bar
- 🐶 reachable reactions bar (moved to the bottom of message menu)
- 🐶 reachable "seen by" (moved to bottom of message menu)
- double-tap message actions (separate for incoming/outgoing/editable channel posts), 🐶 customizable double-tap delay
- hide keyboard on scroll
- always show go-to-bottom button (don't hide on scroll-down)
- web preview: replacements (e.g. twitter→fixupx)
- 🐶 strip tracking params (utm_*, fbclid, si, erid, …) from links before opening and/or when pasting — *rules from AdGuard URL Tracking filter*
- 🐶 web preview refetch from menu
- 🐶 disable web preview limit on twitter-like websites
- 🐶 spoiler web previews: when the preview-generating link is under a spoiler, cover the whole preview card too
- tap a web preview photo to open it in the photo viewer
- message details from menu (+ show json)
- per-message statistics from message menu
- remove single message's file from cache from the message menu
- "Repeat" in message menu - re-send the same message to the same chat
- "Save to Downloads" for stickers
- customizable message context menu - reorder and hide items + long-tap forward/reply items + quick actions row (*ported from [NagramX](https://github.com/risin42/NagramX)*)
- customizable chat menu + extra actions:
  - Recent actions
  - Go to beginning
  - Go to message by ID
  - Delete my messages
  - Statistics / Administrators / Permissions / Invite links (admin shortcuts)
- 🐶 disable custom wallpaper and theme per chat
- per-forum client-side topics layout override (tabs/list) from the profile menu
- read-only chat "admin" page for non-admins
- split media restriction toggles for stickers / gifs / games / inline
- show id in profile, show user json
- long-tap the name in profile to copy it
- 🐶 drag the pinned-music sheet by its header to scroll/expand the playlist
- long-tap inline callback button to copy text or callback data
- "select between messages" (🐶 done right)
- 🐶 lift 100-message selection cap (forwards/saves/deletes are auto-chunked)
- 🐶 two-finger swipe over messages to select/deselect them
- more bulk actions in message selection mode (save, translate, gallery, pin/unpin, no-quote forward)
- in-place message translation, with optional web preview translation, original-text appending and on-device source-language auto-detection (hides Translate when already in your language)
- instant view pages translator
- show original time/date in the forwarded header, with regular, icon-only label, and compact one-line modes
- show forward count next to the view count on channel posts
- long-tap forward bar (above input) to cycle between regular / without sender / without caption
- long-tap a mention in a message to insert a name-mention into the input with custom text
- 🐶 restrict/ban menu items the avatar long-tap menu
- hide messages from blocked users: with a spoiler or completely - *partially ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
  - also hide messages from a custom list of users/channels without blocking them (via settings picker or profile menu)

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

- ultra HDR photos with HDR-capable displays - *based on [NagramX](https://github.com/risin42/NagramX)*
- "hide with spoiler" toggle
- "copy photo" / "copy frame" menu actions
- show dc + platform of the photo in menu
- seek bar for mp4 gifs
- always use the modern speed-control rewind on long-press
- mark public (fallback) / personal profile photos next to the date

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
  - optional launcher long-press shortcut to enter it quickly (hidden while active)
- biometric confirmation before deleting/clearing a chat or logging out - *inspired by [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)*

## behavior

- call confirmation
- HD call audio on Bluetooth
- 🐶 reaction confirmation in non-joined chats
- 🐶 internal link confirmation (tg://, t.me/…)
- support `tg://user?id=…` links (opens the profile; user must be known locally)
- 🐶 disable swipe-down to minimize the in-app browser (only the minimize button collapses it)
- predictive back mode selector
- disable pull-to-next-channel
- disable swipe-to-unarchive
- 🐶 disable swipe-to-hide the General topic in the forum topics list
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
- auto-disable the configured proxy while a VPN is active
- send MP4 files attached through Files as playable videos without conversion
- original video quality option in quality picker when sending videos
- remember last used settings in polls + reasonable defaults

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
- disable auto-play when opening the pinned music player on a profile

## 🐶 bugfixes (vs stock)

- "Save to Downloads" copies uncached documents after downloading instead of requiring a second attempt
- cancelling a video download kept restarting it after streaming the video in PhotoViewer (the player's loader thread swallowed its shutdown interrupt, survived the viewer close, and re-requested the file on every cancel; also a file-reference refresh landing mid-cancel resurrected the operation into an uncancellable zombie)
- "Save to Downloads" preserves the original filename on Android 10+
- gboard image paste no longer skips PhotoViewer
- reordering an attach-panel album preserves per-photo captions and no longer duplicates its album caption
- photo crop silently not applied to the sent image
- high-quality photo cropping in PhotoViewer (crop *before* downscaling)
- sticker creator output sent as photo when high-quality default is on
- non-square webm stickers rendered off-center in the emoji panel (precached frames were blitted top-left and scaled by width only)
- webm sticker permanently stuck blank until the app is restarted (an image load cancelled mid-flight published a decoder-less drawable into the animation cache, whose stream stayed cancelled and which never retried decoding)
- webm stickers played too fast (frame pacing was driven by the container's declared fps instead of frame timestamps, so variable-rate or mis-probed webm ran at up to 2x)
- recyclerlistview double-tap requires same view
- list ripple left behind when the pressed row moves because another row changed height (selector was only re-synced on scroll)
- link ripples in album captions work across the entire group
- dead zones in list rows where a hidden clickable child kept stale bounds from a previous binding (e.g. top-right corner of a member row in profile after a tagged member was recycled)
- chat list crash while flinging when RecyclerView exposes a stale child without a ViewHolder
- dialogs list pull-to-reveal-archive glitches
- inline code in dialog previews no longer inherits chat-bubble colors
- big emoji jumping around in emoji-only messages when a visible reply preview shared its spans (stock bug: per-span draw-position cache fought over by both layouts), plus oversized animated emoji in reply previews (stock bug: `cloneSpan` overwrote the resized value with the old size)
- pinned dialog reorder scrolling/glitching mid-drag in the archive (stock bug: async list diffing dispatched the move after the drag swap)
- forwards from users with hidden forward privacy: the optimistic message shows the anonymized name right away (when their profile is cached), and the server-confirmed hidden header is applied in place instead of showing the linked author until chat reopen
- shared media player visual glitches
- profile pinned-music sheet bugfixes
- shared media pager: fling mid-animation to chain tabs or reverse (was ignored until settled); at the edge tab the fling falls through to swipe-to-close
- attach panel: better perf, safe close before fully open
- paid reaction animation respects litemode
- reaction counter shift during long-tap menu
- reactions silently disappearing right after being sent (stale server read race)
- sticky date pill jump and color shift when replacing an inline date separator
- bubble jump when ime height changes mid send-animation
- out-of-bubble panels (reply/forward/name) on custom wallpapers jumping tint when the keyboard opens (wallpaper-sampling offset flipped by the action bar height via a stale keyboard-layout conditional)
- markdown `__`/`**`/`~~`/`||` no longer parsed inside auto-detected links on send
- "regular" formatting option with mixed-span selections
- applying a style over a mixed-span selection smearing one span (e.g. mono) across the whole range
- photo viewer ui respects litemode blur
- search-as-list box respects litemode blur
- lazy face detect (only on filters tab)
- lazy chromecast init in photo viewer
- stale video seekbar leaking onto photos in photo viewer
- fix photo zoom/video progress resetting on message edit
- photo viewer no longer dismissing the keyboard / jumping at end of close animation (12.8 regression)
- fix edge-to-edge for instant view
- missing action bar title/date and open/close animation when viewing a photo of a user who hid theirs from you (stock bug: profile photo locations carried no dc id)
- text spoilers jittering/blinking while scrolling on high-refresh displays (12.8 regression)
- revealed spoilers in album captions re-hiding themselves after a scroll (reveal flag was set on the drawing cell's message instead of the group's primary one)
- black screen after rotating the screen with a chat preview open
- round video recorder cancel crash when leaving chat
- missing `Emoji.replaceEmoji` calls
- background media loading cpu usage (experimental)
- animated photo spoilers respect power-saving setting
- shared media spoiler positioning
- nav stack lockup after rapid back swipes
- click-through area to the left/right of bottom bar tabs
- profile scroll jump when opening uncached user
- duplicate edit-info and profile-photo actions in the standalone self-profile overflow menu
- stale unread badges on global-search top peers
- stale unread mention pointer after reading mention on another device (mention button jumping to old message)
- folder pins silently missing when the pinned dialog isn't in the local dialogs cache (now fetched from server)
- photo/video gallery performance improvements
- edits (incl. crop) silently lost when the gallery refreshed under an open photo editor (fresh screenshots); also fixes fresh screenshots sometimes not appearing in the attach sheet or making it flash
- messages consisting of only 2 or 3 emojis are huge in chat search results
- admin logs scroll jumping when loading events
- fix glitch when quickly dismissing photo editor after cropping
- persist crop when rotating photo in photo editor
- chat preview no longer marks visible reactions/poll votes as read
- dialogs list briefly flashing over the lockscreen after ending/declining a call
- fix camera2api a/v sync issue in round messages
- forward bar showing stale message count/senders after deselecting messages in the forward options sheet
- cross-peer reply: clear stale quote so a leftover quote-reply target doesn't override the new one at send
- phantom empty dialog rows after peeking a non-joined channel / discuss group
- expand emoji tabs when there's enough space to fit without scrolling
- "pause music on media" now lets external players auto-resume (transient focus instead of indefinite)
- "pause music on media" now also applies to videos in the photo viewer, with transient focus so external players auto-resume (stock never requests audio focus for them)
- reply box right padding when the sender-name line is wider than the message text line
- fix lingering webpage when quickly sending
- fix sponsored message media not respecting data saver
- fix non-joined channels history getting stuck in the past
- jumping to an uncached message no longer flashes and returns to the chat bottom
- expandable switch cell (e.g. admin rights groups) counter badge overlapping the switch on long titles
- fix stuck red snapshot box when the frame capture fails/times out on enter in pip
- heads-up notifications show only the new message instead of the whole group, on the correct channel (Private Chats/Groups/Channels) instead of "Internal notifications" (visible since android 16 forced grouping)
- fix npe checking admin/owner when channel admins not yet loaded
- comments/topic thread restored as plain group chat after activity recreation
- phantom message selection after back-gesture swipe over a reply header (leaked long-press timer)
- fix deeplinking to a non-primary album member sometimes mis-anchoring
- a bunch of stock memory/resource leaks
- correctly handle "Open in..." in ChatAttachAlert: open editor before sending + support multi-attach
- avatar of a bubble right below a topic separator (forum "All" tab) only clickable in its lower part
- opt out of android media resumption, so a phantom telegram player chip no longer reappears in quick settings after a reboot or once the app process is gone
- fix profile crash when a contact's note is removed server-side while the open animation is running (note row built from stale user info, bound against fresh)
- notifications for chats read on another device no longer linger forever when the app process was killed in between (stock only tracked posted notifications in memory)
- more stale notification fixes: reaction, "scheduled message sent", story and forum-topic notifications now clear when read/seen on another device while the app is connected (stock only cleared these via FCM pushes, which aren't delivered to online sessions, or on opening the chat locally); notification refresh is no longer skipped when unread counts happen to stay equal (forums, communities, muted chats)
- unchanged notifications are no longer re-posted on every update (stock re-notifies every chat each time anything changes, making notification bridges like Mi Fitness re-forward the whole stack to the wearable)
- crash long-pressing a sticker set while off-screen rows are cached (reorder update bound null item on cached/hidden holders)
- crash cutting out a sticker after the photo editor recycled the source image mid-segmentation
- storage usage cached-media list intermittently refusing to scroll
- crash after transferring channel ownership (admin sort comparator overflowed on 64-bit peer ids)
- chat drifting a few px off the input when hiding the keyboard while a message animation runs (aosp recyclerview bug: end-anchored padding delta applied twice across pre+post layout)
- crash expanding/loading more votes in poll results (sections adapter diffed against an empty hash list on the first update, re-inserting every already-laid-out item)
- crash tapping the story privacy badge on a story from a user with no first name (e.g. deleted account)
- stop spamming doomed admin-list requests (`COMMUNITY_FILTER_INVALID`) on every open of a community you're not an admin of
- crash in the forward picker inside a community when the community's info updates (stock updates an action bar avatar view that picker mode never creates)
- fix bottom progress bar on video bubbles now following inline playback (autoplay & play-with-sound)
