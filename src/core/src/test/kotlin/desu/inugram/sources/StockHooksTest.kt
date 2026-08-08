package desu.inugram.sources

import desu.inugram.core.plugins.TlNames
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The engine's connection to the app, which is a handful of lines of stock java and nothing else.
 * Every test that drives the bridge drives it directly, so a stock call site that goes away during a
 * rebase takes the whole api with it while every suite stays green - which is exactly how
 * `maybeIntercept` was once absent from the worktree with nothing to show for it.
 *
 * Position is asserted wherever it carries meaning. A hook that still exists three lines lower can
 * be worse than one that is gone: `onDifference` below the secret-chat decrypt hands decrypted
 * secret-chat messages to every `onUpdate` listener, which is the one thing the update surface
 * promises it never does.
 */
class StockHooksTest {
    @Test
    fun `postInitApplication still boots the plugins, and cannot take the process down doing it`() {
        val body = bodyOf(stock("org/telegram/messenger/ApplicationLoader.java"), "public static void postInitApplication(")
        assertTrue(
            body.contains("desu.inugram.InuHooks.onAppBoot();"),
            "the plugin boot point is gone from ApplicationLoader.postInitApplication, so nothing a " +
                "headless path dispatches into is running by the time a push arrives",
        )
        assertTrue(
            Regex("""try\s*\{[^{}]*desu\.inugram\.InuHooks\.onAppBoot\(\);[^{}]*}\s*catch""").containsMatchIn(body),
            "onAppBoot is reached from ~22 entry points with applicationInited already true, so an " +
                "unguarded throw kills a push wakeup, a wear reply and a notification image load",
        )
    }

    @Test
    fun `every request the app sends is offered to the chain before anything else happens to it`() {
        val body = bodyOf(
            stock("org/telegram/tgnet/ConnectionsManager.java"),
            "public void sendRequestInternal(",
        )
        assertOpensWith(
            body,
            "if (desu.inugram.helpers.plugins.tg.PluginRpc.maybeIntercept(",
            "interceptRpc is bypassed for anything sendRequestInternal does before the hook, and the " +
                "fork calls this method itself, so it has to stay public too",
        )
    }

    @Test
    fun `every arriving batch is offered to the update chain before the app applies any of it`() {
        val body = bodyOf(
            stock("org/telegram/messenger/MessagesController.java"),
            "public void processUpdates(",
        )
        assertOpensWith(
            body,
            "if (desu.inugram.helpers.plugins.tg.PluginRpc.onUpdates(",
            "interceptUpdate takes the whole batch over by answering true here; below any of the " +
                "branches, a drop would come after the app already applied the update",
        )
    }

