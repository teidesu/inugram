package desu.inugram.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.WebPreviewHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuWebPreviewReplacementsActivity : InuSettingsPageActivity() {
    private var replacements = WebPreviewHelper.load().toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuWebPreviewReplacements)

    override fun onResume() {
        super.onResume()
        listView?.post { updateDisabledAlpha() }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.Enable))
                .setChecked(InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuWebPreviewReplacementsInfo)))

        for ((i, r) in replacements.withIndex()) {
            items.add(UItem.asButton(ITEM_BASE + i, r.pattern, r.replacement))
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuWebPreviewAdd)))
        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset,
                LocaleController.getString(R.string.InuWebPreviewReset)
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.toggle()
                (view as? TextCheckCell)?.setChecked(new)
                (view as? TextCheckCell)?.setBackgroundColorAnimated(
                    new,
                    Theme.getColor(if (new) Theme.key_windowBackgroundChecked else Theme.key_windowBackgroundUnchecked)
                )
                updateDisabledAlpha()
            }

            else -> {
                if (!InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) return
                when (item.id) {
                    BUTTON_ADD -> showEditDialog(-1)
                    BUTTON_RESET -> {
                        WebPreviewHelper.resetToDefault()
                        replacements = WebPreviewHelper.DEFAULT_REPLACEMENTS.toMutableList()
                        listView.adapter.update(true)
                    }

                    else -> {
                        val idx = item.id - ITEM_BASE
                        if (idx in replacements.indices) {
                            showEditDialog(idx)
                        }
                    }
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (!InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) return false
        val idx = item.id - ITEM_BASE
        if (idx !in replacements.indices) return false
        showDeleteDialog(idx, view)
        return true
    }

    private fun updateDisabledAlpha() {
        val alpha = if (InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) 1f else 0.5f
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val pos = listView.getChildAdapterPosition(child)
            val item = (listView.adapter as? UniversalAdapter)?.getItem(pos) ?: continue
            if (item.id != TOGGLE_ENABLED && item.viewType != UniversalAdapter.VIEW_TYPE_SHADOW) {
                child.alpha = alpha
            }
        }
    }

    private fun showEditDialog(index: Int) {
        val ctx = context ?: return
        val existing = if (index >= 0) replacements[index] else null

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val patternInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuWebPreviewPattern)
            setText(existing?.pattern ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(patternInput, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        val replacementInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuWebPreviewReplacement)
            setText(existing?.replacement ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(replacementInput, LinearLayout.LayoutParams(-1, -2))

        val title = if (existing != null)
            LocaleController.getString(R.string.InuWebPreviewEdit)
        else
            LocaleController.getString(R.string.InuWebPreviewAdd)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val pattern = patternInput.text.toString().trim()
                val replacement = replacementInput.text.toString().trim()
                if (pattern.isEmpty()) return@setPositiveButton
                val r = WebPreviewHelper.Replacement(pattern, replacement)
                if (index >= 0) {
                    replacements[index] = r
                } else {
                    replacements.add(r)
                }
                WebPreviewHelper.save(replacements)
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                replacements.removeAt(index)
                WebPreviewHelper.save(replacements)
                listView.adapter.update(true)
            }
        }

        showDialog(builder.create())
    }

    private fun showDeleteDialog(index: Int, view: View) {
        ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(listView.getClipBackground(view))
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
                replacements.removeAt(index)
                WebPreviewHelper.save(replacements)
                listView.adapter.update(true)
            }.show()
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
        private const val ITEM_BASE = 10000

        private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())
    }
}
