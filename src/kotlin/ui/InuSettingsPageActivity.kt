package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.view.View
import androidx.core.graphics.withTranslation
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalFragment
import kotlin.system.exitProcess

abstract class InuSettingsPageActivity : UniversalFragment() {
    override fun isSupportEdgeToEdge(): Boolean = true

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.setSections()
            actionBar.setAdaptiveBackground(listView)
            listView.clipToPadding = false
        }
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val lv = listView ?: return
        lv.setPadding(lv.paddingLeft, lv.paddingTop, lv.paddingRight, bottom)
    }

    protected fun showRestartBulletin() {
        BulletinFactory.of(this)
            .createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuRestartRequired),
                LocaleController.getString(R.string.InuRestartNow)
            ) {
                val activity = parentActivity ?: return@createSimpleBulletin
                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                activity.finishAffinity()
                activity.startActivity(intent)
                exitProcess(0)
            }
            .show()
    }

    protected fun mkTwoLineCheckItem(
        id: Int,
        textRes: Int,
        infoRes: Int,
        checked: Boolean,
        experimental: Boolean = false
    ): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) addExperimentalSpan(rawText) else rawText
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    true
                )
                (view as? NotificationsCheckCell)?.setDrawLine(false)
            }
        }
    }

    protected fun mkSplitCheckItem(
        id: Int,
        textRes: Int,
        infoRes: Int,
        checked: Boolean,
        experimental: Boolean = false
    ): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) addExperimentalSpan(rawText) else rawText
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    true
                )
                (view as? NotificationsCheckCell)?.setDrawLine(true)
            }
        }
    }

    class ExperimentalSpan : ReplacementSpan {
        var textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        var bgPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var layout: StaticLayout? = null
        var width: Float = 0f
        var height: Float = 0f

        var color = 0

        constructor() {
            textPaint.setTypeface(AndroidUtilities.bold())
            bgPaint.style = Paint.Style.FILL
            textPaint.textSize = AndroidUtilities.dp(12f).toFloat()
        }

        private var text: CharSequence? = "NEW"
        fun setText(text: CharSequence?) {
            this.text = text
            if (layout != null) {
                layout = null
                makeLayout()
            }
        }

        fun makeLayout(): StaticLayout? {
            if (layout == null) {
                layout = StaticLayout(
                    text,
                    textPaint,
                    AndroidUtilities.displaySize.x,
                    Layout.Alignment.ALIGN_NORMAL,
                    1f,
                    0f,
                    false
                )
                width = layout!!.getLineWidth(0)
                height = layout!!.height.toFloat()
            }
            return layout
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int {
            makeLayout()
            return (AndroidUtilities.dp(10f) + width).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, _x: Float, top: Int, _y: Int, bottom: Int, paint: Paint) {
            makeLayout()

            bgPaint.setColor(color)
            textPaint.setColor(color)
            bgPaint.setAlpha(16)
            textPaint.setAlpha(255)

            val x = _x + AndroidUtilities.dp(2f)
            val y = _y - height + AndroidUtilities.dp(1f)
            AndroidUtilities.rectTmp.set(x, y, x + width, y + height)
            val r = AndroidUtilities.dp(4.4f).toFloat()
            AndroidUtilities.rectTmp.inset(
                AndroidUtilities.dp(-4f).toFloat(),
                AndroidUtilities.dp(-2.33f).toFloat()
            )

            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, bgPaint)
            canvas.withTranslation(x, y) {
                layout!!.draw(this)
            }
        }
    }

    protected fun addExperimentalSpan(string: CharSequence): CharSequence {
        val tag = LocaleController.getString(R.string.InuExperimental)

        val tagSpan = ExperimentalSpan()
        tagSpan.color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader)
        tagSpan.setText(tag)

        val tagText = SpannableString(tag)
        tagText.setSpan(tagSpan, 0, tagText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val text = SpannableStringBuilder(string)
        text.append("  ")
        text.append(tagText)
        return text
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float) = false
}
