# HeapProbe — programmatic MAT queries

`scripts/heapprobe/` is a small Eclipse MAT plugin (an OSGi `IApplication`) that answers
the questions the HTML reports can't, and doesn't crash on corrupt strings the way the
"Leak Suspects / Details" renderer sometimes does. Reach for it once the reports have
pointed you at a suspect cohort and you need to prove the retention.

**It's a starting point, not a straitjacket. Feel free to add commands or edit
`HeapProbeApp.java` for whatever the current dump needs** — a bespoke reconcile pass,
a cross-cohort set intersection, a field-value dump. The build script recompiles on any
source change. The commands below are just the ones that have paid off repeatedly; don't
contort a question to fit them when a five-line new method would answer it directly.

## Why a plugin and not `java -jar`

`SnapshotFactory.openSnapshot` needs a live OSGi context (the parser registers itself via
`ParserPlugin`; outside equinox you get `ParserPlugin.getDefault() == null` /
`NoClassDefFoundError`). So the probe is packaged as a bundle and launched by MAT's own
equinox launcher. `build_and_run.sh` handles compile + jar + registration + launch.

## Running

Java is not available inside the agent sandbox — run with the sandbox disabled.

```bash
MAT=/Applications/MemoryAnalyzer.app \
  scripts/heapprobe/build_and_run.sh <dump.ec.hprof> <command> [args...]
```

First run compiles `HeapProbeApp.java`, jars it into `$MAT/Contents/Eclipse/plugins/`,
and appends one line to `bundles.info` (backed up as `bundles.info.bak`). Later runs skip
straight to launch unless the `.java` changed. A `$` in a class name must be single-quoted
so the shell doesn't treat it as a variable: `'...LPhotoPaintView$2'`.

Parsing a ~600 MB dump the first time takes a few minutes (MAT builds its index next to the
dump). Subsequent runs reuse that index and start in seconds.

## Commands

| command | args | what it answers |
| --- | --- | --- |
| `counts` | `<class>...` | how many instances of each class (leak signal = dozens/hundreds of an Activity/View/Fragment) |
| `histogram` | `<class>...` | inbound-referrer class histogram — what *kinds* of objects point at the cohort |
| `inbound` | `<class> [limit=6]` | per-instance inbound refs, sorted by retained — the concrete referrers of the biggest instances |
| `paths` | `<class> [objs=3] [paths=4]` | **strong** GC-root paths (weak/soft/phantom excluded) for the biggest instances |
| `tally` | `<class> [kw1,kw2,...]` | one shortest strong path per instance, bucketed by first matching holder — the **root distribution** of a multiply-held cohort |
| `observers` | `<class>` | how many instances sit in `NotificationCenter` observer lists, by event id — the add/remove-asymmetry smoking gun |

### `tally` is the workhorse for multiply-held cohorts

When a leaked class shows up as a **top-level dominator** in Top Consumers (its immediate
dominator is the root set), it's reachable through several independent paths — no single
reference cut frees it. `tally` walks one shortest strong path per instance and buckets each
by the first class along the path that matches a keyword you pass. Give it the candidate
holder names you're testing (comma-separated substrings). The bucket counts tell you how
many instances each distinct leak accounts for, so you fix roots in impact order.

Example — 90 `ChatActivity`, three independent leaks:
```
tally org.telegram.ui.ChatActivity ChatBackgroundDrawable,LaunchActivity,ReactionsEffectOverlay
-- FIRST-STRONG-HOLDER TALLY --
  57  org.telegram.ui.ChatBackgroundDrawable
   9  org.telegram.ui.LaunchActivity
   9  org.telegram.ui.Components.Reactions.ReactionsEffectOverlay$1
   ...
```
With no keywords it falls back to the first app-package class that isn't the target or one
of its own inner classes / a generic container — a decent auto-guess, but explicit keywords
give crisper buckets (e.g. it surfaces `ChatBackgroundDrawable` rather than the intervening
`SizeNotifierFrameLayout$BackgroundView`).

### `paths` vs. the HTML leak-suspects report — the weak-ref trap

MAT's HTML leak-suspects report does **not** exclude weak/soft references, so it will happily
draw a path through something like `ObjectAnimator.mTarget` that may be a `WeakReference` and
therefore not actually retaining anything. `paths` excludes weak/soft/phantom referents, so a
path it still reports is a genuine strong retention. Always re-confirm a suspect path with
`paths` before naming it the root. (In one case `ObjectAnimator.mTarget` turned out to be a
real strong ref on that SDK and the leak was real — but the check is what told us so.)

## Editing the probe

The helper methods (`idsFor`, `retained`, `refsTo`, `obj`, `excludeMap`, `sortedByRetained`)
are the reusable primitives; new commands are typically ~10 lines on top of them. Useful MAT
API surface already in use: `snapshot.getInboundRefererIds(id)`,
`snapshot.getPathsFromGCRoots(id, excludeMap)` +
`IPathsFromGCRootsComputer.getNextShortestPath()`, `snapshot.getRetainedHeapSize(id)`,
`object.resolveValue("field.path")`, `object.getOutboundReferences()`,
`snapshot.mapIdToAddress` / `mapAddressToId`, and `IObjectArray.getReferenceArray()` for
walking native arrays (that's how `observers` reads the `NotificationCenter` `SparseArray`).
