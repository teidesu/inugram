Test assets for the on-device plugin suite. `src/test/kotlin` is synced into a *kotlin* source
root, so anything that is not source lives here instead.

`probe.dex` is a dex the platform loader accepts, so `inu.jvm.loadDex` is checked by loading a class
out of it rather than against a recorded path. One class, built once and committed:

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

`defined_class_generated.dex` and `defined_class_forwarding.dex` came from the
isolated Rust emitter trial. Android SDK dexdump accepted both. The independent
smali 0.6.2 decoder confirmed their class structure and instructions match the
LSPlant-generated reference fixtures, normalizing equivalent const/4 and
const/16 encodings.

They cover interfaces, instance/static methods, dispatch fields, primitive
boxing/unboxing, arrays, wide arguments/results, and constructor range calls.
Native tests compare production output byte-for-byte. ART execution is covered
by the separate device class-definition suite, not established by these fixtures.
