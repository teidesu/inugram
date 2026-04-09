package desu.inugram.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BackgroundGradientDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MotionBackgroundDrawable
import kotlin.math.ceil
import kotlin.math.max

@SuppressLint("ViewConstructor")
class StickerSizePreviewMessagesCell(context: Context?, fragment: BaseFragment) : LinearLayout(context) {
    private var backgroundGradientDisposable: BackgroundGradientDrawable.Disposable? = null

    private val cells: Array<ChatMessageCell>
    private val messageObjects: Array<MessageObject>
    private val shadowDrawable: Drawable

    init {
        val resourcesProvider = fragment.getResourceProvider()

        setWillNotDraw(false)
        orientation = VERTICAL
        setPadding(0, AndroidUtilities.dp(11f), 0, AndroidUtilities.dp(11f))

        shadowDrawable = Theme.getThemedDrawable(
            context,
            R.drawable.greydivider_bottom,
            Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider)
        )

        val now = (System.currentTimeMillis() / 1000).toInt() - 60 * 60
        val selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId

        val stickerTlMessage = TLRPC.TL_message().apply {
            date = now + 10
            dialog_id = 1
            flags = 257
            from_id = TLRPC.TL_peerUser().apply { user_id = selfId }
            id = 1
            media = TLRPC.TL_messageMediaDocument().apply {
                flags = 1
                document = TLRPC.TL_document().apply {
                    mime_type = "image/webp"
                    file_reference = ByteArray(0)
                    access_hash = 0
                    date = now
                    attributes.add(TLRPC.TL_documentAttributeSticker().apply { alt = "🐱" })
                    attributes.add(TLRPC.TL_documentAttributeImageSize().apply {
                        h = 512
                        w = 512
                    })
                }
            }
            message = ""
            out = true
            peer_id = TLRPC.TL_peerUser().apply { user_id = 0 }
        }

        val textTlMessage = TLRPC.TL_message().apply {
            message = getString(R.string.InuStickerSizeDialogMessage)
            date = now + 1270
            dialog_id = -1
            flags = 259
            id = 3
            reply_to = TLRPC.TL_messageReplyHeader().apply {
                flags = flags or 16
                reply_to_msg_id = 2
            }
            media = TLRPC.TL_messageMediaEmpty()
            out = false
            peer_id = TLRPC.TL_peerUser().apply { user_id = 1 }
        }

        val stickerMessageObject = MessageObject(UserConfig.selectedAccount, stickerTlMessage, true, false).apply {
            useCustomPhoto = true
        }
        val textMessageObject = MessageObject(UserConfig.selectedAccount, textTlMessage, true, false).apply {
            replyMessageObject = stickerMessageObject
        }
        messageObjects = arrayOf(stickerMessageObject, textMessageObject)

        cells = Array(messageObjects.size) { i ->
            ChatMessageCell(context, UserConfig.selectedAccount, false, null, resourcesProvider).apply {
                delegate = object : ChatMessageCell.ChatMessageCellDelegate {
                    override fun canPerformActions() = true

                    override fun didPressImage(cell: ChatMessageCell?, x: Float, y: Float, fullPreview: Boolean) {
                        BulletinFactory.of(fragment)
                            .createErrorBulletin(getString(R.string.InuWoof), resourcesProvider)
                            .show()
                    }
                }
                isChat = false
                setFullyDraw(true)
                setMessageObject(messageObjects[i], null, false, false, false)
            }.also {
                addView(it, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        }
    }

    override fun invalidate() {
        super.invalidate()
        cells.forEachIndexed { i, cell ->
            cell.setMessageObject(messageObjects[i], null, false, false, false)
            cell.invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = Theme.getCachedWallpaperNonBlocking() ?: return
        drawable.alpha = 255
        if (drawable is ColorDrawable || drawable is GradientDrawable || drawable is MotionBackgroundDrawable) {
            drawable.setBounds(0, 0, measuredWidth, measuredHeight)
            if (drawable is BackgroundGradientDrawable) {
                backgroundGradientDisposable = drawable.drawExactBoundsSize(canvas, this)
            } else {
                drawable.draw(canvas)
            }
        } else if (drawable is BitmapDrawable) {
            if (drawable.tileModeX == Shader.TileMode.REPEAT) {
                canvas.save()
                val scale = 2.0f / AndroidUtilities.density
                canvas.scale(scale, scale)
                drawable.setBounds(
                    0,
                    0,
                    ceil((measuredWidth / scale).toDouble()).toInt(),
                    ceil((measuredHeight / scale).toDouble()).toInt()
                )
            } else {
                val viewHeight = measuredHeight
                val scaleX = measuredWidth.toFloat() / drawable.intrinsicWidth.toFloat()
                val scaleY = (viewHeight).toFloat() / drawable.intrinsicHeight.toFloat()
                val scale = max(scaleX, scaleY)
                val width = ceil((drawable.intrinsicWidth * scale).toDouble()).toInt()
                val height = ceil((drawable.intrinsicHeight * scale).toDouble()).toInt()
                val x = (measuredWidth - width) / 2
                val y = (viewHeight - height) / 2
                canvas.save()
                canvas.clipRect(0, 0, width, measuredHeight)
                drawable.setBounds(x, y, x + width, y + height)
            }
            drawable.draw(canvas)
            canvas.restore()
        }
        shadowDrawable.setBounds(0, 0, measuredWidth, measuredHeight)
        shadowDrawable.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (backgroundGradientDisposable != null) {
            backgroundGradientDisposable!!.dispose()
            backgroundGradientDisposable = null
        }
    }

    override fun dispatchSetPressed(pressed: Boolean) {
    }
}
