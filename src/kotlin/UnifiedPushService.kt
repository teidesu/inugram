package desu.inugram

import desu.inugram.helpers.UnifiedPushHelper
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        UnifiedPushHelper.onNewEndpoint(endpoint.url, this)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        UnifiedPushHelper.onPushReceived()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        UnifiedPushHelper.onRegistrationFailed()
    }

    override fun onUnregistered(instance: String) {
        UnifiedPushHelper.onUnregistered()
    }
}
