package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlNamesTest {
    @Test
    fun convertsNamespacedMethodName() {
        assertEquals("messages.sendMessage", TlNames.classNameToTlName("TL_messages_sendMessage"))
    }

    @Test
    fun convertsSingleWordUpdateName() {
        assertEquals("updateNewMessage", TlNames.classNameToTlName("TL_updateNewMessage"))
    }

    @Test
    fun convertsSingleWordUnderscorelessType() {
        assertEquals("geoPoint", TlNames.classNameToTlName("TL_geoPoint"))
    }

    @Test
    fun onlyReplacesFirstUnderscoreWhenNamespaceIsLowercase() {
        assertEquals("channels.sendAsPeers", TlNames.classNameToTlName("TL_channels_sendAsPeers"))
    }

    @Test
    fun keepsRemainingUnderscoresAfterNamespace() {
        assertEquals("messages.sendMessage_extra", TlNames.classNameToTlName("TL_messages_sendMessage_extra"))
    }

    @Test
    fun leavesUnderscoreAsIsWhenLeadingSegmentIsNotLowercase() {
        // no realistic stock example of this shape, but the rule must not dot-ify a
        // capitalized/mixed leading segment
        assertEquals("Foo_bar", TlNames.classNameToTlName("TL_Foo_bar"))
    }

    @Test
    fun stripsLayerSuffixBeforeConverting() {
        assertEquals("messages.dhConfig", TlNames.classNameToTlName("TL_messages_dhConfig_layer131"))
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

    @Test
    fun reverseConvertsDottedNameToClassName() {
        assertEquals("TL_messages_sendMessage", TlNames.tlNameToClassName("messages.sendMessage"))
    }

    @Test
    fun reverseConvertsUndottedNameToClassName() {
        assertEquals("TL_updateNewMessage", TlNames.tlNameToClassName("updateNewMessage"))
    }

    @Test
    fun roundTripsNamespacedNames() {
        val tl = TlNames.classNameToTlName("TL_account_updateProfile")
        assertEquals("TL_account_updateProfile", TlNames.tlNameToClassName(tl))
    }
}
