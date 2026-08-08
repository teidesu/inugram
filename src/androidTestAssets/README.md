Test assets for the on-device plugin suite. `src/androidTest` itself is synced into a *kotlin*
source root, so anything that is not source lives here instead.

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
