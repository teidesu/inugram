package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFilterTest {
    @Test
    fun sixDigitCodeIsRedactedAndLengthIsPreserved() {
        val input = "Login code: 123456. Do not give it to anyone."
        val output = ApiFilter.redactLoginCodes(input)
        assertEquals("Login code: ******. Do not give it to anyone.", output)
        assertEquals(input.length, output.length)
    }

    @Test
    fun fourDigitRunIsBelowTheThreshold() {
        assertEquals("your order 1234 shipped", ApiFilter.redactLoginCodes("your order 1234 shipped"))
    }

    @Test
    fun runLongerThanEightIsRedactedWhole() {
        // stock spoils only `[\d\-]{5,8}`, which would leave the last 3 chars of this readable
        val input = "code 12345678901 now"
        val output = ApiFilter.redactLoginCodes(input)
        assertEquals("code *********** now", output)
        assertEquals(input.length, output.length)
    }

    @Test
    fun everyRunInTheStringIsRedacted() {
        assertEquals("***** and ******", ApiFilter.redactLoginCodes("12345 and 678901"))
    }

    @Test
    fun hyphenatedFormIsRedacted() {
        assertEquals("code ******", ApiFilter.redactLoginCodes("code 12-345"))
    }

    @Test
    fun textWithoutACodeShapedRunIsUnchanged() {
        val input = "hello there, nothing to see"
        assertEquals(input, ApiFilter.redactLoginCodes(input))
    }

    @Test
    fun codeAfterANonBmpEmojiRedactsAtTheRightOffset() {
        // entity offsets are UTF-16 code units, so the surrogate pair must count as 2
        val input = "🐶 code 123456"
        val output = ApiFilter.redactLoginCodes(input)
        assertEquals("🐶 code ******", output)
        assertEquals(input.length, output.length)
        assertEquals(input.indexOf('1'), output.indexOf('*'))
    }

    @Test
    fun serviceSendersAreTheTwoDocumentedPeers() {
        assertTrue(ApiFilter.isServiceSender(777000L))
        assertTrue(ApiFilter.isServiceSender(489000L))
        assertFalse(ApiFilter.isServiceSender(42L))
    }

    @Test
    fun aSenderNamedByFromIdIsService() {
        assertTrue(ApiFilter.isServiceMessage(777000L, 42L, null, out = false))
        assertFalse(ApiFilter.isServiceMessage(42L, 42L, null, out = false))
    }

    @Test
    fun anIncomingMessageWithNoFromIdFallsBackToTheDialogPeer() {
        // regression: from_id is flags.8 and the server omits it in a 1:1 dialog, so keying on it
        // alone left every login code read straight off the wire in clear
        assertTrue(ApiFilter.isServiceMessage(null, 777000L, null, out = false))
        assertTrue(ApiFilter.isServiceMessage(null, 489000L, null, out = false))
    }

    @Test
    fun anOutgoingMessageIsNeverServiceByItsDialogPeer() {
        // the peer is the recipient, not the sender, so our own message to 777000 stays untouched
        assertFalse(ApiFilter.isServiceMessage(null, 777000L, null, out = true))
    }

    @Test
    fun aForwardedServiceMessageIsStillService() {
        // a copy in saved messages carries out=true and peer_id=self, sender only in fwd_from
        assertTrue(ApiFilter.isServiceMessage(null, 12345L, 777000L, out = true))
    }

    @Test
    fun aMessageWithNoUserPeerAtAllIsNotService() {
        assertFalse(ApiFilter.isServiceMessage(null, null, null, out = false))
    }

    @Test
    fun theSealedFieldsAreExactlyWhatTheVerdictReads() {
        // a plugin holding a writable view rewrites these to clear the sender and re-read the text,
        // so the set has to track isServiceMessage's parameters exactly
        assertEquals(setOf("from_id", "peer_id", "fwd_from", "out"), ApiFilter.REDACTION_EVIDENCE_FIELDS)
    }

    @Test
    fun filteredTypesStillExistInTheSchema() {
        // regression: a rename in TLRPC.java must not silently empty the filter
        for (name in ApiFilter.HIDDEN_FIELDS.keys) {
            assertTrue("$name missing from TlCtorIds.allNames", name in TlCtorIds.allNames)
        }
    }

    @Test
    fun hiddenFieldsMatchTheDocumentedSetExactly() {
        // duplicated on purpose: the other test only iterates whatever the map holds, so dropping
        // an entry would widen the api and still pass. list per common.d.ts "account-takeover
        // surfaces are filtered"
        assertEquals(
            mapOf(
                "config" to setOf("autologin_token"),
                "updateServiceNotification" to setOf("message", "media", "entities"),
            ),
            ApiFilter.HIDDEN_FIELDS,
        )
    }
}
