package desu.inugram.core.plugins

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootCohortTest {
    private fun cohort(vararg grants: String) =
        BootCohort.bootsEarly(PluginPermissions.parse(grants.toList()))

    @Test
    fun `a plugin the headless paths dispatch to boots early`() {
        assertTrue(cohort("interceptRpc(messages.sendMessage)"))
        // the scoped form is the one plugins are told to use, and cold start is exactly when a
        // update interceptor has to already be there
        assertTrue(cohort("interceptUpdate(updateNewMessage)"))
        assertTrue(cohort("interceptSendMessage"))
        assertTrue(cohort("onUpdate(updateNewMessage)"))
    }

    @Test
    fun `a demuxed event boots early, since it is an onUpdate grant`() {
        assertTrue(cohort("onUpdate(new_message)"))
        assertTrue(cohort("onUpdate(edit_message)"))
        assertTrue(cohort("onUpdate(delete_message)"))
    }

    @Test
    fun `a plugin that only needs a screen does not`() {
        assertFalse(cohort("ui", "registerGlobalAction", "registerSettings", "clipboard", "openUrl"))
        assertFalse(cohort("fs(64kb)", "fetch(example.com)"))
        assertFalse(cohort("account.read(messages)", "account.write(send)"))
        assertFalse(cohort())
    }

    @Test
    fun `invokeRpc alone does not, since nothing dispatches into it`() {
        assertFalse(cohort("invokeRpc(users.getUsers)"))
    }

    @Test
    fun `one headless grant among many is enough`() {
        assertTrue(cohort("ui", "onUpdate(updateUserTyping)", "clipboard"))
    }

    @Test
    fun `an unscoped headless grant counts`() {
        assertTrue(cohort("onUpdate"))
        assertTrue(cohort("interceptRpc"))
    }

    @Test
    fun `a malformed grant token does not smuggle a plugin into the cohort`() {
        // parseGrant is fail-closed, so these produce no grant at all rather than an unscoped one
        assertFalse(cohort("onUpdate("))
        assertFalse(cohort("onUpdate()"))
        assertFalse(cohort("(updateNewMessage)"))
    }
}
