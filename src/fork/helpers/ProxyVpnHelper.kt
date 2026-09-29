package desu.inugram.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.edit
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager

object ProxyVpnHelper {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var applying = false

    fun init(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reconcile()
            override fun onLost(network: Network) = reconcile()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = reconcile()
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Throwable) {
        }
        reconcile()
    }

    fun reconcile() {
        AndroidUtilities.runOnUIThread { doReconcile() }
    }

    private fun doReconcile() {
        if (applying) return
        if (!InuConfig.AUTO_DISABLE_PROXY_ON_VPN.value) {
            if (InuConfig.PROXY_SUPPRESSED_BY_VPN.value) restoreProxy()
            return
        }
        if (isVpnActive()) {
            if (SharedConfig.isProxyEnabled()) suppressProxy()
        } else if (InuConfig.PROXY_SUPPRESSED_BY_VPN.value) {
            restoreProxy()
        }
    }

    private fun isVpnActive(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun suppressProxy() {
        applying = true
        try {
            MessagesController.getGlobalMainSettings().edit {
                putBoolean("proxy_enabled", false)
                putBoolean("proxy_enabled_calls", false)
            }
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "")
            InuConfig.PROXY_SUPPRESSED_BY_VPN.value = true
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        } finally {
            applying = false
        }
    }

    private fun restoreProxy() {
        applying = true
        try {
            InuConfig.PROXY_SUPPRESSED_BY_VPN.value = false
            val proxy = SharedConfig.currentProxy ?: return
            MessagesController.getGlobalMainSettings().edit {
                putBoolean("proxy_enabled", true)
            }
            ConnectionsManager.setProxySettings(true, proxy.address, proxy.port, proxy.username, proxy.password, proxy.secret)
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        } finally {
            applying = false
        }
    }
}
