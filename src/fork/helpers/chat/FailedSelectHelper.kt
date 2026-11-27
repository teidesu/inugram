package desu.inugram.helpers.chat

import android.util.SparseArray
import org.telegram.messenger.MessageObject

object FailedSelectHelper {
    // a failed message keeps its local negative id, which the server rejects as a reply target
    @JvmStatic
    fun hasSendError(selected: Array<SparseArray<MessageObject>>): Boolean =
        selected.any { ids -> (0 until ids.size()).any { ids.valueAt(it).isSendError } }
}
