package desu.inugram.helpers

import android.os.SystemClock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BulletinFactory

object ServerErrorHelper {
    private const val BULLETIN_INTERVAL_MS = 5L * 60 * 1000

    private var lastBulletinTime = 0L

    @JvmStatic
    fun onSendServerError(account: Int, code: Int, message: String) {
        AndroidUtilities.runOnUIThread {
            if (account != UserConfig.selectedAccount || ApplicationLoader.mainInterfacePaused) return@runOnUIThread
            val now = SystemClock.elapsedRealtime()
            if (lastBulletinTime != 0L && now - lastBulletinTime < BULLETIN_INTERVAL_MS) return@runOnUIThread
            lastBulletinTime = now
            BulletinFactory.global().createSimpleBulletin(
                R.raw.error,
                LocaleController.getString(R.string.InuSendServerError),
                "$code $message",
            ).show()
        }
    }
}
