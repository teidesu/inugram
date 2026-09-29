Assets for device plugin tests. Keep non-source files here because `src/test/kotlin` syncs
to a Kotlin source root.

`routines.json` is compiler output pinned by `sdk/cli/test/routines-runtime.test.ts`.
`PluginJvmRoutineTest` executes it through the actual Kotlin interpreter and checks
return values and side-effect order. After reviewing compiler changes, regenerate
with `pnpm --filter @inugram/cli test --update`; do not hand-edit the bytecode.

`probe.dex` tests `inu.jvm.loadDex` by loading a real class. It contains one class,
built with these commands and committed:

    javac --release 8 desu/inugram/probe/Probe.java
    d8 --min-api 26 --output . desu/inugram/probe/Probe.class

```java
package desu.inugram.probe;

public class Probe {
    public static String greet() {
        return "loaded from dex";
    }
}
```

`defined_class_generated.dex` and `defined_class_forwarding.dex` are the Rust
emitter's output, written by `dex_tests.rs` when `INU_DEX_TEST_OUTPUT` names a
directory. Android SDK dexdump accepted both. An earlier version was checked with
the independent smali 0.6.2 decoder against the LSPlant-generated reference
fixtures; constructors have since changed to fetch every super argument with one
`getSuperArguments` call.

They cover interfaces, instance/static methods, dispatch fields, primitive
boxing/unboxing, arrays, wide arguments/results, and constructor range calls.
Native tests compare production output byte-for-byte. ART execution is covered
by the separate device class-definition suite, not established by these fixtures.

`canvas-variable-delay.gif` is a 16×16 GIF with frame durations of 900, 100, and 200 ms.
It checks that `inu.canvas.decodeAnimation` preserves variable frame timing.
