package desu.inugram.ui.drawer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import desu.inugram.helpers.dialogs.DrawerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.ThemeActivity

class DrawerThemeButton(
    context: Context,
    private val drawerLayoutContainer: DrawerLayoutContainer,
) : RLottieImageView(context), NotificationCenter.NotificationCenterDelegate {

    private var currentIconColor: Int? = null
    private var iconColorAnimator: ValueAnimator? = null
    private var pendingCrossfadeFrom: Int? = null

    companion object {
        const val SIZE_DP = 48

        // Shared across instances so the animation state persists when the drawer is rebuilt.
        private var sunDrawable: RLottieDrawable? = null
    }

    init {
        val playDrawable = sunDrawable == null
        if (playDrawable) {
            sunDrawable = RLottieDrawable(R.raw.sun, AndroidUtilities.dp(28f), AndroidUtilities.dp(28f), true, null)
            sunDrawable!!.setPlayInDirectionOfCustomEndFrame(true)
            if (Theme.isCurrentThemeDay()) {
                sunDrawable!!.setCustomEndFrame(0)
                sunDrawable!!.setCurrentFrame(0)
            } else {
                sunDrawable!!.setCurrentFrame(35)
                sunDrawable!!.setCustomEndFrame(36)
            }
        }
        isFocusable = true
        scaleType = ImageView.ScaleType.CENTER
        elevation = AndroidUtilities.dpf2(1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = 0x40000000.toInt()
            outlineSpotShadowColor = 0x40000000.toInt()
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        setAnimation(sunDrawable)
        updateColors()
        if (!playDrawable && sunDrawable!!.customEndFrame != sunDrawable!!.currentFrame) {
            playAnimation()
        }

        setOnClickListener {
            if (DialogsActivity.switchingTheme) return@setOnClickListener
            DialogsActivity.switchingTheme = true
            val preferences = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE)
            var dayThemeName = preferences.getString("lastDayTheme", "Blue")!!
            if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark) {
                dayThemeName = "Blue"
            }
            var nightThemeName = preferences.getString("lastDarkTheme", "Dark Blue")!!
            if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark) {
                nightThemeName = "Dark Blue"
            }
            var themeInfo = Theme.getActiveTheme()
            if (dayThemeName == nightThemeName) {
                if (themeInfo.isDark || dayThemeName == "Dark Blue" || dayThemeName == "Night") {
                    dayThemeName = "Blue"
                } else {
                    nightThemeName = "Dark Blue"
                }
            }

            val toDark = dayThemeName == themeInfo.key
            if (toDark) {
                themeInfo = Theme.getTheme(nightThemeName)
                sunDrawable!!.setCustomEndFrame(36)
            } else {
                themeInfo = Theme.getTheme(dayThemeName)
                sunDrawable!!.setCustomEndFrame(0)
            }
            if (!toDark) {
                // dark→light: hide icon and wait for a composed frame before snapshot, otherwise
                // PixelCopy reads the prior surfaceflinger buffer and old sun bleeds through.
                // Floating sun overlay (sourceView path in LaunchActivity) renders the morph on top.
                val fromColor = currentIconColor
                pendingCrossfadeFrom = fromColor
                if (fromColor != null) {
                    sunDrawable!!.colorFilter = PorterDuffColorFilter(fromColor, PorterDuff.Mode.SRC_IN)
                }
                visibility = INVISIBLE
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback {
                        playAnimation()
                        switchTheme(themeInfo, toDark)
                    }
                }
            } else {
                playAnimation()
                switchTheme(themeInfo, toDark)
            }

            // Adapted from 11.14.1: 12.x turnOffAutoNight takes BulletinFactory instead of a host FrameLayout.
            // Build a BulletinFactory from the currently-presented fragment so the bulletin shows on the host.
            val openSettings = Runnable {
                drawerLayoutContainer.inu_drawer?.closeDrawer(false)
                drawerLayoutContainer.parentActionBarLayout.presentFragment(ThemeActivity(ThemeActivity.THEME_TYPE_NIGHT))
            }
            val lastFragment: BaseFragment? = drawerLayoutContainer.parentActionBarLayout?.lastFragment
            val bf: BulletinFactory? = lastFragment?.let { BulletinFactory.of(it) }
            Theme.turnOffAutoNight(bf, openSettings)
        }
        setOnLongClickListener {
            drawerLayoutContainer.inu_drawer?.closeDrawer(false)
            drawerLayoutContainer.parentActionBarLayout.presentFragment(ThemeActivity(ThemeActivity.THEME_TYPE_BASIC))
            true
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = if (Theme.isCurrentThemeDark())
            LocaleController.getString(R.string.AccDescrSwitchToDayTheme)
        else
            LocaleController.getString(R.string.AccDescrSwitchToNightTheme)
    }

    fun updateColors() {
        background = Theme.createSimpleSelectorCircleDrawable(
            AndroidUtilities.dp(SIZE_DP.toFloat()),
            DrawerHelper.getTonalColor(),
            Theme.getColor(Theme.key_listSelector),
        )
        (background as? RippleDrawable)?.let { Theme.setRippleDrawableForceSoftware(it) }
    }

    fun updateSunDrawable(toDark: Boolean) {
        sunDrawable?.setCustomEndFrame(if (toDark) 36 else 0)
        playAnimation()
    }

    private fun switchTheme(themeInfo: Theme.ThemeInfo, toDark: Boolean) {
        val pos = IntArray(2)
        getLocationInWindow(pos)
        pos[0] += measuredWidth / 2
        pos[1] += measuredHeight / 2
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.needSetDayNightTheme, themeInfo, false, pos, -1, toDark, this)
    }

    private fun startIconColorCrossfade(from: Int, to: Int) {
        iconColorAnimator?.cancel()
        sunDrawable?.colorFilter = PorterDuffColorFilter(from, PorterDuff.Mode.SRC_IN)
        iconColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 400
            addUpdateListener {
                sunDrawable?.colorFilter = PorterDuffColorFilter(it.animatedValue as Int, PorterDuff.Mode.SRC_IN)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (iconColorAnimator === a) iconColorAnimator = null
                    sunDrawable?.colorFilter = PorterDuffColorFilter(to, PorterDuff.Mode.SRC_IN)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val iconColor = Theme.getColor(Theme.key_chats_menuItemIcon)
        if (currentIconColor == null || currentIconColor != iconColor) {
            val prev = currentIconColor
            currentIconColor = iconColor
            val crossfadeFrom = pendingCrossfadeFrom ?: prev
            if (pendingCrossfadeFrom != null && crossfadeFrom != null && crossfadeFrom != iconColor) {
                pendingCrossfadeFrom = null
                startIconColorCrossfade(crossfadeFrom, iconColor)
            } else if (iconColorAnimator == null && pendingCrossfadeFrom == null) {
                sunDrawable!!.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            }
        }
        super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.needSetDayNightTheme)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.needSetDayNightTheme)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.needSetDayNightTheme) {
            currentIconColor = null
            invalidate()
        }
    }
}
