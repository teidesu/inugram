package desu.inugram.helpers

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Adapters.MentionsAdapter

object SearchFromHelper {
    private const val DEBOUNCE_MS = 350L
    private const val LIMIT = 20

    private var pendingRunnable: Runnable? = null
    private var inflightReqId = 0
    private var inflightAccount = -1
    private var seq = 0

    @JvmStatic
    fun queryAndMerge(adapter: MentionsAdapter, currentAccount: Int, query: String?) {
        cancel()
        if (query.isNullOrEmpty()) return
        val mySeq = ++seq
        val runnable = Runnable {
            if (mySeq != seq) return@Runnable
            pendingRunnable = null
            val req = TLRPC.TL_contacts_search().apply {
                q = query
                limit = LIMIT
            }
            inflightAccount = currentAccount
            inflightReqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req) { response, error ->
                AndroidUtilities.runOnUIThread {
                    if (mySeq != seq) return@runOnUIThread
                    inflightReqId = 0
                    if (error != null || response !is TLRPC.TL_contacts_found) return@runOnUIThread
                    val mc = MessagesController.getInstance(currentAccount)
                    mc.putUsers(response.users, false)
                    mc.putChats(response.chats, false)
                    val users = ArrayList<TLRPC.User>(response.my_results.size + response.results.size)
                    for (peer in response.my_results) {
                        if (peer.user_id == 0L) continue
                        mc.getUser(peer.user_id)?.let { users.add(it) }
                    }
                    for (peer in response.results) {
                        if (peer.user_id == 0L) continue
                        mc.getUser(peer.user_id)?.let { users.add(it) }
                    }
                    merge(adapter, users)
                }
            }
        }
        pendingRunnable = runnable
        AndroidUtilities.runOnUIThread(runnable, DEBOUNCE_MS)
    }

    @JvmStatic
    fun cancel() {
        seq++
        pendingRunnable?.let {
            AndroidUtilities.cancelRunOnUIThread(it)
            pendingRunnable = null
        }
        if (inflightReqId != 0 && inflightAccount >= 0) {
            ConnectionsManager.getInstance(inflightAccount).cancelRequest(inflightReqId, false)
            inflightReqId = 0
        }
    }

    private fun merge(adapter: MentionsAdapter, users: List<TLRPC.User>) {
        val list = adapter.searchResultUsernames ?: return
        val delegate = adapter.delegate ?: return
        val have = HashSet<Long>()
        for (o in list) {
            if (o is TLRPC.User) have.add(o.id)
        }
        var added = false
        for (u in users) {
            if (have.add(u.id)) {
                list.add(u)
                added = true
            }
        }
        if (added) {
            adapter.notifyDataSetChanged()
            delegate.needChangePanelVisibility(list.isNotEmpty())
        }
    }
}
