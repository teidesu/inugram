package desu.inugram.helpers

import android.text.TextUtils
import android.util.Base64
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.OutputSerializedData
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.bots.BotWebViewAttachedSheet
import org.telegram.ui.bots.BotWebViewSheet
import org.telegram.ui.bots.WebViewRequestProps


object WebAppHelper {
    // using a bot seems to be the the easiest way to force a webview
    const val HELPER_BOT_USERNAME = "inu_helper_bot"
    const val HELPER_BOT_ID = 8589894659L
    const val TYPE_TLV = 1;

    @JvmStatic
    fun getInternalBotName(props: WebViewRequestProps): String? {
        return when (props.inu_internalType) {
            TYPE_TLV -> LocaleController.getString(R.string.InuShowJson);
            else -> null
        }
    }

    @JvmStatic
    fun openTlViewer(fragment: BaseFragment, arr: List<TLObject>) {
        openTlViewer(fragment, object : TLObject() {
            override fun serializeToStream(stream: OutputSerializedData) {
                stream.writeInt32(0x1cb5c415)
                stream.writeInt32(arr.size)
                for (obj in arr) {
                    obj.serializeToStream(stream)
                }
            }
        })
    }

    // serialized message without attach path. stack-inspection monkeypatch —
    // we can't actually override Message.writeAttachPath without a hooking lib,
    // so we no-op the writes it would issue when we detect it in the stack.
    class CleanSerializedData(size: Int) : SerializedData(size) {
        private fun inWriteAttachPath(): Boolean {
            val stack = Thread.currentThread().stackTrace
            for (i in 2 until stack.size.coerceAtMost(8)) {
                if (stack[i].methodName == "writeAttachPath") return true
            }
            return false
        }

        override fun writeString(s: String) {
            if (inWriteAttachPath()) return
            super.writeString(s)
        }

        override fun writeInt32(x: Int) {
            if (inWriteAttachPath()) return
            super.writeInt32(x)
        }
    }

    @JvmStatic
    fun openTlViewer(fragment: BaseFragment, obj: TLObject) {
        var serialized = "";
        try {
            val data = CleanSerializedData(obj.getObjectSize());
            obj.serializeToStream(data);
            serialized =
                Base64.encodeToString(data.toByteArray(), Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
            data.cleanup();
        } catch (e: Exception) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(serialized)) {
            return;
        }
        val url = "https://schema.jppgr.am/embed#data=" + serialized + "&layer=" + TLRPC.LAYER + "&hide-toolbar=1";
        openInternalWebApp(fragment, url, TYPE_TLV);
    }

    private fun openInternalWebApp(fragment: BaseFragment, url: String, type: Int, allowResolve: Boolean = true) {
        val bot = fragment.messagesController.getUser(HELPER_BOT_ID)
        if (bot == null) {
            if (allowResolve) {
                fragment.messagesController.userNameResolver.resolve(HELPER_BOT_USERNAME) {
                    openInternalWebApp(fragment, url, type, false)
                }
            }
            return
        }
        val props = WebViewRequestProps.of(
            fragment.getCurrentAccount(),
            HELPER_BOT_ID,
            HELPER_BOT_ID,
            null,
            url,
            BotWebViewAttachedSheet.TYPE_WEB_VIEW_BUTTON,
            0, 0, false, null, false, null, null, 0, false, false
        )
        props.inu_internalType = type;
        val context = fragment.getParentActivity() ?: return
        if (context is LaunchActivity) {
            if (context.getBottomSheetTabs() != null && context.getBottomSheetTabs().tryReopenTab(props) != null) {
                return
            }
        }

        val webViewSheet = BotWebViewSheet(context, fragment.getResourceProvider())
        webViewSheet.setDefaultFullsize(false)
        webViewSheet.setNeedsContext(false)
        webViewSheet.setParentActivity(context)
        webViewSheet.requestWebView(null, props)
        webViewSheet.show()
    }
}