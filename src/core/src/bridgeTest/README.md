# `bridgeTest` — unit tests for the plugin bridge

`src/kotlin/helpers/plugins` lives in the app module, which needs the whole android toolchain, so
nothing could test it: reverting a guard in `TlFilter`, `TlHandles`, `TlJson` or `PluginRpc` left
every suite green. This source set compiles those files *here*, in `:InuCore`, against a fake stock
runtime, and runs them on a plain JVM.

```
./gradlew :InuCore:bridgeTest    # this suite
./gradlew :InuCore:check         # it plus the pure-core tests
```

It is a source set of its own, not `test`: the pure-core suite stays fast and stays green when the
bridge does not compile. Nothing here reaches the app build — `:InuCore`'s main jar is untouched.

## What is in it

| dir | what | maintained by |
| --- | --- | --- |
| `tlstubs/` | the whole stock TL type tree — 3271 classes, their nesting, superclasses, constructor ids and public fields | **generated**, gitignored, built by `:InuCore:generateTlStubs` |
| `fakes/` | everything else stock: `android.util.*`, `android.os.SystemClock`, the two dispatch queues, `ConnectionsManager`, `TLObject` | by hand |
| `kotlin/` | the doubles for `QuickJs` and `PluginManager`, the shared fixture, the tests | by hand |

`tlstubs/` is shape only: no serialization, no behaviour. It comes out of the same parse
`generate-tl-typings` uses, so it cannot drift from stock the way a hand-written stub would. The
bridge compile depends on `generateTlStubs`, so a rebase needs nothing done by hand; gradle skips the
task while stock is unchanged, so a checkout without node still builds after the first run.
`HarnessIntegrityTest` still fails loudly when the committed id table is stale against it.

## What is faked, and how honestly

- **The queues are one thread and one virtual clock.** `Utilities.globalQueue`/`stageQueue` order by
  (due time, post order), which is what a `Handler` gives a single queue and the only cross-queue
  property the bridge is allowed to depend on. `advanceBy` jumps the clock, so a 10 s chain budget
  costs a microsecond.
- **`SystemClock.uptimeMillis` is that same clock**, because the chain budget counts in it.
- **`ConnectionsManager` never answers on its own.** A test answers the send it wants to, which is
  how a passthrough racing a cancel, or stock's `CONNECTION_NOT_INITED` re-send, is written.
- **`QuickJs` and `PluginManager` are doubles**, and they are the only two. The real `QuickJs` calls
  `nativeCreate()` from its constructor and loads `libinu_native` from its class initializer;
  `PluginManager` reaches `Context`/`AlarmManager`/`ShortcutManager`. Both doubles are *shadowing*:
  the bridge is compiled against them, so a signature change in the real interface is a compile
  error here rather than a silent divergence. What they cannot catch is behavioural drift, which is
  why `HarnessIntegrityTest` re-reads the real `PluginManager` source.
- **`org.json` is the reference implementation, not android's.** Same api, different code: android's
  `JSONObject` is `LinkedHashMap`-backed, so `keys()` comes back in insertion order, while
  `org.json:json` is `HashMap`-backed and its order is arbitrary. Several bridge files iterate
  `keys()` (`PluginDeserialize.readTerms`, `PluginFetch.Spec.parse`) or emit a whole
  `JSONObject.toString()`. **Never assert on key order or on an object's serialized form** - the
  device produces a different string and no test here would say so. Assert on parsed fields.

## Adding to it

A new file in `src/kotlin/helpers/plugins` is compiled here **by default**. If it cannot be — it
reaches an `Activity`, a `Context`, the JNI library — add it to `bridgeExcluded` in
`src/core/build.gradle` and say why. `HarnessIntegrityTest` fails if that list names a file the
bridge no longer has.

Not covered, deliberately: `PluginApi`, `PluginManager`, `PluginUi`. They are android
surface (activities, toasts, shortcuts) rather than the reflection/handle/chain code the
security rules live in. Reaching them needs Robolectric, which is a much larger dependency than what
it would buy today. `PluginKv` is covered: its quota arithmetic is a sum over a map and needs
nothing of android but the store's own shape, which `fakes/android/content/SharedPreferences.java`
supplies.
