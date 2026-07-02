---
name: explore-hprof-leaks
description: Analyze Java/Android heap dumps (.hprof, converted Eclipse MAT .ec.hprof) to find likely memory leak roots. Use when asked to inspect hprof/heapdump/memory dump/OOM artifacts, interpret Eclipse MAT leak suspects/top consumers/histograms, trace retained objects to GC roots, compare heap evidence with source lifecycle cleanup, or propose memory leak fixes without blindly patching downstream retained objects.
---

# Explore HPROF Leaks

Target: $ARGUMENTS

## Workflow

1. Locate inputs: target `.hprof`, converted `.ec.hprof`, existing `*_Leak_Suspects.zip`, `*_Top_Consumers.zip`, MAT install path, repro notes, build/version. The file may be provided in Input above.
2. Prefer an already converted MAT-compatible dump (`*.ec.hprof`). Android hprofs are not MAT-compatible as-is.
3. If only Android hprof exists, convert with `~/Library/Android/sdk/platform-tools/hprof-conv my-oom.hprof my-oom.ec.hprof`. Convention: append `.ec.hprof` for Eclipse-compatible files. If input is already `.ec.hprof`, skip conversion.
4. Run `scripts/extract_mat_reports.py <dump>` first. It reuses existing MAT zip reports, or can run MAT if available. This gives the class histogram + top consumers, which is the fastest first look.
5. Read stripped report text: system overview, top consumers, class histogram, thread overview, leak suspects.
6. If MAT says "No leak suspect", keep going. Abnormal instance counts and retained cohorts often matter more than MAT's suspect verdict.
7. Pick suspicious classes by lifecycle expectations, not size alone: activities/fragments/views/adapters should not accumulate; caches/controllers may legitimately retain data.
8. For each suspect cohort, trace paths to GC roots. Use `scripts/heapprobe/` (see `references/heapprobe.md`) for programmatic queries — strong-path enumeration, first-strong-holder tally, observer-list membership. Exclude weak/soft/phantom refs when confirming retention. Record the first strong owner that should have been cleared.
9. Map anonymous classes to source. Search exact generated class markers (`ChatActivity$18`, `this$0`, listener fields), then inspect creation and destroy/unregister paths.
10. Compare add/remove symmetry: observers, listeners, callbacks, delegates, runnables, animators, adapters, static maps, ThreadLocals, native-backed drawables/bitmaps.
11. Name a root cause only after source lifecycle explains the heap path. If not proven, say what dump/test would disambiguate.

## MAT Usage

Use existing reports before reparsing a large dump:

```bash
python3 /path/to/explore-hprof-leaks/scripts/extract_mat_reports.py private/my-oom.ec.hprof
```

If reports are absent and MAT is installed:

```bash
python3 /path/to/explore-hprof-leaks/scripts/extract_mat_reports.py private/my-oom.ec.hprof --mat /Applications/MemoryAnalyzer.app --xmx 8g
```

MAT macOS notes:
- Android hprofs must be converted before MAT: `~/Library/Android/sdk/platform-tools/hprof-conv my-oom.hprof my-oom.ec.hprof`.
- `.ec.hprof` means Eclipse-compatible; do not reconvert it.
- Headless parser is usually `/Applications/MemoryAnalyzer.app/Contents/Eclipse/ParseHeapDump.sh`.
- The app's default `MemoryAnalyzer.ini` may have `-Xmx1024m`; this is too small for ~700 MB dumps.
- Prefer passing larger heap via the script over editing global app files.
- Reports are usually named `<dump-base>_Leak_Suspects.zip` and `<dump-base>_Top_Consumers.zip`.
- Always run it outside the sandbox, Java is not available inside the sandbox.

## Operational gotchas (these cost real time — read before parsing)

