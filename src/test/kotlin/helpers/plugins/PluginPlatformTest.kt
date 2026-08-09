package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginPlatform
import java.util.Date
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController

class PluginPlatformTest {
    @Before
    fun setUp() = resetBridge()

    @Test
    fun formatting_uses_the_stock_localized_formatters() {
        val platform = PluginPlatform.listenerFor()
        val unix = 1_715_531_070L

        assertEquals(LocaleController.formatDate(unix), platform.format(0, unix))
        assertEquals(LocaleController.getInstance().formatterDay.format(Date(unix * 1000)), platform.format(1, unix))
        assertEquals(LocaleController.formatDateTime(unix, true), platform.format(2, unix))
        assertEquals(LocaleController.formatDateChat(unix, true), platform.format(3, unix))
        assertEquals(LocaleController.formatNumber(1_234_567, ' '), platform.format(4, 1_234_567))
        assertEquals(LocaleController.formatNumberWithMillion(1_234_567, ' '), platform.format(5, 1_234_567))
        assertEquals(AndroidUtilities.formatFileSize(4_404_019), platform.format(6, 4_404_019))
        assertEquals(LocaleController.formatShortDuration(3_764), platform.format(7, 3_764))
    }
}
