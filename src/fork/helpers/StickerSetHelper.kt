package desu.inugram.helpers

import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory

object StickerSetHelper {
    @JvmStatic
    fun refreshStickerSet(
        accountId: Int,
        container: FrameLayout,
        resourcesProvider: Theme.ResourcesProvider?,
        stickerSet: TLRPC.TL_messages_stickerSet?,
        onLoaded: Utilities.Callback<TLRPC.TL_messages_stickerSet>,
    ) {
        val set = stickerSet?.set ?: return
        val req = TLRPC.TL_messages_getStickerSet()
        req.stickerset = MediaDataController.getInputStickerSet(set)
        ConnectionsManager.getInstance(accountId).sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                val newSet = response as? TLRPC.TL_messages_stickerSet
                if (newSet == null) {
                    BulletinFactory.of(container, resourcesProvider)
                        .createErrorBulletin(LocaleController.getString(R.string.InuStickerSetRefreshFailed))
                        .show()
                    return@runOnUIThread
                }
                MediaDataController.getInstance(accountId).putStickerSet(newSet, true)
                onLoaded.run(newSet)
                BulletinFactory.of(container, resourcesProvider)
                    .createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuStickerSetRefreshed),
                    )
                    .show()
            }
        }
    }
}
