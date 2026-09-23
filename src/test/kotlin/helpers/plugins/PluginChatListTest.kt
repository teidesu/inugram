package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginReads
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

// stock's `allDialogs`, `dialogsByFolder` and `dialogFilters` belong to the ui thread
class PluginChatListTest {
    // not `self`: inside a `TL_user` builder that name is the object's own boolean field
    private val selfId = 100L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, TLRPC.TL_user().apply { id = selfId; access_hash = selfId * 10 })
    }

    private fun granted() = startPlugin("chat-list", "account.read(self,peers,dialogs)")

    private var nextRequestId = 1L

    private fun fetch(plugin: Plugin, op: Int, args: String): String {
        val requestId = nextRequestId++
        plugin.js.readResults.clear()
        val inline = plugin.js.listener!!.accountFetch(0, requestId, op, "", args, "")
        if (inline != null) return inline
        settle()
        return plugin.js.readResults.single { it.requestId == requestId }.resultWire
    }

    private fun cached(plugin: Plugin, archive: Int = 0, folder: Int? = null, limit: Int = 0): List<String> {
        val wire = fetch(plugin, PluginReads.OP_DIALOGS_CACHED, "{\"archive\":$archive,\"chatFolderId\":${folder ?: "null"},\"limit\":$limit}")
        if (wire.isEmpty()) return emptyList()
        assertFalse(wire.startsWith("P") || wire.startsWith("E"), "unexpected error: $wire")
        return wire.split("\n")
    }

    private fun folders(plugin: Plugin): JSONArray {
        val value = PluginWire.decode(fetch(plugin, PluginReads.OP_CHAT_FOLDERS, "{}"))
        return JSONArray((value as PluginWire.Value.Json).json)
    }

    private fun dialog(id: Long, date: Int, folderId: Int = 0) = TLRPC.TL_dialog().apply {
        this.id = id
        peer = if (id > 0) TLRPC.TL_peerUser().apply { user_id = id } else TLRPC.TL_peerChat().apply { chat_id = -id }
        last_message_date = date
        folder_id = folderId
    }

    private fun seed(main: List<TLRPC.Dialog>, archived: List<TLRPC.Dialog> = emptyList()) = onUi {
        val controller = MessagesController.getInstance(0)
        controller.allDialogs.clear()
        controller.dialogsByFolder.put(0, ArrayList(main))
        controller.dialogsByFolder.put(1, ArrayList(archived))
        controller.allDialogs.addAll(main)
        controller.allDialogs.addAll(archived)
        for (dialog in main + archived) controller.dialogs_dict.put(dialog.id, dialog)
    }

    private fun seedFilter(filter: MessagesController.DialogFilter) = onUi {
        val controller = MessagesController.getInstance(0)
        controller.dialogFilters.clear()
        controller.dialogFilters.add(filter)
        controller.dialogFiltersById.put(filter.id, filter)
    }

    @Test
    fun the_main_list_is_what_an_omitted_archive_answers() {
        val plugin = granted()
        seed(main = listOf(dialog(222, 30), dialog(333, 20)), archived = listOf(dialog(444, 10, folderId = 1)))
        assertEquals(2, cached(plugin).size)
        assertEquals(1, cached(plugin, archive = 1).size)
        assertEquals(3, cached(plugin, archive = 2).size)
    }

    @Test
    fun a_limit_cuts_the_answer_and_keeps_the_apps_order() {
        val plugin = granted()
        seed(main = listOf(dialog(222, 30), dialog(333, 20), dialog(444, 10)))
        assertEquals(3, cached(plugin).size)
        assertEquals(2, cached(plugin, limit = 2).size)
    }

    // `TL_dialogFolder` is the archive row stock splices into `allDialogs`, and has no peer
    @Test
    fun the_archive_row_is_not_a_dialog() {
        val plugin = granted()
        val row = TLRPC.TL_dialogFolder().apply {
            id = DialogObject.makeFolderDialogId(1)
            folder = TLRPC.TL_folder().apply { this.id = 1 }
        }
        seed(main = listOf(row, dialog(222, 30)))
        assertEquals(1, cached(plugin).size)
    }

    @Test
    fun no_cached_read_reaches_a_secret_chat() {
        val plugin = granted()
        val secret = DialogObject.makeEncryptedDialogId(7)
        seed(main = listOf(dialog(secret, 40), dialog(222, 30)))
        assertEquals(1, cached(plugin).size)
        assertEquals(1, cached(plugin, archive = 2).size)

        seedFilter(
            MessagesController.DialogFilter().apply {
                id = 7
                name = "Work"
                dialogs.add(dialog(secret, 40))
                pinnedDialogs.put(secret, 0)
            },
        )
        assertEquals(0, cached(plugin, folder = 7).size)
        val folder = folders(plugin).getJSONObject(0)
        assertEquals("[]", folder.getJSONArray("pinned").toString())
        assertEquals(0, folder.getInt("dialogCount"))
    }

    @Test
    fun an_account_with_no_chat_list_yet_answers_empty_rather_than_failing() {
        val plugin = granted()
        seed(main = emptyList())
        onUi { MessagesController.getInstance(0).dialogFilters.clear() }
        assertTrue(cached(plugin).isEmpty())
        assertEquals(0, folders(plugin).length())
    }

    @Test
    fun a_chat_folder_carries_its_title_pins_and_count() {
        val plugin = granted()
        seed(main = listOf(dialog(222, 30)))
        seedFilter(
            MessagesController.DialogFilter().apply {
                id = 7
                name = "Work"
                color = 3
                unreadCount = 4
                dialogs.add(dialog(222, 30))
                pinnedDialogs.put(333, 1)
                pinnedDialogs.put(222, 0)
            },
        )
        val folder = folders(plugin).getJSONObject(0)
        assertEquals(7, folder.getInt("id"))
        assertEquals("Work", folder.getJSONObject("title").getString("text"))
        assertEquals(3, folder.getInt("colorIndex"))
        assertEquals(4, folder.getInt("unreadCount"))
        assertEquals(1, folder.getInt("dialogCount"))
        assertFalse(folder.getBoolean("isDefault"))
        assertEquals("[222,333]", folder.getJSONArray("pinned").toString())
    }

    @Test
    fun a_folder_with_no_colour_answers_null_rather_than_stocks_minus_one() {
        val plugin = granted()
        seedFilter(MessagesController.DialogFilter().apply { id = 0; name = "All chats"; color = -1 })
        val folder = folders(plugin).getJSONObject(0)
        assertTrue(folder.isNull("colorIndex"))
        assertTrue(folder.getBoolean("isDefault"))
    }

    @Test
    fun naming_a_chat_folder_answers_that_folders_own_dialogs() {
        val plugin = granted()
        seed(main = listOf(dialog(222, 30), dialog(333, 20)))
        seedFilter(
            MessagesController.DialogFilter().apply {
                id = 7
                name = "Work"
                dialogs.add(dialog(333, 20))
            },
        )
        assertEquals(1, cached(plugin, folder = 7).size)
    }

    @Test
    fun an_unknown_chat_folder_is_not_found_rather_than_empty() {
        val plugin = granted()
        seed(main = listOf(dialog(222, 30)))
        val error = PluginWire.decode(fetch(plugin, PluginReads.OP_DIALOGS_CACHED, "{\"archive\":0,\"chatFolderId\":99,\"limit\":0}"))
        assertEquals("not-found", (error as PluginWire.Value.PluginErr).code)
    }

}
