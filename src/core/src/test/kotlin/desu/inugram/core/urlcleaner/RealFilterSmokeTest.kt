package desu.inugram.core.urlcleaner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test against the actual AdGuard URL Tracking filter (list 17).
 * Skipped silently if the file isn't present locally.
 */
class RealFilterSmokeTest {
    private fun loadCleaner(): UrlCleaner? {
        val candidates = listOfNotNull(
            System.getProperty("adguard.filter17"),
            // src/core symlinked into worktree/InuCore — both resolve back to the fork source dir
            "../../src/res/assets/adguard_url_tracking.txt",
            "src/res/assets/adguard_url_tracking.txt",
        )
        val f = candidates.map(::File).firstOrNull { it.exists() } ?: return null
        return UrlCleaner.fromAdGuardFilter(f.readText())
    }

    @Test fun stripsUtmGlobally() {
        val c = loadCleaner() ?: return
        val cleaned = c.clean("https://example.com/page?utm_source=newsletter&utm_medium=email&id=42")
        assertEquals("https://example.com/page?id=42", cleaned)
    }

    @Test fun stripsFbclid() {
        val c = loadCleaner() ?: return
        val cleaned = c.clean("https://news.example.com/article?fbclid=abc123&page=2")
        assertEquals("https://news.example.com/article?page=2", cleaned)
    }

    @Test fun stripsGoogleSrsltid() {
        val c = loadCleaner() ?: return
        // srsltid is in the globalRules list
        val cleaned = c.clean("https://shop.example.com/item?srsltid=AfmBOoq&color=red")
        assertEquals("https://shop.example.com/item?color=red", cleaned)
    }

    @Test fun stripsMailchimpTracking() {
        val c = loadCleaner() ?: return
        // mc_eid / mc_cid are global
        val cleaned = c.clean("https://example.com/article?mc_cid=abc&mc_eid=xyz&id=1")
        assertEquals("https://example.com/article?id=1", cleaned)
    }

    @Test fun realAllowlistGlavnoeLife() {
        val c = loadCleaner() ?: return
        // list 17 has both globalRules stripping utm_* and @@||glavnoe.life^$removeparam=utm_*
        assertEquals(
            "https://glavnoe.life/news?utm_campaign=keep",
            c.clean("https://glavnoe.life/news?utm_campaign=keep&fbclid=drop"),
        )
    }

    @Test fun realAllowlistLifehacker() {
        val c = loadCleaner() ?: return
        assertEquals(
            "https://lifehacker.ru/post?erid=keep",
            c.clean("https://lifehacker.ru/post?erid=keep&utm_source=drop"),
        )
    }

    @Test fun leavesCleanUrlAlone() {
        val c = loadCleaner() ?: return
        val url = "https://example.com/?page=2&sort=date"
        assertEquals(url, c.clean(url))
    }
}
