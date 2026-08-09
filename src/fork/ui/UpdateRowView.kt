package desu.inugram.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MediaActionDrawable
import org.telegram.ui.Components.RadialProgress2

class UpdateRowView(context: Context) : FrameLayout(context) {

    private val iconProgress: RadialProgress2
    private val textView: AnimatedTextView
    private val sizeText: AnimatedTextView.AnimatedTextDrawable

    init {
        setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton))
        foreground = Theme.getSelectorDrawable(0x40ffffff, false)

        val tv = object : AnimatedTextView(context, true, true, true) {
            override fun onDraw(canvas: Canvas) {
                sizeText.setBounds(0, 0, measuredWidth - dp(20f), measuredHeight)
                sizeText.draw(canvas)
                canvas.save()
                canvas.translate(dp(15f).toFloat(), 0f)
                super.onDraw(canvas)
                canvas.translate(
                    (measuredWidth - width()) / 2f - dp(30f),
                    dp(11f).toFloat(),
                )
                iconProgress.draw(canvas)
                canvas.restore()
            }

            override fun verifyDrawable(who: Drawable): Boolean {
                return super.verifyDrawable(who) || who === sizeText
            }
        }
        tv.setTextSize(dp(15f).toFloat())
        tv.setTypeface(AndroidUtilities.bold())
        tv.setTextColor(0xffffffff.toInt())
        tv.setGravity(Gravity.CENTER)
        addView(tv, LayoutHelper.createFrameMatchParent())
        tv.setText(LocaleController.getString(R.string.AppUpdateBeta), false)
        textView = tv

        iconProgress = RadialProgress2(tv).apply {
            setColors(
                0xffffffff.toInt(), 0xffffffff.toInt(),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButton),
            )
            setProgressRect(0, 0, dp(22f), dp(22f))
            setCircleRadius(dp(11f))
            setAsMini()
        }

        sizeText = AnimatedTextView.AnimatedTextDrawable(true, true, true).apply {
            setCallback(tv)
            setTextSize(dp(14f).toFloat())
            setTypeface(AndroidUtilities.bold())
            setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            setTextColor(0xccffffff.toInt())
        }

        setOnClickListener { handleClick() }
        setOnLongClickListener {
            val pending = SharedConfig.pendingAppUpdate ?: return@setOnLongClickListener false
            ApplicationLoader.applicationLoaderInstance
                ?.showUpdateAppPopup(getContext(), pending, UserConfig.selectedAccount)
            true
        }
    }

    private fun handleClick() {
        if (!SharedConfig.isAppUpdateAvailable()) return
        val account = UserConfig.selectedAccount
        when (iconProgress.icon) {
            MediaActionDrawable.ICON_DOWNLOAD -> {
                UpdateHelper.startDownload(account)
                refresh(true)
            }
            MediaActionDrawable.ICON_CANCEL -> {
                UpdateHelper.cancelDownload(account)
                refresh(true)
            }
            else -> {
                val activity = (context as? Activity) ?: return
                ApplicationLoader.applicationLoaderInstance
                    ?.openApkInstall(activity, SharedConfig.pendingAppUpdate.document)
            }
        }
    }

    fun refresh(animated: Boolean) {
        if (!SharedConfig.isAppUpdateAvailable()) return
        val account = UserConfig.selectedAccount
        val doc = SharedConfig.pendingAppUpdate.document
        val fileName = FileLoader.getAttachFileName(doc)
        val path = FileLoader.getInstance(account).getPathToAttach(doc, true)
        val showSize: Boolean
        if (path != null && path.exists()) {
            iconProgress.setIcon(MediaActionDrawable.ICON_UPDATE, true, animated)
            textView.setText(LocaleController.getString(R.string.AppUpdateNow), animated)
            showSize = false
        } else if (UpdateHelper.isPendingStart || FileLoader.getInstance(account).isLoadingFile(fileName)) {
            iconProgress.setIcon(MediaActionDrawable.ICON_CANCEL, true, animated)
            iconProgress.setProgress(0f, false)
            val p = ImageLoader.getInstance().getFileProgress(fileName) ?: 0f
            textView.setText(
                LocaleController.formatString(R.string.AppUpdateDownloading, (p * 100).toInt()),
                animated,
            )
            showSize = false
        } else {
            iconProgress.setIcon(MediaActionDrawable.ICON_DOWNLOAD, true, animated)
            textView.setText(LocaleController.getString(R.string.AppUpdate), animated)
            showSize = true
        }
        sizeText.setText(
            if (showSize) AndroidUtilities.formatFileSize(doc.size) else null,
            animated,
        )
    }

    fun applyProgress(args: Array<out Any?>?) {
        if (args == null) return
        if (!SharedConfig.isAppUpdateAvailable()) return
        val location = args.getOrNull(0) as? String ?: return
        val fileName = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document)
        if (fileName != null && fileName == location) {
            val loadedSize = (args.getOrNull(1) as? Long) ?: return
            val totalSize = (args.getOrNull(2) as? Long) ?: return
            val loadProgress = loadedSize / totalSize.toFloat()
            iconProgress.setProgress(loadProgress, true)
            textView.setText(
                LocaleController.formatString(R.string.AppUpdateDownloading, (loadProgress * 100).toInt()),
                true,
            )
        }
    }
}
