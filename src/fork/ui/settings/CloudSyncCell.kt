package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.Switch

@SuppressLint("ViewConstructor")
class CloudSyncCell(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider?,
    onPickAccount: (View) -> Unit,
    onToggleAuto: () -> Unit,
) : FrameLayout(context) {

    private val avatarView = BackupImageView(context).apply {
        setRoundRadius(AndroidUtilities.dp(22f))
    }

    private val badgeView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageResource(R.drawable.inu_tabler_cloud)
        setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider))
        background = Theme.createCircleDrawable(
            AndroidUtilities.dp(20f),
            Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider),
        )
        setPadding(AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f))
    }

    private val badgeContainer = FrameLayout(context).apply {
        background = Theme.createCircleDrawable(
            AndroidUtilities.dp(24f),
            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
        )
        addView(badgeView, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
    }

    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = LocaleController.getString(R.string.InuCloudSync)
    }

    private val subtitleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val switchView = Switch(context, resourcesProvider).apply {
        setColors(
            Theme.key_switchTrack,
            Theme.key_switchTrackChecked,
            Theme.key_windowBackgroundWhite,
            Theme.key_windowBackgroundWhite,
        )
    }

    private val avatarContainer = FrameLayout(context).apply {
        ScaleStateListAnimator.apply(this)
        setOnClickListener { onPickAccount(this) }
        addView(avatarView, LayoutHelper.createFrame(44, 44, Gravity.START or Gravity.TOP))
        addView(badgeContainer, LayoutHelper.createFrame(24, 24, Gravity.END or Gravity.BOTTOM))
    }

    private var hasAccount = false

    init {
        background = Theme.getSelectorDrawable(false, resourcesProvider)
        contentDescription = LocaleController.getString(R.string.InuCloudSyncAuto)
        setOnClickListener {
            if (hasAccount) onToggleAuto() else onPickAccount(avatarContainer)
        }

        addView(
            avatarContainer,
            LayoutHelper.createFrame(48, 48f, Gravity.START or Gravity.CENTER_VERTICAL, 18f, 0f, 0f, 0f),
        )

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                subtitleView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f),
            )
        }
        addView(
            textContainer,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.START or Gravity.CENTER_VERTICAL,
                78f, 0f, 78f, 0f,
            ),
        )

        addView(
            switchView,
            LayoutHelper.createFrame(
                37,
                if (InuConfig.MATERIAL3_SWITCHES.value) 24f else 20f,
                Gravity.END or Gravity.CENTER_VERTICAL,
                0f, 0f, 20f, 0f,
            ),
        )
    }

    fun setState(account: Int, status: CharSequence, autoSync: Boolean, animated: Boolean) {
        val user = if (account >= 0) UserConfig.getInstance(account).currentUser else null
        if (user != null) {
            avatarView.imageReceiver.currentAccount = account
            avatarView.setForUserOrChat(user, AvatarDrawable().apply { setInfo(user) })
        } else {
            avatarView.setImageDrawable(
                AvatarDrawable().apply { setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED) }
            )
        }
        subtitleView.text = Emoji.replaceEmoji(status, subtitleView.paint.fontMetricsInt, false)
        hasAccount = account >= 0
        switchView.setChecked(autoSync, animated)
        switchView.alpha = if (hasAccount) 1f else 0.5f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(76f), MeasureSpec.EXACTLY),
        )
    }
}
