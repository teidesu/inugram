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
            "if (desu.inugram.helpers.plugins.telegram.PluginRpc.maybeIntercept(",
            "interceptRpc is bypassed for anything sendRequestInternal does before the hook, and the " +
                "fork calls this method itself, so it has to stay public too",
        )
    }

    @Test
    fun `a plugin-dropped send is removed instead of becoming a failed local message`() {
        val source = stock("org/telegram/messenger/SendMessagesHelper.java")
        val hook = "desu.inugram.helpers.plugins.telegram.PluginRpc.handleDroppedSend(this, currentAccount, newMsgObj, scheduled, error)"
        val multiHook = "desu.inugram.helpers.plugins.telegram.PluginRpc.handleDroppedSends(this, currentAccount, msgObjs, scheduled, error)"
        val editHook = "desu.inugram.helpers.plugins.telegram.PluginRpc.isDroppedSendError(error)"
        assertTrue(source.contains("if ($hook) {\n                            return;"), "the dropped-send cleanup hook is gone")
        assertTrue(source.contains("if ($multiHook) {\n                        return;"), "the dropped-album cleanup hook is gone")
        assertTrue(source.contains("if ($editHook) {\n                            removeFromSendingMessages"), "the dropped-edit cleanup hook is gone")
        assertTrue(
            source.indexOf(hook) < source.indexOf("AlertsCreator.processError(currentAccount, error, null, req);", source.indexOf(hook)),
            "a dropped send reached stock's failed-message handling before it could be removed",
        )
    }

    @Test
    fun `outgoing request rewrites are bound back to their optimistic messages`() {
        val source = stock("org/telegram/messenger/SendMessagesHelper.java")
        assertEquals(1, source.split("PluginRpc.bindOptimisticMessage(req, currentAccount, msgObj);").size - 1)
        assertEquals(1, source.split("PluginRpc.bindOptimisticMessages(request, currentAccount, msgObjs);").size - 1)
    }

    @Test
    fun `every arriving batch is offered to the update chain before the app applies any of it`() {
        val body = bodyOf(
            stock("org/telegram/messenger/MessagesController.java"),
            "public void processUpdates(",
        )
        assertOpensWith(
            body,
            "if (desu.inugram.helpers.plugins.telegram.PluginUpdates.onUpdates(",
            "interceptUpdate takes the whole batch over by answering true here; below any of the " +
                "branches, a drop would come after the app already applied the update",
        )
    }

    /**
     * The two compressed short forms carry no `Update`, so `PluginUpdates.normalizeShortMessage` has to
     * build the message stock would have applied. It calls stock's own builder for it, and this is
     * what keeps that true: a second copy drifts silently, which is how it once lost `unread` and
     * the Saved Messages adjustments that stock does at the end.
     */
    @Test
    fun `the short-form message is built once, by stock, for both the app and the update chain`() {
        val source = stock("org/telegram/messenger/MessagesController.java")
        val builder = bodyOf(source, "public TLRPC.TL_message inu_buildShortMessage(")
        assertTrue(
            builder.contains("message.unread = value < message.id;") && builder.contains("message.media = new TLRPC.TL_messageMediaEmpty();"),
            "inu_buildShortMessage no longer builds the whole message",
        )
        val branch = bodyOf(source, "public void processUpdates(")
        assertTrue(
            branch.contains("TLRPC.TL_message message = inu_buildShortMessage(updates);"),
            "stock's own updateShortMessage branch stopped using the extracted builder, so the fork " +
                "copy of it is now the only one and nothing says when they disagree",
        )
        assertEquals(
            1,
            source.split("message.via_bot_id = updates.via_bot_id;").size - 1,
            "the short-form message construction is back in two places",
        )
    }

    /**
     * A drop is this line and nothing else. The batch is handed back whole so that stock's pts
     * arithmetic is untouched, which means an absent hook does not fail loudly - it applies every
     * update a plugin refused, and the only thing that would ever say so is this.
     */
    @Test
    fun `the update loop still asks whether it may apply each update`() {
        val body = bodyOf(
            stock("org/telegram/messenger/MessagesController.java"),
            "public boolean processUpdateArray(",
        )
        assertTrue(
            Regex("""if \(desu\.inugram\.helpers\.plugins\.telegram\.PluginUpdates\.isDropped\(baseUpdate\)\) \{\s*continue;""")
                .containsMatchIn(body),
            "interceptUpdate's 'drop' verdict is enforced here and nowhere else: without it every " +
                "dropped update is applied, and the batch still carries them all by design",
        )
    }

    @Test
    fun `the difference walk is handed over at the top of its runnable, above the secret-chat decrypt`() {
        val source = stock("org/telegram/messenger/MessagesController.java")
        val hook = "desu.inugram.helpers.plugins.telegram.PluginUpdates.onDifference("
        assertEquals(
            2,
            source.split(hook).size - 1,
            "updates.getDifference and getChannelDifference walk their own updates, so both need the hook",
        )
        // an anonymous Runnable rather than a lambda, and that is load-bearing: `this` is the
        // continuation the hook parks and re-runs, and a lambda's `this` is the controller
        val hooked = Regex("""Utilities\.stageQueue\.postRunnable\(new Runnable\(\) \{ public void run\(\) \{""")
            .findAll(source)
            .map { it.range.last }
            .filter { source.substring(it + 1).trimStart().startsWith("if ($hook") }
            .toList()
        assertEquals(
            2,
            hooked.size,
            "PluginUpdates.onDifference is no longer the first statement of both difference runnables",
        )
        for (at in hooked) {
            val call = source.substring(at, source.indexOf(')', source.indexOf(hook, at)))
            assertTrue(
                call.contains("currentAccount, this"),
                "the hook is handed the runnable it must re-run, so anything but `this` parks the " +
                    "difference forever: $call",
            )
        }

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
                "org/telegram/messenger/MessagesController.java -> PluginUpdates.isDropped",
                "org/telegram/messenger/MessagesController.java -> PluginUpdates.onDifference",
                "org/telegram/messenger/MessagesController.java -> PluginUpdates.onDifference",
                "org/telegram/messenger/MessagesController.java -> PluginUpdates.onUpdates",
                "org/telegram/messenger/SendMessagesHelper.java -> PluginRpc.bindOptimisticMessage",
                "org/telegram/messenger/SendMessagesHelper.java -> PluginRpc.bindOptimisticMessages",
                "org/telegram/messenger/SendMessagesHelper.java -> PluginRpc.handleDroppedSend",
                "org/telegram/messenger/SendMessagesHelper.java -> PluginRpc.handleDroppedSends",
                "org/telegram/messenger/SendMessagesHelper.java -> PluginRpc.isDroppedSendError",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.maybeIntercept",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestBoundToGuid",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestCancelled",
                "org/telegram/tgnet/ConnectionsManager.java -> PluginRpc.onRequestsCancelledForGuid",
                "org/telegram/ui/LaunchActivity.java -> PluginScreens.onFragmentStackChanged",
            ),
            found.sorted(),
            "the set of stock call sites moved: a new one needs a case in this file, and a missing " +
                "one is an api that silently stopped existing",
        )
    }

}
