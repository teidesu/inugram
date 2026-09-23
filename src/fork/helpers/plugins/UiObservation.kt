package desu.inugram.helpers.plugins

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter

/**
 * NotificationCenter may only be touched from the ui thread, and holds observers strongly. [AndroidUtilities.runOnUIThread]
 * always posts, so [stop] lands behind [start] even for an observation torn down before its add ran.
 */
internal class UiObservation(
    private val events: IntArray,
    private val centres: () -> List<NotificationCenter>,
    private val delegate: NotificationCenter.NotificationCenterDelegate,
) {
    fun start() = AndroidUtilities.runOnUIThread {
        for (centre in centres()) for (id in events) centre.addObserver(delegate, id)
    }

    fun stop() = AndroidUtilities.runOnUIThread {
        for (centre in centres()) for (id in events) centre.removeObserver(delegate, id)
    }
}
