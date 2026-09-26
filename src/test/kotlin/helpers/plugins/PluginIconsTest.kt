package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.ui.PluginIcons
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.ui.Components.RLottieImageView

class PluginIconsTest {
    @Before
    fun set_up() = resetBridge()

    @Test
    fun animated_ui_icon_specs_resolve_to_drawables() {
        val plugin = startPlugin("icons")
        val context = deviceContext()

        onUi {
            assertTrue(PluginIcons.setIcon(RLottieImageView(context), "a0done", plugin.js))
            assertTrue(PluginIcons.setIcon(RLottieImageView(context), "e15361751237382052539", plugin.js))
            assertTrue(PluginIcons.setIcon(RLottieImageView(context), "t1i0\nteidesu_favs", plugin.js))
        }
    }
}
