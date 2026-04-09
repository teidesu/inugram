package desu.inugram.helpers

import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ArticleViewer

object InstantViewHelper {
    private const val COCOON_SUMMARY_CAPTION = "Cocoon AI Summary"

    @JvmStatic
    fun shouldHideBlock(block: TLRPC.PageBlock?): Boolean {
        if (InuConfig.HIDE_IV_SUMMARY.value && block is TLRPC.TL_pageBlockBlockquote) {
            val caption = ArticleViewer.getPlainText(block.caption) ?: return false
            return caption.toString() == COCOON_SUMMARY_CAPTION
        }

        return false
    }
}
