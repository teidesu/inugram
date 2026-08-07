package desu.inugram.core.plugins

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconSpecTest {
    @Test
    fun `a bare identifier is a name and a qualified reference is not`() {
        assertTrue(IconSpec.isResourceName("msg_settings"))
        assertTrue(IconSpec.isResourceName("_x9"))

        for (bad in listOf(
            "",
            "9lives",
            "android:drawable/ic_delete",
            "drawable/ic_delete",
            "msg settings",
            "msg-settings",
            "msg.settings",
            "msg/settings",
            "x".repeat(IconSpec.MAX_RESOURCE_NAME + 1),
        )) {
            assertFalse(IconSpec.isResourceName(bad), bad)
        }
    }

    @Test
    fun `a doctype is refused wherever it sits and a comment is not`() {
        assertTrue(IconSpec.isSvgSource("<svg><!-- hi --><rect/></svg>"))

        assertFalse(IconSpec.isSvgSource("<!DOCTYPE svg SYSTEM \"file:///etc/passwd\"><svg/>"))
        assertFalse(IconSpec.isSvgSource("<svg><!ENTITY x SYSTEM \"file:///etc/passwd\"></svg>"))
        assertFalse(IconSpec.isSvgSource("<svg><!-- ok --><!DOCTYPE x></svg>"))
        assertFalse(IconSpec.isSvgSource("<rect/>"))
        assertFalse(IconSpec.isSvgSource("<svg>" + "x".repeat(IconSpec.SVG_LIMIT_BYTES) + "</svg>"))
    }
}
