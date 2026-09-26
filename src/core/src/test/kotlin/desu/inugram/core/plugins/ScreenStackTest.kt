package desu.inugram.core.plugins

import desu.inugram.core.plugins.ScreenChangeAction.POP
import desu.inugram.core.plugins.ScreenChangeAction.PUSH
import desu.inugram.core.plugins.ScreenChangeAction.REPLACE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenStackTest {
    private val dialogs = ScreenRef("dialogs")
    private val chatA = ScreenRef("chat", dialogId = -1001)
    private val chatB = ScreenRef("chat", dialogId = 42)
    private val profile = ScreenRef("profile", dialogId = 42)
    private val settings = ScreenRef("settings")

    @Test
    fun opening_a_screen_from_the_list_is_a_push() {
        assertEquals(PUSH, ScreenStack.diff(listOf(dialogs), listOf(dialogs, chatA)))
    }

    @Test
    fun going_back_is_a_pop() {
        assertEquals(POP, ScreenStack.diff(listOf(dialogs, chatA), listOf(dialogs)))
    }

    /**
     * the case `screen`/`previous` alone cannot tell from a push: chat -> profile arrives either
     * way, and only the stack shape says which
     */
    @Test
    fun closing_a_chat_to_reveal_a_profile_underneath_is_a_pop_not_a_push() {
        assertEquals(POP, ScreenStack.diff(listOf(dialogs, profile, chatA), listOf(dialogs, profile)))
        assertEquals(PUSH, ScreenStack.diff(listOf(dialogs, chatA), listOf(dialogs, chatA, profile)))
    }

    @Test
    fun same_depth_with_a_different_top_is_a_replace() {
        assertEquals(REPLACE, ScreenStack.diff(listOf(dialogs, chatA), listOf(dialogs, chatB)))
    }

    @Test
    fun a_rebuild_ending_on_the_same_screen_is_not_dispatched() {
        assertEquals(null, ScreenStack.diff(listOf(dialogs, chatA), listOf(dialogs, chatA)))
        // and the whole point of value identity: the fragments are new objects, the screens are not
        val rebuilt = listOf(ScreenRef("dialogs"), ScreenRef("chat", dialogId = -1001))
        assertEquals(null, ScreenStack.diff(listOf(dialogs, chatA), rebuilt))
    }

    @Test
    fun removing_something_buried_is_not_dispatched() {
        assertEquals(null, ScreenStack.diff(listOf(dialogs, profile, chatA), listOf(dialogs, chatA)))
        assertEquals(null, ScreenStack.diff(listOf(dialogs, chatA), listOf(chatA)))
    }

    @Test
    fun the_empty_edges_are_a_push_and_a_pop() {
        assertEquals(PUSH, ScreenStack.diff(emptyList(), listOf(dialogs)))
        assertEquals(POP, ScreenStack.diff(listOf(dialogs), emptyList()))
        assertEquals(null, ScreenStack.diff(emptyList(), emptyList()))
    }

    /** a bulk swap is nobody's prefix; `action` is closed, so depth is the only honest label left */
    @Test
    fun a_stack_swap_falls_back_on_depth() {
        assertEquals(POP, ScreenStack.diff(listOf(dialogs, profile, chatA), listOf(dialogs, settings)))
        assertEquals(PUSH, ScreenStack.diff(listOf(dialogs), listOf(settings, chatA)))
        assertEquals(REPLACE, ScreenStack.diff(listOf(dialogs, chatA), listOf(settings, chatB)))
    }

    /** the topic is part of the identity, or moving between two topics of one forum is invisible */
    @Test
    fun the_same_chat_in_two_topics_is_two_screens() {
        val general = ScreenRef("chat", dialogId = -1001)
        val topic = ScreenRef("chat", dialogId = -1001, topicId = 7)
        assertNotEquals(general, topic)
        assertEquals(REPLACE, ScreenStack.diff(listOf(dialogs, general), listOf(dialogs, topic)))
    }

    /** the same chat on two accounts is likewise two screens */
    @Test
    fun the_same_chat_on_two_accounts_is_two_screens() {
        val first = ScreenRef("chat", dialogId = 42, accountId = 0)
        val second = ScreenRef("chat", dialogId = 42, accountId = 1)
        assertEquals(REPLACE, ScreenStack.diff(listOf(dialogs, first), listOf(dialogs, second)))
    }
}
