package desu.inugram

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MediaActionDrawable
import org.telegram.ui.Components.RadialProgress2
import org.telegram.ui.IUpdateLayout

class UpdateLayout(
    private val activity: Activity,
    private val sideMenuContainer: ViewGroup?,
) : IUpdateLayout(activity, sideMenuContainer) {

    private var updateLayout: FrameLayout? = null
    private var updateLayoutIcon: RadialProgress2? = null
    private var updateTextView: AnimatedTextView? = null
    private var updateSizeTextView: AnimatedTextView.AnimatedTextDrawable? = null

    override fun updateFileProgress(args: Array<out Any?>?) {
        val tv = updateTextView ?: return
        if (args == null) return
        if (!SharedConfig.isAppUpdateAvailable()) return
        val location = args.getOrNull(0) as? String ?: return
        val fileName = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document)
        if (fileName != null && fileName == location) {
            val loadedSize = (args.getOrNull(1) as? Long) ?: return
            val totalSize = (args.getOrNull(2) as? Long) ?: return
            val loadProgress = loadedSize / totalSize.toFloat()
            updateLayoutIcon?.setProgress(loadProgress, true)
            tv.setText(LocaleController.formatString(R.string.AppUpdateDownloading, (loadProgress * 100).toInt()))
        }
    }

    override fun createUpdateUI(currentAccount: Int) {
        val container = sideMenuContainer ?: return
        if (updateLayout != null) return

        val layout = FrameLayout(activity).also { updateLayout = it }
        layout.visibility = View.GONE
        layout.translationY = dp(44f).toFloat()
        layout.background = Theme.getSelectorDrawable(0x40ffffff, false)
        container.addView(
            layout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT or Gravity.BOTTOM),
        )
        layout.setOnClickListener {
            if (!SharedConfig.isAppUpdateAvailable()) return@setOnClickListener
            when (updateLayoutIcon?.icon) {
                MediaActionDrawable.ICON_DOWNLOAD -> {
                    FileLoader.getInstance(currentAccount).loadFile(
                        SharedConfig.pendingAppUpdate.document, "update", FileLoader.PRIORITY_NORMAL, 1,
                    )
                    updateAppUpdateViews(currentAccount, true)
                }
                MediaActionDrawable.ICON_CANCEL -> {
                    FileLoader.getInstance(currentAccount).cancelLoadFile(SharedConfig.pendingAppUpdate.document)
                    updateAppUpdateViews(currentAccount, true)
                }
                else -> {
                    ApplicationLoader.applicationLoaderInstance?.openApkInstall(
                        activity, SharedConfig.pendingAppUpdate.document,
                    )
                }
            }
        }

        val tv = object : AnimatedTextView(activity, true, true, true) {
            override fun onDraw(canvas: Canvas) {
                updateSizeTextView?.setBounds(0, 0, measuredWidth - dp(20f), measuredHeight)
                updateSizeTextView?.draw(canvas)
                canvas.save()
                canvas.translate(dp(15f).toFloat(), 0f)
                super.onDraw(canvas)
                canvas.translate(
                    (measuredWidth - width()) / 2f - dp(30f),
                    dp(11f).toFloat(),
                )
                updateLayoutIcon?.draw(canvas)
                canvas.restore()
            }

            override fun verifyDrawable(who: Drawable): Boolean {
                return super.verifyDrawable(who) || who === updateSizeTextView
            }
        }
        tv.setTextSize(dp(15f).toFloat())
        tv.setTypeface(AndroidUtilities.bold())
        tv.setTextColor(0xffffffff.toInt())
        tv.setGravity(Gravity.CENTER)
        layout.addView(tv, LayoutHelper.createFrameMatchParent())
        tv.setText(LocaleController.getString(R.string.AppUpdateBeta), false)
        updateTextView = tv

        val icon = RadialProgress2(tv).apply {
            setColors(
                0xffffffff.toInt(), 0xffffffff.toInt(),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButton),
            )
            setProgressRect(0, 0, dp(22f), dp(22f))
            setCircleRadius(dp(11f))
            setAsMini()
        }
        updateLayoutIcon = icon

        updateSizeTextView = AnimatedTextView.AnimatedTextDrawable(true, true, true).apply {
            setCallback(tv)
            setTextSize(dp(14f).toFloat())
            setTypeface(AndroidUtilities.bold())
            setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            setTextColor(0xccffffff.toInt())
        }
    }

    override fun updateAppUpdateViews(currentAccount: Int, animated: Boolean) {
        if (sideMenuContainer == null) return
        if (SharedConfig.isAppUpdateAvailable()) {
            createUpdateUI(currentAccount)
            val layout = updateLayout ?: return
            val icon = updateLayoutIcon ?: return
            val doc = SharedConfig.pendingAppUpdate.document
            val fileName = FileLoader.getAttachFileName(doc)
            val path = FileLoader.getInstance(currentAccount).getPathToAttach(doc, true)
            val showSize: Boolean
            if (path != null && path.exists()) {
                icon.setIcon(MediaActionDrawable.ICON_UPDATE, true, animated)
                setUpdateText(LocaleController.getString(R.string.AppUpdateNow), animated)
                showSize = false
            } else {
                if (FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                    icon.setIcon(MediaActionDrawable.ICON_CANCEL, true, animated)
                    icon.setProgress(0f, false)
                    val p = ImageLoader.getInstance().getFileProgress(fileName) ?: 0f
                    setUpdateText(
                        LocaleController.formatString(R.string.AppUpdateDownloading, (p * 100).toInt()),
                        animated,
                    )
                    showSize = false
                } else {
                    icon.setIcon(MediaActionDrawable.ICON_DOWNLOAD, true, animated)
                    setUpdateText(LocaleController.getString(R.string.AppUpdate), animated)
                    showSize = true
                }
            }
            updateSizeTextView?.setText(
                if (showSize) AndroidUtilities.formatFileSize(doc.size) else null,
                animated,
            )
            if (layout.tag != null) return
            layout.animate().cancel()
            layout.visibility = View.VISIBLE
            layout.tag = 1
            if (animated) {
                layout.animate().translationY(0f)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(null)
                    .setDuration(180).start()
            } else {
                layout.translationY = 0f
            }
        } else {
            val layout = updateLayout ?: return
            if (layout.tag == null) return
            layout.tag = null
            layout.animate().cancel()
            if (animated) {
                layout.animate().translationY(dp(44f).toFloat())
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (layout.tag == null) layout.visibility = View.GONE
                        }
                    }).setDuration(180).start()
            } else {
                layout.translationY = dp(44f).toFloat()
                layout.visibility = View.GONE
            }
        }
    }

    private fun setUpdateText(text: String, animated: Boolean) {
        updateTextView?.setText(text, animated)
    }
}
