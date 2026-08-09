package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.CounterView

object AttachMinimizeHelper {
    private const val BADGE_SCALE = 0.62f
    private const val BADGE_HEIGHT = 23f
    private const val BADGE_OFFSET = 10f

    @JvmStatic
    fun syncBadge(chatActivity: ChatActivity) {
        val enterView = chatActivity.chatActivityEnterView ?: return
        val button = enterView.attachButton ?: return
        val count = chatActivity.chatAttachAlert?.inu_getPreservedCount() ?: 0

        var badge = enterView.inu_attachBadge
        if (badge == null) {
            if (count == 0) return
            badge = CounterView.CounterDrawable(button, true, chatActivity.resourceProvider).apply {
                gravity = Gravity.LEFT
                setSize(AndroidUtilities.dp(BADGE_HEIGHT), 0)
            }
            enterView.inu_attachBadge = badge
        }
        badge.setCount(count, true)
    }

    @JvmStatic
    fun drawBadge(enterView: ChatActivityEnterView, button: View, canvas: Canvas) {
        val badge = enterView.inu_attachBadge ?: return
        val count = enterView.parentFragment?.chatAttachAlert?.inu_getPreservedCount() ?: 0
        if (count == 0 && badge.countChangeProgress == 1f) return

        badge.updateBackgroundRect()
        canvas.save()
        canvas.translate(
            button.width / 2f + AndroidUtilities.dp(BADGE_OFFSET),
            button.height / 2f - AndroidUtilities.dp(BADGE_OFFSET),
        )
        canvas.scale(BADGE_SCALE, BADGE_SCALE)
        canvas.translate(-badge.rectF.centerX(), -badge.rectF.centerY())
        badge.draw(canvas)
        canvas.restore()
    }
}
