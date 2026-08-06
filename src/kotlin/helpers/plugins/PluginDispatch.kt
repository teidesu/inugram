package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlWire
import org.telegram.messenger.Utilities

/**
 * The one rule every host callback into an engine obeys, in one place.
 *
 * A plugin that reloaded is running on a *different* engine whose request, invoke, callback and
 * menu ids all restart at 1, so a settle that crossed a queue hop has to prove the plugin is still
 * on the engine it left from - a missed check does not fail loudly, it resolves someone else's
 * pending promise with the wrong value.
 *
 * [onDropped] runs on the stale path, because some of these settles carry an obligation: a bitmap
 * to recycle, a response whose free stock suppressed, a fetched body's bytes.
 */
internal object PluginDispatch {
    /** is [plugin] still running on [engine]? Identity, not null: a reload swaps the instance. */
    fun isLive(plugin: Plugin, engine: QuickJs): Boolean = plugin.engine === engine

    /** post [block] to `globalQueue`, and run it only if the plugin is still on this engine */
    fun onEngine(plugin: Plugin, engine: QuickJs, onDropped: () -> Unit = {}, block: () -> Unit) {
        Utilities.globalQueue.postRunnable {
            if (isLive(plugin, engine)) block() else onDropped()
        }
    }

    /**
     * [onEngine] for the settles that answer exactly one wire. [produce] throwing is the host's own
     * bad day rather than the plugin's, so it becomes an `internal` error wire naming [what] rather
     * than escaping onto `globalQueue`.
     */
    fun settle(
        plugin: Plugin,
        engine: QuickJs,
        what: String,
        onDropped: () -> Unit = {},
        produce: () -> String,
        deliver: (String) -> Unit,
    ) = onEngine(plugin, engine, onDropped) { deliver(wireOf(what, produce)) }

    fun wireOf(what: String, produce: () -> String): String = try {
        produce()
    } catch (e: Exception) {
        TlWire.encodePluginError("internal", "$what: ${e.message ?: e.toString()}")
    }
}