- **"It's stuck" = a stray MAT process holding the config lock.** A leftover
  `MemoryAnalyzer` from a prior run (a probe that didn't exit, a killed parse) keeps the
  equinox `-clean` configuration lock. The next invocation then *silently blocks* — near-zero
  CPU, ~40 MB RSS, no progress, no error — instead of failing. If a parse isn't churning
  (a real parse uses GBs of RSS and steady CPU), check `ps aux | rg MemoryAnalyzer` and
  `kill -9` the strays before retrying. `build_and_run.sh` does this automatically.
- **Truncated dump = unrecoverable, don't retry.** If `hprof-conv` prints
  `ERROR: read N of M bytes`, the source hprof was captured incompletely (the dump was
  interrupted mid-write). MAT's `Pass2Parser` will throw on the incomplete dump segment.
  There's nothing to salvage — ask for a fresh full capture and move on.
- **The HTML "Leak Suspects / Details" renderer can crash** (AIOOBE in
  `MultiplePath2GCRootsQuery` on a corrupt `String`). When a report zip won't generate or the
  Details page is empty, don't fight it — go to `scripts/heapprobe/` for the path/holder
  queries directly.

## Deep queries: HeapProbe

Once the reports point at a suspect cohort, use `scripts/heapprobe/build_and_run.sh` to prove
the retention. It's a small MAT plugin exposing `counts` / `histogram` / `inbound` / `paths`
/ `tally` / `observers`. `tally` (first-strong-holder distribution) and `paths` (weak-excluded
strong paths) are the ones that most often crack a case. Full docs + when to reach for each:
`references/heapprobe.md`.

**Feel free to expand `HeapProbeApp.java`** for whatever the current dump needs — a bespoke
reconcile pass, a set intersection between two cohorts, a field-value dump. The build script
recompiles on any source change. Don't feel obligated to use only the commands that ship;
a few new lines on top of the existing helpers usually answers the exact question faster than
contorting one to fit.

## Multiply-held cohorts (the "no single fix frees it" case)

When a leaked class appears in Top Consumers as a **top-level dominator** (its immediate
dominator is the root set / `<ROOT>`), it's reachable through *several independent strong
paths* — cutting one reference frees nothing. Don't stop at MAT's one shortest path.

- Enumerate the whole cohort's roots with `heapprobe tally <class> <candidate,holders>`. The
  bucket counts show how many instances each distinct leak accounts for, so you fix in impact
  order. Real example: 90 `ChatActivity` split 57 `ChatBackgroundDrawable` / 9 `LaunchActivity`
  / 9 `ReactionsEffectOverlay` / … — **three separate leaks**, each its own fix.
- The named suspect's *own* retained size can be tiny while the mass hangs off a multiply-held
  child. One `PhotoViewer` retained 186 KB but transitively pinned 118 MB of GL paint buffers
  through a `LPhotoPaintView` that was itself a top-level dominator. Trace to the accumulation
  point and enumerate *its* external pins, not the suspect's.
- A single dangling observer/animator/watcher deep in the tree can be the sole external root
  of an entire orphaned Activity/PhotoViewer subtree. `heapprobe observers <adapterOrView>`
  counts how many instances of a class are still in `NotificationCenter` lists (and on which
  event ids) — a one-shot confirmation of an add/remove asymmetry.

## Leak Analysis Rules

- Top dominator is not necessarily the leak root. It can be a downstream object retained by the real root.
- A "biggest object" can be valid cache state. A small observer/listener can retain a huge UI graph.
- Thread overview with tiny retained locals means it is probably not a thread/local-stack leak.
- Native-backed explosions (`Paint`, `TextPaint`, `DirectByteBuffer`, `sun.misc.Cleaner`) often follow leaked views; do not fix them first unless they are the root.
- `byte[]`, `Object[]`, `ArrayList`, `HashMap` are containers. Find the owning domain object.
- A `WeakHashMap` does not protect you if the stored *value* (or anything it reaches) strongly references the *key* — the entry pins its own key and never evicts. Same trap with `WeakReference` referents held alive by a sibling strong ref. When a leak path runs through a `WeakHashMap$Entry.value`, check what the value captures.
- Use counts: one `Activity`/fragment instance may be fine; dozens/hundreds after navigation is a leak signal.
- Validate against source teardown. Missing `removeObserver`, `removeListener`, `onDestroy`, `detach`, `cancelRunOnUIThread`, `removeDelegate`, or adapter cleanup is the usual fix shape.
- Do not propose clearing useful caches unless the GC-root path proves the cache is wrong for lifecycle.
- **Confirm a suspect path is strong before naming it.** MAT's HTML leak-suspects report does
  not exclude weak/soft refs, so it can draw a path through a `WeakReference` field (e.g.
  `ObjectAnimator.mTarget` on some SDKs) that retains nothing. Re-run with weak/soft/phantom
  excluded (`heapprobe paths`) — a path that survives exclusion is a real retention.

## Anonymous Class Mapping

When MAT reports `OuterClass$18`, do this:

- In MAT object fields, inspect synthetic captures first: `this$0` is the outer instance; `val$foo` fields are captured locals. These often reveal the leaked owner without bytecode.
- If `.class` files are available, run `javap -classpath <classes-root> -v -p 'org.telegram.ui.ChatActivity$18'`. Read `EnclosingMethod`, `InnerClasses`, field list, and constructor bytecode. Synthetic fields show captured refs; constructor `putfield` order shows which parameters feed them.
- If only APK/DEX is available, use `jadx -d /tmp/jadx <apk>` or `baksmali d <classes.dex> -o /tmp/smali`, then search `ChatActivity$18`. In smali, check `.field final synthetic this$0` / `val$...` and `.annotation system Ldalvik/annotation/EnclosingMethod;`.
- If no bytecode is available, search source around likely methods for anonymous allocations: `rg -n 'new .+\\(.*\\) \\{' worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`. Treat ordinal counting as a fallback only; compiler output can shift.
- After locating the block, inspect what registers it (`addObserver`, `setDelegate`, `addListener`, `postDelayed`, static collection) and where the matching unregister/cancel should happen.

## Inugram/Telegram Subtleties

Prior heapdump lessons to preserve:

- MAT may report no suspects while counts show the leak. One dump had `139 x ChatActivity`, `17 x ChatAttachAlertPhotoLayout`, `135k x MediaController$PhotoEntry`, `170k x android.graphics.Paint`, `537k x sun.misc.Cleaner`.
- Many leaked `ChatAttachAlertPhotoLayout` objects were downstream of leaked `ChatActivity`; patching the photo layout would have been speculative before cutting the real roots.
- Telegram `NotificationCenter` observers are a common root. Check constructor/add sites against `onDestroy`/detach removal.
- `resourcesProvider`/`ThemeDelegate` captures can pin a full `ChatActivity`. Adapters and views created inside chat/attach/photo viewer paths are high risk if they register observers.
- `ChatAvatarContainer` leak class: observer removal belonged in `onDestroy`/detach, plus clear parent refs/detach drawables.
- `ChatAttachAlert` leak class: top-level `onDestroy()` destroyed layouts/comment fields/its own observers, but missed `mentionContainer.getAdapter().onDestroy()`. The `MentionsAdapter` stayed in `NotificationCenter`, retained the chat `resourcesProvider`, then the whole chat and attach gallery.
- `EditTextEmoji` leak class: global `emojiLoaded` observer registered in constructor but some owners never called `onDestroy()`. Attach/detach lifecycle was safer.
- `LaunchActivity` leak class: static delegate/WeakHashMap patterns can leak if value strongly references the weak key; remove delegate in `onDestroy`. Also `checkCurrentAccount()` adds a `chatSwitchedForum` observer that `onFinish()` forgot to remove — leaked the whole chat stack on recreation.
- Anonymous generated classes like `ChatActivity$18` can be a listener/delegate, not the root itself. Locate the source block and inspect its captured fields.
- `MentionsAdapter` leak class (recurs): the adapter registers 4 `NotificationCenter` observers in its constructor and only removes them in `onDestroy()`, which the *owner* must call. `MentionsContainerView.onDetachedFromWindow()` only clears its own `emojiLoaded` observer, not the adapter's — so any owner that forgets `mentionContainer.getAdapter().onDestroy()` leaks. `ChatActivity`/`ChatAttachAlert` call it; the PhotoViewer caption editor (`captionEdit`/`topCaptionEdit`) did not, so `destroyPhotoViewer()` left the adapter registered, pinning the entire orphaned `PhotoViewer` (incl. 100 MB+ of GL paint buffers). Fix at the owner's terminal teardown, not in `onDetachedFromWindow` (the adapter re-adds observers only in its constructor, so detaching-then-reattaching would break search).
- `ChatBackgroundDrawable` leak class: an inverted `attachedViews.contains()` guard meant `onDetachedFromWindow` never removed views, so the background `ImageReceiver`'s global observers (`didReplacedPhotoInMemCache`, `stop/startAllHeavyOperations`) were never released — leaked one whole `ChatActivity` per closed chat, the single biggest `ChatActivity` retainer.
- `ReactionsEffectOverlay` leak class: removal was 100% draw-loop-driven with only two static tracking slots; overlays orphaned mid-animation stayed in the `DecorView`. Needed a fragment-keyed sweep on `onFragmentDestroy`.
- Paint editor (`LPhotoPaintView` / `Painting`) GL buffers (~26 MB each, a `DirectByteBuffer`) survive `photoPaintView.shutdown()` if any external ref pins the `RenderView`: `PhotoViewer.paintKeyboardAnimator` (an `AnimatorSet`) is only cancelled when re-triggered, so its `ObjectAnimator.mTarget` keeps `PaintWeightChooserView.renderView` alive after the editor closes; and the blur3 `ViewPositionWatcher` self-pins a subscribed view via a `WeakHashMap` whose *value* (the listener lambda) strongly captures the *key* view. Cancel/null the animator and unsubscribe the watched view at the paint-teardown site.

## Reporting

Return:

- Heap signals: suspicious class counts, retained sizes, report verdict.
- Root path: GC root -> owner chain -> leaked object cohort.
- Source explanation: exact add/register/create site and missing cleanup site.
- Confidence: confirmed, likely, or speculative.
- Fix shape: smallest lifecycle cleanup; avoid broad nulling unless it cuts the proven strong path.
- Verification: fresh dump should show suspect activity/view/adapter counts drop; compare before/after counts.
