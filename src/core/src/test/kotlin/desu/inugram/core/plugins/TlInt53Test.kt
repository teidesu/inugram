package desu.inugram.core.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** stand-ins for the android module's classes, keyed the way [TlInt53] keys the generated table */
class TlInt53Test {
    // peerUser#59511722 user_id:long
    class PeerUser {
        companion object {
            @JvmField
            val constructor: Int = 0x59511722
        }
    }

    // inputPeerUser#dde8a54c user_id:long access_hash:long
    class InputPeerUser {
        companion object {
            @JvmField
            val constructor: Int = 0x7b8e7de6
        }
    }

    // document#8fd4c4d8 id:long access_hash:long ... size:long
    class Document {
        companion object {
            @JvmField
            val constructor: Int = 0x8fd4c4d8.toInt()
        }
    }

    class NotATlClass

    @Test
    fun marksIdsAndSizesButNotHashes() {
        assertTrue(TlInt53.isInt53(PeerUser::class.java, "user_id"))
        assertTrue(TlInt53.isInt53(InputPeerUser::class.java, "user_id"))
        assertFalse(TlInt53.isInt53(InputPeerUser::class.java, "access_hash"))
        assertTrue(TlInt53.isInt53(Document::class.java, "size"))
        assertFalse(TlInt53.isInt53(Document::class.java, "id"))
    }

    @Test
    fun anUnknownClassHasNone() {
        assertTrue(TlInt53.fieldsOf(NotATlClass::class.java).isEmpty())
    }
}