    /**
     * Nothing else notices this going away: the deserialize suite drives `PluginDeserialize.apply`
     * directly, so the one thing that makes any of it reach a real object is the call site in stock.
     */
    @Test
    fun `stock still calls the deserialize hook from the one place every TL object is parsed`() {
        val source = stock("org/telegram/tgnet/TLObject.java")
        val body = Regex(
            """object\.readParams\(stream, exception\);(.*?)return object;""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1)
        assertNotNull(body, "TLObject.TLdeserialize no longer has the shape the hook lives in")
        assertTrue(
            body.contains("PluginDeserialize.hot") && body.contains("PluginDeserialize.apply(object, constructor)"),
            "the interceptDeserialize hook is gone from TLObject.TLdeserialize: $body",
        )
    }

    @Test
    fun `the difference walk is handed over at the top of its runnable, above the secret-chat decrypt`() {
        val source = stock("org/telegram/messenger/MessagesController.java")
        val hook = "desu.inugram.helpers.plugins.tg.PluginRpc.onDifference("
        assertEquals(
            2,
            source.split(hook).size - 1,
            "updates.getDifference and getChannelDifference walk their own updates, so both need the hook",
        )
        val hooked = Regex("""Utilities\.stageQueue\.postRunnable\(\(\) -> \{""")
            .findAll(source)
            .map { it.range.last }
            .filter { source.substring(it + 1).trimStart().startsWith(hook) }
            .toList()
        assertEquals(
            2,
            hooked.size,
            "PluginRpc.onDifference is no longer the first statement of both difference runnables",
        )

        val decrypt = "getSecretChatHelper().decryptMessage("
        val carrying = hooked.filter { source.substring(blockAt(source, it)).contains(decrypt) }
        assertEquals(
            1,
            carrying.size,
            "getDifference appends the secret-chat messages it decrypted into res.new_messages, and " +
                "that has to happen inside a runnable the hook already read",
        )
        val block = source.substring(blockAt(source, carrying.single()))
        assertTrue(
            block.indexOf(hook) < block.indexOf(decrypt),
            "onDifference below the decrypt feeds decrypted secret-chat messages to every onUpdate " +
                "and onNewMessage listener",
        )
    }

    @Test
    fun `stock reaches the plugin engine from exactly these places`() {
        val root = stockRoot()
        val qualified = Regex("""desu\.inugram\.helpers\.plugins\.(?:\w+\.)*(\w+)\.(\w+)""")
        val found = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
            val source = file.readText()
            if (!source.contains("desu.inugram.")) return@forEach
            val path = file.relativeTo(root).invariantSeparatorsPath
            qualified.findAll(source).forEach { found.add("$path -> ${it.groupValues[1]}.${it.groupValues[2]}") }
            repeat(source.split("desu.inugram.InuHooks.onAppBoot").size - 1) {
                found.add("$path -> InuHooks.onAppBoot")
            }
        }
        assertEquals(
            listOf(
                "org/telegram/messenger/ApplicationLoader.java -> InuHooks.onAppBoot",
                "org/telegram/messenger/MessagesController.java -> PluginRpc.onDifference",
                "org/telegram/messenger/MessagesController.java -> PluginRpc.onDifference",
                "org/telegram/messenger/MessagesController.java -> PluginRpc.onUpdates",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.maybeIntercept",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestBoundToGuid",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestCancelled",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestsCancelledForGuid",
                "org/telegram/tgnet/TLObject.java -> PluginDeserialize.apply",
                "org/telegram/tgnet/TLObject.java -> PluginDeserialize.hot",
                "org/telegram/ui/LaunchActivity.java -> PluginScreens.onFragmentStackChanged",
            ),
            found.sorted(),
            "the set of stock call sites moved: a new one needs a case in this file, and a missing " +
                "one is an api that silently stopped existing",
        )
    }

    @Test
    fun `the constructors that bypass the deserialize hook are the ones the contract carves out`() {
        val bypassing = sortedSetOf<String>()
        File(stockRoot(), "org/telegram/tgnet").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val container = file.nameWithoutExtension
                Regex("""(?<![.\w])deserialize\(new (\w+)\(\), stream, exception\)""")
                    .findAll(file.readText())
                    .forEach { bypassing.add(TlNames.classNameToTlName(container, it.groupValues[1])) }
            }
        assertEquals(
            listOf("messages.foundStickers", "messages.foundStickersNotModified", "users.users", "users.usersSlice"),
            bypassing.toList(),
            "TLRPC.deserialize calls readParams itself, so what it parses never reaches " +
                "interceptDeserialize; TLRPC.java is generated and cannot be hooked, so the blind set " +
                "is documented in common.d.ts and has to be updated there when it moves",
        )

        val contract = File(forkRoot(), "src/plugins/common.d.ts").readText()
        assertTrue(
            contract.contains("`TLRPC.deserialize`"),
            "the carve-out sentence is gone from common.d.ts, which still promises every object the app parses",
        )
        for (name in bypassing) {
            assertTrue(contract.contains("`$name`"), "common.d.ts does not name `$name` as unreachable")
        }
    }
}
