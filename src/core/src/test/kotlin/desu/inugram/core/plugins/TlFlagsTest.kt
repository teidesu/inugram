package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the real classes live in the android module, which isn't on this module's classpath, so these
 * stand in for them - what matters is the `static int constructor`, which is how [TlFlags] keys the
 * generated table.
 */
class TlFlagsTest {
    // messageMediaPhoto#e216eb63 flags:# spoiler:flags.3?true photo:flags.0?Photo
    // ttl_seconds:flags.2?int live_photo:flags.4?true
    class MessageMediaPhoto {
        companion object {
            @JvmField
            val constructor: Int = 0xe216eb63.toInt()
        }
    }

    // user#b1b8cc83, whose `bot_can_edit` sits on the second flag word
    class User {
        companion object {
            @JvmField
            val constructor: Int = 0xB1B8CC83.toInt()
        }
    }

    class NotATlClass

    @Test
    fun readsGatesOffTheGeneratedTable() {
        val cls = MessageMediaPhoto::class.java
        assertEquals(TlFlags.Gate(0, 0), TlFlags.gateOf(cls, "photo"))
        assertEquals(TlFlags.Gate(0, 2), TlFlags.gateOf(cls, "ttl_seconds"))
        assertEquals(TlFlags.Gate(0, 3), TlFlags.gateOf(cls, "spoiler"))
    }

    @Test
    fun usesTheSecondFlagWordWhereStockDoes() {
        val gate = TlFlags.gateOf(User::class.java, "bot_can_edit")
        assertEquals(1, gate?.word)
        assertEquals("flags2", TlFlags.wordName(1))
    }

    @Test
    fun ungatedAndUnknownClassesHaveNoGates() {
        // `id` is not optional on user
        assertNull(TlFlags.gateOf(User::class.java, "id"))
        assertNull(TlFlags.gateOf(NotATlClass::class.java, "whatever"))
        assertTrue(TlFlags.wordsOf(NotATlClass::class.java).isEmpty())
    }

    @Test
    fun flagWordsAreOnlyHiddenOnClassesThatHaveThem() {
        assertTrue(TlFlags.isFlagWord(User::class.java, "flags"))
        assertTrue(TlFlags.isFlagWord(User::class.java, "flags2"))
        assertFalse(TlFlags.isFlagWord(User::class.java, "id"))
        assertFalse(TlFlags.isFlagWord(NotATlClass::class.java, "flags"))
    }

    @Test
    fun zeroAndEmptyCountAsAbsent() {
        assertFalse(TlFlags.isPresent(null))
        assertFalse(TlFlags.isPresent(0))
        assertFalse(TlFlags.isPresent(0L))
        assertFalse(TlFlags.isPresent(false))
        assertFalse(TlFlags.isPresent(""))
        assertFalse(TlFlags.isPresent(ByteArray(0)))
        assertFalse(TlFlags.isPresent(emptyList<Any>()))

        assertTrue(TlFlags.isPresent(1))
        assertTrue(TlFlags.isPresent(true))
        assertTrue(TlFlags.isPresent("x"))
        assertTrue(TlFlags.isPresent(listOf(1)))
        assertTrue(TlFlags.isPresent(Any()))
    }

    @Test
    fun buildsTheWordFromPresentFieldsOnly() {
        val cls = MessageMediaPhoto::class.java
        val present = setOf("photo", "spoiler")
        assertEquals(
            (1 shl 0) or (1 shl 3),
            TlFlags.computeWord(cls, 0) { it in present },
        )
        assertEquals(0, TlFlags.computeWord(cls, 0) { false })
    }

    @Test
    fun sharedBitIsSetWhenAnyOfItsFieldsIs() {
        // codeSettings#ad253d78 puts token:flags.8?string and app_sandbox:flags.8?Bool on one bit
        val cls = CodeSettings::class.java
        val gate = TlFlags.gateOf(cls, "token")
        assertEquals(TlFlags.gateOf(cls, "app_sandbox"), gate)
        assertTrue(TlFlags.isBitPresent(cls, gate!!) { it == "app_sandbox" })
        assertFalse(TlFlags.isBitPresent(cls, gate) { false })
    }

    class CodeSettings {
        companion object {
            @JvmField
            val constructor: Int = 0xad253d78.toInt()
        }
    }
}
