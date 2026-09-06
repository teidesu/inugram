package desu.inugram.helpers.plugins

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class PluginLogBudgetTest {
    @Test fun concurrent_callers_share_one_log_budget() {
        val type = Class.forName("desu.inugram.helpers.plugins.PluginManager\$LogBudget")
        val budget = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val charge = type.getDeclaredMethod("charge", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val passed = AtomicInteger()
        val last = AtomicInteger()
        val dropped = AtomicInteger()
        val workers = List(8) {
            Thread {
                repeat(1000) {
                    when (charge.invoke(budget, 1000L).toString()) {
                        "PASS" -> passed.incrementAndGet()
                        "LAST" -> last.incrementAndGet()
                        "DROP" -> dropped.incrementAndGet()
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach {
            it.join(5000)
            assertFalse(it.isAlive)
        }
        assertEquals(199, passed.get())
        assertEquals(1, last.get())
        assertEquals(7800, dropped.get())
        assertEquals("PASS", charge.invoke(budget, 10_000L).toString())
    }
}
