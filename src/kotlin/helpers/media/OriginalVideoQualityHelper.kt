package desu.inugram.helpers.media

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.PhotoViewer

object OriginalVideoQualityHelper {
    private const val PREF_KEY = "inu_compress_video_original"

    @JvmStatic
    fun loadPref(): Boolean =
        MessagesController.getGlobalMainSettings().getBoolean(PREF_KEY, false)

    @JvmStatic
    fun savePref(checked: Boolean) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_KEY, checked).apply()
    }

    /**
     * Builds the "Original" checkbox row and attaches it right above [anchor]
     * (PhotoViewer's qualityChooseView, which must already be in its parent).
     * The row mirrors the anchor's translationY/visibility every frame, so the
     * stock show/hide animation needs no extra wiring; while active it also dims
     * the anchor and swallows its touches (quality slider becomes inert).
     */
    @JvmStatic
    fun createRow(
        viewer: PhotoViewer,
        anchor: View,
        resourcesProvider: Theme.ResourcesProvider?,
    ) {
        val context = anchor.context
        val checkBox = CheckBox2(context, 18, resourcesProvider).apply {
            setColor(
                Theme.key_dialogRoundCheckBox,
                Theme.key_checkboxDisabled,
                Theme.key_dialogRoundCheckBoxCheck,
            )
            setDrawUnchecked(true)
            isClickable = false
        }
        val label = TextView(context).apply {
            text = LocaleController.getString(R.string.QualityOriginal)
            setTextColor(0xffcdcdcd.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            includeFontPadding = false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x7f000000)
            visibility = View.INVISIBLE
            translationY = anchor.translationY
            contentDescription = label.text
            addView(checkBox, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL))
            addView(
                label,
                LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f,
                ),
            )
            setOnClickListener {
                viewer.inu_originalQualitySelected = !viewer.inu_originalQualitySelected
                viewer.didChangedCompressionLevel(false)
                viewer.requestVideoPreview(1)
            }
        }

        val parent = anchor.parent as ViewGroup
        val anchorLp = anchor.layoutParams as FrameLayout.LayoutParams
        val rowLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            AndroidUtilities.dp(36f),
            Gravity.LEFT or Gravity.BOTTOM,
        )
        rowLp.bottomMargin = anchorLp.bottomMargin + anchorLp.height
        parent.addView(row, rowLp)

        // dimming the anchor via view alpha would also fade its panel background,
        // so the background moves onto a standalone backdrop view behind it
        val backdrop = View(context).apply {
            background = anchor.background
            visibility = anchor.visibility
            translationY = anchor.translationY
        }
        anchor.background = null
        parent.addView(backdrop, parent.indexOfChild(anchor), FrameLayout.LayoutParams(anchorLp))

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            val available = viewer.inu_originalVideoQualityAvailable()
            val checked = viewer.inu_originalQualitySelected
            checkBox.setChecked(checked, row.visibility == View.VISIBLE)
            row.translationY = anchor.translationY
            row.visibility =
                if (anchor.visibility == View.VISIBLE && available) View.VISIBLE else View.INVISIBLE
            backdrop.translationY = anchor.translationY
            backdrop.visibility = anchor.visibility
            anchor.alpha = if (available && checked) 0.4f else 1f
            true
        }
        anchor.setOnTouchListener { _, _ ->
            viewer.inu_originalVideoQualityAvailable() && viewer.inu_originalQualitySelected
        }
        // ViewTreeObserver doesn't survive detach/reattach (PhotoViewer close/reopen),
        // so the listener has to be re-registered on every attach
        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) {
                    v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                }
            }
        })
        if (anchor.isAttachedToWindow) {
            anchor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }
}
