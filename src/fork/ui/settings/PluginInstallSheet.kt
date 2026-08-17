package desu.inugram.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.SourceObfuscation
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * The confirmation shown before a plugin is written to disk: what it is, what it may do once it
 * runs, and how to read its code first. Everything here is a *claim the file makes about itself*,
 * so the sheet is deliberately the same shape as [PluginInfoActivity] - a user who installs one and
 * inspects it later should be looking at the same rows.
 */
class PluginInstallSheet(
    context: Context,
    private val manifest: PluginManifest,
    private val source: String,
    private val obfuscation: SourceObfuscation?,
    private val onInstall: (enable: Boolean) -> Unit,
) : BottomSheet(context, false) {
    private var enableNow = true

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val icon = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(16f)) }
        PluginManifestIcons.bindIcon(icon, manifest.icon, PluginManifestIcons.createPlaceholder(context))
        container.addView(icon, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0))

        container.addView(
            centeredText(context, manifest.name, 20f, Theme.key_dialogTextBlack, bold = true),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 12, 20, 0),
        )

        val meta = listOfNotNull(
            manifest.version?.let { "v$it" },
            manifest.author?.let { LocaleController.formatString(R.string.InuPluginsByAuthor, it) },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            container.addView(
                centeredText(context, meta, 13f, Theme.key_dialogTextGray3, bold = false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 4, 20, 0),
            )
        }

        val description = manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)
        if (!description.isNullOrBlank()) {
            container.addView(
                centeredText(context, description, 14f, Theme.key_dialogTextGray3, bold = false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 10, 24, 0),
            )
        }

        if (obfuscation != null) {
            val banner = WarningBanner(context)
            when (obfuscation) {
                SourceObfuscation.OBFUSCATED -> {
                    banner.setTitle(LocaleController.getString(R.string.InuPluginObfuscatedTitle))
                    banner.setText(LocaleController.getString(R.string.InuPluginObfuscatedInfo))
                }
                SourceObfuscation.MINIFIED -> {
                    banner.setTitle(LocaleController.getString(R.string.InuPluginMinifiedTitle))
                    banner.setText(LocaleController.getString(R.string.InuPluginMinifiedInfo))
                }
            }
            container.addView(
                banner,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 12, 10, 0),
            )
        }

        container.addView(
            HeaderCell(context).apply { setText(LocaleController.getString(R.string.InuPluginsPermissions)) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0, 0),
        )
        val grants = mergeGrants(manifest.grants)
        if (grants.isEmpty()) {
            val none = TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setPadding(AndroidUtilities.dp(22f), 0, AndroidUtilities.dp(22f), 0)
                text = LocaleController.getString(R.string.InuPluginsPermissionsNone)
            }
            container.addView(none, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        } else {
            for ((name, scopes) in grants) {
                val row = GrantRowView(context).apply { bind(name, scopes) }
                container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        }

        val enableCell = TextCheckCell(context)
        enableCell.setTextAndCheck(LocaleController.getString(R.string.InuPluginInstallEnable), enableNow, true)
        enableCell.setOnClickListener {
            enableNow = !enableNow
            enableCell.setChecked(enableNow)
        }
        container.addView(
            enableCell,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0, 0),
        )

        val sourceCell = TextCell(context)
        sourceCell.setTextAndIcon(LocaleController.getString(R.string.InuPluginsViewSource), R.drawable.inu_tabler_code, false)
        sourceCell.setOnClickListener { PluginSourceSheet(context, manifest.name, source).show() }
        container.addView(sourceCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cancelBtn = createSheetButton(
            context,
            LocaleController.getString(R.string.Cancel),
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(21f), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            dismiss()
        }
        val installBtn = createSheetButton(
            context,
            LocaleController.getString(R.string.InuPluginInstall),
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            dismiss()
            onInstall(enableNow)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            })
            addView(installBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
        }
        container.addView(
            buttonRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 10, 16, 16),
        )

        setCustomView(NestedScrollView(context).apply { addView(container) })
    }

    private fun centeredText(context: Context, text: CharSequence, size: Float, colorKey: Int, bold: Boolean) =
        TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Theme.getColor(colorKey))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            if (bold) typeface = AndroidUtilities.bold()
            this.text = text
        }
}
