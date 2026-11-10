package desu.inugram

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import desu.inugram.helpers.update.UpdateHelper
import desu.inugram.ui.ChangelogSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme

class UpdateAppAlertDialog(
    context: Context,
    appUpdate: TLRPC.TL_help_appUpdate,
    accountNum: Int,
) : ChangelogSheet(
    context,
    LocaleController.getString(R.string.AppUpdate),
    createRows(context, appUpdate, accountNum),
    LocaleController.getString(R.string.AppUpdateRemindMeLater),
    { UpdateHelper.clearPending() },
    LocaleController.getString(R.string.AppUpdateDownloadNow),
    {
        UpdateHelper.markWhatsNewSeen(appUpdate.version.toIntOrNull() ?: 0)
        UpdateHelper.startDownload(accountNum)
    },
) {
    override fun onOpenAnimationEnd() {
        super.onOpenAnimationEnd()
        UpdateHelper.revealPendingUpdate()
    }

    private companion object {
        fun createRows(context: Context, appUpdate: TLRPC.TL_help_appUpdate, accountNum: Int): List<View> {
            val rows = ArrayList<View>()

            rows.add(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                text = LocaleController.formatString(
                    R.string.AppUpdateVersionAndSize,
                    appUpdate.version,
                    AndroidUtilities.formatFileSize(appUpdate.document.size),
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(23f), 0, dp(23f), dp(5f))
            })

            val changelog = UpdateHelper.getChangelog(appUpdate)
            rows.add(
                if (changelog != null && changelog.blocks.isNotEmpty()) {
                    RichMessageLayout.PreviewView(context, accountNum, null).apply {
                        set(changelog)
                        setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                    }
                } else {
                    TextView(context).apply {
                        setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                        text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty))
                        setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                    }
                },
            )
            return rows
        }
    }
}
