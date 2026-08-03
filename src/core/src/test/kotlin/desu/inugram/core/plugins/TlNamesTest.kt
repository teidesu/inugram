package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlNamesTest {
    @Test
    fun convertsNamespacedMethodName() {
        assertEquals("messages.sendMessage", TlNames.classNameToTlName("TLRPC", "TL_messages_sendMessage"))
    }

    @Test
    fun convertsSingleWordUpdateName() {
        assertEquals("updateNewMessage", TlNames.classNameToTlName("TLRPC", "TL_updateNewMessage"))
    }

    @Test
    fun convertsSingleWordUnderscorelessType() {
        assertEquals("geoPoint", TlNames.classNameToTlName("TLRPC", "TL_geoPoint"))
    }

    @Test
    fun keepsRemainingUnderscoresAfterNamespace() {
        assertEquals("messages.sendMessage_extra", TlNames.classNameToTlName("TLRPC", "TL_messages_sendMessage_extra"))
    }

    @Test
    fun leavesLeadingSegmentAloneWhenItIsNotANamespace() {
        // the whole reason the namespace set exists: `user` is a type, so this is not `user.old`
        assertEquals("user_old", TlNames.classNameToTlName("TLRPC", "TL_user_old"))
        assertEquals("message_old7", TlNames.classNameToTlName("TLRPC", "TL_message_old7"))
    }

    @Test
    fun namespacesBareNamedClassesFromTheOverrideTable() {
        assertEquals("account.contentSettings", TlNames.classNameToTlName("TL_account", "contentSettings"))
    }

    @Test
    fun takesTheMemberFromTheSchemaWhereTheJavaNameDiffers() {
        assertEquals("stats.getPollStats", TlNames.classNameToTlName("TL_stats", "TL_statsGetPollStats"))
        assertEquals("messages.savedReactionTags", TlNames.classNameToTlName("TLRPC", "TL_messages_savedReactionsTags"))
    }

    @Test
    fun keepsTheDerivedNameWhenTheSchemaNameIsAlreadyClaimed() {
        // both are `message` on the wire; the modern one takes it, the legacy one stays reachable
        assertEquals("message", TlNames.classNameToTlName("TLRPC", "TL_message"))
        assertEquals("message_old7", TlNames.classNameToTlName("TLRPC", "TL_message_old7"))
    }

    @Test
    fun overrideWinsOverTheContainerTheClassIsFiledUnder() {
        assertEquals("payments.transferStarGift", TlNames.classNameToTlName("TL_stars", "transferStarGift"))
        assertEquals("users.users", TlNames.classNameToTlName("TLRPC", "TL_users"))
    }

    @Test
    fun leavesContainerlessClassesUnnamespaced() {
        // TL_stories holds both `stories.*` types and plain ones; the container decides nothing
        assertEquals("storyView", TlNames.classNameToTlName("TL_stories", "TL_storyView"))
        assertEquals("stories.storyViews", TlNames.classNameToTlName("TL_stories", "TL_stories_storyViews"))
    }

    @Test
    fun distinguishesSameNamedClassesInDifferentContainers() {
        assertEquals("account.reorderUsernames", TlNames.classNameToTlName("TL_account", "reorderUsernames"))
        assertEquals("bots.reorderUsernames", TlNames.classNameToTlName("TL_bots", "reorderUsernames"))
    }

    @Test
    fun stripsLayerSuffixBeforeConverting() {
        assertEquals("messages.dhConfig", TlNames.classNameToTlName("TLRPC", "TL_messages_dhConfig_layer131"))
    }

    @Test
    fun isLayerVariantDetectsSuffix() {
        assertTrue(TlNames.isLayerVariant("TL_messages_dhConfig_layer131"))
        assertFalse(TlNames.isLayerVariant("TL_messages_dhConfig"))
    }

    @Test
    fun stripLayerSuffixOnlyStripsTrailingMatch() {
        assertEquals("TL_messages_dhConfig", TlNames.stripLayerSuffix("TL_messages_dhConfig_layer131"))
        assertEquals("TL_messages_dhConfig", TlNames.stripLayerSuffix("TL_messages_dhConfig"))
    }
}
