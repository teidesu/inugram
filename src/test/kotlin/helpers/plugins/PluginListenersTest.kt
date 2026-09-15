package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginAccounts
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * a composed listener is forwarders the Kotlin compiler writes into the composing class, and its
 * incremental build does not rewrite them when only the delegated interface changes: the stale class
 * throws `AbstractMethodError` the first time rust calls the member it lacks
 */
class PluginListenersTest {
    @Before
    fun setUp() = resetBridge()

    private fun assertEveryMemberImplemented(listener: Any, vararg interfaces: Class<*>) {
        val missing = interfaces.flatMap { it.methods.asList() }.filter { member ->
            Modifier.isAbstract(listener.javaClass.getMethod(member.name, *member.parameterTypes).modifiers)
        }.map { member -> "${member.declaringClass.simpleName}.${member.name}(${member.parameterTypes.joinToString { it.simpleName }})" }
        assertEquals(emptyList(), missing.distinct())
    }

    @Test
    fun the_account_listener_implements_every_reads_and_writes_member() {
        val session = startPlugin("accounts").session!!
        assertEveryMemberImplemented(
            PluginAccounts.listenerFor(session),
            AccountListener::class.java,
            ReadsListener::class.java,
            WritesListener::class.java,
        )
    }

    @Test
    fun the_bridge_implements_every_listener_member() {
        assertEveryMemberImplemented(startPlugin("bridge").js.listener!!, PluginListener::class.java)
    }
}
