package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.DrawerHelper
import org.telegram.PhoneFormat.PhoneFormat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Reactions.HwEmojis
import org.telegram.ui.Components.SnowflakesEffect

class DrawerProfileCell(
    context: Context,
    private val drawerLayoutContainer: DrawerLayoutContainer,
    private val onAccountsToggle: () -> Unit,
) :
    FrameLayout(context), NotificationCenter.NotificationCenterDelegate {

    private val avatarImageView: BackupImageView
    private val accountRowView: View
    private val nameTextView: SimpleTextView
    private val phoneTextView: TextView
    private val arrowView: ImageView

    private var currentIconColor: Int? = null
    private var snowflakesEffect: SnowflakesEffect? = null
    private var accountsShown = false

    val status: AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable

    private var lastAccount = -1
    private var lastUser: TLRPC.User? = null
    private var premiumStar: Drawable? = null

    init {
        setWillNotDraw(false)
        accountRowView = View(context)
        accountRowView.setOnClickListener {
            onAccountsToggle()
        }
        applyBackground()
        addView(accountRowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64f, Gravity.LEFT or Gravity.TOP, 0f, 4f, 0f, 0f))

        avatarImageView = BackupImageView(context)
        avatarImageView.imageReceiver.setRoundRadius(AndroidUtilities.dp(20f))
        avatarImageView.setOnClickListener {
            DrawerHelper.openMyProfile(drawerLayoutContainer)
        }
        addView(avatarImageView, LayoutHelper.createFrame(40, 40f, Gravity.LEFT or Gravity.TOP, 16f, 16f, 0f, 0f))

        nameTextView = object : SimpleTextView(context) {
            // HwEmojis hooks: hardware emoji rendering batches invalidations; skip them here.
            override fun invalidate() {
                if (HwEmojis.grab(this)) return
                super.invalidate()
            }

            override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
                if (HwEmojis.grab(this)) return
                super.invalidate(l, t, r, b)
            }

            override fun invalidateDrawable(who: Drawable) {
                if (HwEmojis.grab(this)) return
                super.invalidateDrawable(who)
            }

            override fun invalidate(dirty: Rect) {
                if (HwEmojis.grab(this)) return
                super.invalidate(dirty)
            }
        }
        nameTextView.setTextSize(16)
        nameTextView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM))
        nameTextView.setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
        nameTextView.setEllipsizeByGradient(true)
        nameTextView.setRightDrawableOutside(true)
        addView(
            nameTextView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24f, Gravity.LEFT or Gravity.TOP, 68f, 13f, 52f, 0f)
        )

        phoneTextView = TextView(context)
        phoneTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        phoneTextView.setLines(1)
        phoneTextView.maxLines = 1
        phoneTextView.isSingleLine = true
        phoneTextView.gravity = Gravity.LEFT
        addView(
            phoneTextView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.TOP, 68f, 37f, 52f, 0f)
        )

        arrowView = ImageView(context)
        arrowView.scaleType = ImageView.ScaleType.CENTER
        arrowView.setImageResource(R.drawable.msg_expand)
        addView(arrowView, LayoutHelper.createFrame(48, 48f, Gravity.RIGHT or Gravity.TOP, 0f, 12f, 4f, 0f))
        setArrowState(false)


        if (Theme.getEventType() == 0) {
            snowflakesEffect = SnowflakesEffect(0)
            snowflakesEffect!!.setColorKey(Theme.key_chats_menuItemIcon)
        }

        status = AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, AndroidUtilities.dp(20f))
        nameTextView.setRightDrawable(status)
        nameTextView.setRightDrawableOnClick {
            val user = lastUser ?: return@setRightDrawableOnClick
            if (user.premium || UserObject.getEmojiStatusDocumentId(user) != null) {
                DrawerHelper.showSelectStatusDialog(this, drawerLayoutContainer)
            }
        }
    }

    fun getEmojiStatusLocation(rect: Rect) {
        val rightDrawable = nameTextView.rightDrawable
        if (rightDrawable == null) {
            rect.set(nameTextView.width - 1, nameTextView.height / 2 - 1, nameTextView.width + 1, nameTextView.height / 2 + 1)
        } else {
            rect.set(rightDrawable.bounds)
        }
        rect.offset(nameTextView.x.toInt(), nameTextView.y.toInt())
    }

    fun getEmojiStatusDrawableParent(): View = nameTextView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        updateColors()
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.needSetDayNightTheme)
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.currentUserPremiumStatusChanged)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        status.detach()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.needSetDayNightTheme)
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.currentUserPremiumStatusChanged)
        }
        if (lastAccount >= 0) {
            NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.userEmojiStatusUpdated)
            NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.updateInterfaces)
            lastAccount = -1
        }
        val rightDrawable = nameTextView.rightDrawable
        if (rightDrawable is AnimatedEmojiDrawable.WrapSizeDrawable) {
            val inner = rightDrawable.drawable
            if (inner is AnimatedEmojiDrawable) {
                inner.removeView(nameTextView)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(68f) + AndroidUtilities.statusBarHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val iconColor = Theme.getColor(Theme.key_chats_menuItemIcon)
        if (currentIconColor == null || currentIconColor != iconColor) {
            currentIconColor = iconColor
            arrowView.setColorFilter(PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN))
        }
        val textColor = Theme.getColor(Theme.key_chats_menuItemText)
        nameTextView.setTextColor(textColor)
        phoneTextView.setTextColor(Theme.multAlpha(textColor, 0.7f))
        super.onDraw(canvas)
        snowflakesEffect?.onDraw(this, canvas)
    }

    fun isAccountsShown(): Boolean = accountsShown

    fun setAccountsShown(value: Boolean, animated: Boolean) {
        if (accountsShown == value) return
        accountsShown = value
        setArrowState(animated)
    }

    fun setUser(user: TLRPC.User?, accounts: Boolean) {
        val account = UserConfig.selectedAccount
        if (account != lastAccount) {
            if (lastAccount >= 0) {
                NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.userEmojiStatusUpdated)
                NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.updateInterfaces)
            }
            lastAccount = account
            NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.userEmojiStatusUpdated)
            NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.updateInterfaces)
        }
        lastUser = user
        if (user == null) return
        if (accountsShown != accounts) {
            accountsShown = accounts
            setArrowState(false)
        }
        var text: CharSequence = UserObject.getUserName(user)
        try {
            text = Emoji.replaceEmoji(text, nameTextView.paint.fontMetricsInt, false)
        } catch (ignore: Exception) {
        }

        nameTextView.setText(text)
        val emojiStatusId = UserObject.getEmojiStatusDocumentId(user)
        if (emojiStatusId != null) {
            val isCollectible = user.emoji_status is TLRPC.TL_emojiStatusCollectible
            nameTextView.setDrawablePadding(AndroidUtilities.dp(4f))
            status.set(emojiStatusId, true)
            status.setParticles(isCollectible, true)
        } else if (user.premium) {
            nameTextView.setDrawablePadding(AndroidUtilities.dp(4f))
            if (premiumStar == null) {
                premiumStar = resources.getDrawable(R.drawable.msg_premium_liststar).mutate()
            }
            premiumStar!!.setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedBackground), PorterDuff.Mode.MULTIPLY))
            status.set(premiumStar, true)
            status.setParticles(false, true)
        } else {
            status.set(null as Drawable?, true)
            status.setParticles(false, true)
        }
        status.setColor(Theme.getColor(Theme.key_chats_verifiedBackground))
        phoneTextView.text = if (InuConfig.HIDE_MY_PHONE_NUMBER.value) {
            LocaleController.getString(R.string.MobileHidden)
        } else {
            PhoneFormat.getInstance().format("+" + user.phone)
        }
        val avatarDrawable = AvatarDrawable(user)
        avatarDrawable.setColor(Theme.getColor(Theme.key_avatar_backgroundInProfileBlue))
        avatarImageView.setForUserOrChat(user, avatarDrawable)
    }

    // updated in place: replacing the drawable restarts an in-flight ripple from the center
    private fun applyBackground() {
        val color = Theme.getColor(Theme.key_listSelector)
        val background = accountRowView.background
        if (background == null) {
            accountRowView.background = Theme.createRadSelectorDrawable(color, 24, 24)
        } else {
            Theme.setSelectorDrawableColor(background, color, true)
        }
    }

    fun updateColors() {
        snowflakesEffect?.updateColors()
        status.setColor(Theme.getColor(Theme.key_chats_verifiedBackground))
    }

    private fun setArrowState(animated: Boolean) {
        val rotation = if (accountsShown) 180.0f else 0.0f
        if (animated) {
            arrowView.animate().rotation(rotation).setDuration(SideMenultItemAnimator.DURATION).setInterpolator(SideMenultItemAnimator.INTERPOLATOR).start()
        } else {
            arrowView.animate().cancel()
            arrowView.rotation = rotation
        }
        accountRowView.contentDescription = if (accountsShown)
            LocaleController.getString(R.string.AccDescrHideAccounts)
        else
            LocaleController.getString(R.string.AccDescrShowAccounts)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.emojiLoaded -> nameTextView.invalidate()
            NotificationCenter.needSetDayNightTheme -> {
                currentIconColor = null
                applyBackground()
                updateColors()
                invalidate()
            }

            NotificationCenter.userEmojiStatusUpdated -> setUser(args[0] as TLRPC.User, accountsShown)
            NotificationCenter.currentUserPremiumStatusChanged -> setUser(
                UserConfig.getInstance(UserConfig.selectedAccount).currentUser,
                accountsShown
            )

            NotificationCenter.updateInterfaces -> {
                val flags = args[0] as Int
                if (flags and MessagesController.UPDATE_MASK_NAME != 0 ||
                    flags and MessagesController.UPDATE_MASK_AVATAR != 0 ||
                    flags and MessagesController.UPDATE_MASK_STATUS != 0 ||
                    flags and MessagesController.UPDATE_MASK_PHONE != 0 ||
                    flags and MessagesController.UPDATE_MASK_EMOJI_STATUS != 0
                ) {
                    setUser(UserConfig.getInstance(UserConfig.selectedAccount).currentUser, accountsShown)
                }
            }
        }
    }

}
