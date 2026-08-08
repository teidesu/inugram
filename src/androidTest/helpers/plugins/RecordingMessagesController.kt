package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.tg.PluginUpdates
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

/**
 * The observation point for the `interceptUpdate` chain: what the app was finally handed, in order.
 *
 * It stands in for the app's controller **only as `batch.controller`**, which is the one thing
 * `PluginRpc` uses it for - the hand-back. Everything else the update path reads (peers, entities)
 * still goes through the real `MessagesController.getInstance`, so nothing here answers for stock.
 * The real `processUpdates` cannot be that observation point: it removes entries from
 * `updates.updates` in place, which is the very thing these tests assert about.
 *
 * Built by [TestApp.allocate], **without running any constructor**: the real one registers observers
 * and reads preferences. Only the member below may be called.
 */
class RecordingMessagesController : MessagesController {
    private constructor() : super(0)

    // [TestApp.allocate] runs no constructor, so no field initializer here runs either
    private var processedList: ArrayList<TLRPC.Updates>? = null

    val processed: ArrayList<TLRPC.Updates>
        get() = processedList ?: ArrayList<TLRPC.Updates>().also { processedList = it }

    /** the app's own hand-back throwing, which the account's whole update stream is parked behind */
    var failHandBack = false

    /** the stock hook, verbatim: a chain hands its batch back through here */
    override fun processUpdates(updates: TLRPC.Updates, fromQueue: Boolean) {
        if (PluginUpdates.onUpdates(this, updates, currentAccount, fromQueue)) return
        if (failHandBack) throw IllegalStateException("processUpdates blew up")
        processed.add(updates)
    }
}
