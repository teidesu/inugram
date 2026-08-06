package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of `inu.interceptDeserialize`'s refusals that needs no TL tree to decide. Every one of
 * them exists because a rule's output is written back to sqlite, so what this table gets wrong the
 * user cannot restart away from.
 */
class DeserializeGuardsTest {
    @Test
    fun anIdentityFieldIsProtected() {
        assertTrue(DeserializeGuards.isProtectedField("id"))
        assertTrue(DeserializeGuards.isProtectedField("user_id"))
        assertTrue(DeserializeGuards.isProtectedField("channel_id"))
        assertTrue(DeserializeGuards.isProtectedField("via_bot_id"))
        assertTrue(DeserializeGuards.isProtectedField("grouped_id"))
    }

    @Test
    fun theAddressingFieldsWithNoIdSuffixAreProtected() {
        assertTrue(DeserializeGuards.isProtectedField("access_hash"))
        assertTrue(DeserializeGuards.isProtectedField("dc_id"))
        assertTrue(DeserializeGuards.isProtectedField("file_reference"))
    }

    /** a field that describes rather than addresses is the whole point of the api */
    @Test
    fun aDescribingFieldIsNotProtected() {
        for (name in listOf("premium", "message", "title", "about", "mime_type", "noforwards_my_enabled")) {
            assertFalse(name, DeserializeGuards.isProtectedField(name))
        }
    }

    /** `_id` is a suffix, not a substring: `identity_check` would be a field a rule may set */
    @Test
    fun theSuffixRuleIsASuffix() {
        assertFalse(DeserializeGuards.isProtectedField("id_card"))
        assertFalse(DeserializeGuards.isProtectedField("valid"))
        assertFalse(DeserializeGuards.isProtectedField("pinned"))
    }

    /**
     * the whole family, spelled the several ways stock spells it. A prefix list would have let
     * `updateNewEncryptedMessage` and `messages.sentEncryptedMessage` through, and those are the
     * ones that carry the ciphertext into the app.
     */
    @Test
    fun everyNameTheTableCarriesThatMentionsACipherIsSecret() {
        val uncovered = TlCtorIds.allNames.filter {
            it.lowercase().let { l -> l.contains("encrypt") || l.contains("decrypt") } &&
                !DeserializeGuards.isSecretName(it)
        }
        assertEquals(emptyList<String>(), uncovered.sorted())
        assertTrue(DeserializeGuards.isSecretName("updateNewEncryptedMessage"))
        assertTrue(DeserializeGuards.isSecretName("messages.sentEncryptedMessage"))
        assertTrue(DeserializeGuards.isSecretName("message_secret_old"))
    }

    /**
     * the four the update fan-out refuses by class, spelled here because `PluginRpc` is not in this
     * module: the two rules are the same rule, and a name only one of them catches is a constructor
     * a plugin can reach through `interceptDeserialize` after `onUpdate` turned it away.
     */
    @Test
    fun theUpdatesTheFanOutRefusesAreAlsoRefusedAsRuleTargets() {
        for (name in listOf(
            "updateNewEncryptedMessage",
            "updateEncryption",
            "updateEncryptedChatTyping",
            "updateEncryptedMessagesRead",
        )) {
            assertTrue(name, DeserializeGuards.isSecretName(name))
            assertTrue(name, TlCtorIds.idsOf(name).orEmpty().isNotEmpty())
        }
    }

    /** the families the api is for must not be caught by the same rule */
    @Test
    fun anOrdinaryConstructorIsNotSecret() {
        for (name in listOf("message", "user", "userFull", "chat", "document", "updateNewMessage")) {
            assertFalse(name, DeserializeGuards.isSecretName(name))
        }
    }
}
