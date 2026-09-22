package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.SessionResource
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import desu.inugram.core.plugins.CommonIcons
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.UiListener
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.dialogs.DrawerHelper
import desu.inugram.ui.settings.PluginSettingsActivity
import desu.inugram.ui.showInputDialog
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Cells.RadioColorCell
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity

/**
 * Kotlin side of the settings-page ui bridge (rust: `pages.rs`): presents [PluginSettingsActivity]
 * pages, routes `page.invalidate()` to open pages, anchors `UIAnchor.openMenu` popups to the row
 * the anchor names, and shows bulletins plus the `inu.ui.dialog`/`prompt`/`chooser` modals.
 *
 * Threading: upcalls arrive on [EngineDispatch.scheduler]; anything view-touching hops to the UI
 * thread and settles back on the plugin queue with the usual engine-identity check.
 */
object PluginUi : SessionResource {

    /** stock's `Bulletin.UsersLayout`: a 24dp avatar stepped by 12dp, in a slot sized for three */
    private const val AVATAR_SIZE_DP = 24
    private const val AVATAR_STEP_DP = 12
    private const val AVATAR_SLOT_DP = AVATAR_SIZE_DP + AVATAR_STEP_DP + AVATAR_STEP_DP + 8

    // keep in sync with rust `api::ui::OP_*`
    const val OP_DIALOG = 0
    const val OP_PROMPT = 1
    const val OP_CHOOSER = 2
    const val OP_BULLETIN = 3
    const val OP_PICK_FILE = 3
    const val OP_SAVE_FILE = 4

    // UI-thread state: open page views, keyed per engine so page ids can't cross plugins
    private class PageKey(val session: PluginSession, val pageId: Long) {
        override fun equals(other: Any?): Boolean =
            other is PageKey && other.session === session && other.pageId == pageId
        override fun hashCode(): Int = System.identityHashCode(session) * 31 + pageId.hashCode()
    }
    private val openPages = HashMap<PageKey, MutableList<PluginSettingsActivity>>()

    fun listenerFor(session: PluginSession): UiListener = object : UiListener {
        private val onHost = EngineDispatch.createHostDispatcher { session.isCurrent() }
        override fun uiToast(text: String) {
            AndroidUtilities.runOnUIThread {
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show()
            }
        }

        override fun uiModal(op: Int, requestId: Long, optionsJson: String): String? =
            modal(session, op, requestId, optionsJson)

        override fun uiCurrentScreen(): String = PluginScreens.currentScreenWire()

        override fun uiOpenPage(pageId: Long): String? = openPage(session, pageId)

        override fun uiOpenFragment(handle: Long): String? = openFragment(session.engine, handle)

        override fun uiOpenScreen(optionsJson: String): String? = openScreen(optionsJson)

        override fun uiRegisterSettings(pageId: Long) = onHost { registerSettings(session, pageId) }

        override fun uiUnregisterSettings(pageId: Long) = onHost { unregisterSettings(session, pageId) }

        override fun uiInvalidate(pageId: Long) = invalidate(session, pageId)

        override fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? =
            openMenu(session, menuId, pageId, anchorKey, itemsJson)

        override fun iconResolves(kind: Int, value: String): Boolean = PluginIcons.iconResolves(kind, value)

        override fun commonIcon(name: String): String? = CommonIcons.resolve(name)

        override fun actionRegister(
            kind: Int,
            token: Int,
            id: String,
            placements: Int,
            text: String?,
            icon: String?,
            dynamicFields: Int,
        ): String? = PluginActions.register(session, kind, token, id, placements, text, icon, dynamicFields)

        override fun actionUnregister(kind: Int, token: Int) = onHost { PluginActions.unregister(session.engine, kind, token) }

        override fun actionEditor(op: Int, surface: Long, payloadJson: String): String? =
            PluginActions.editorOp(op, surface, payloadJson)
    }

    fun onPageOpened(activity: PluginSettingsActivity) {
        openPages.getOrPut(PageKey(activity.session, activity.pageId)) { mutableListOf() }.add(activity)
    }

    fun onPageClosed(activity: PluginSettingsActivity) {
        val key = PageKey(activity.session, activity.pageId)
        val list = openPages[key] ?: return
        list.remove(activity)
        if (list.isNotEmpty()) return
        openPages.remove(key)
        EngineDispatch.onEngine(activity.session) {
            activity.session.engine.uiPageClosed(activity.pageId)
        }
    }

    /**
     * the engine is closed right after this, and a frozen page whose rows do nothing is worse than
     * no page. The key is dropped before the fragment is, so the teardown that follows does not
     * try to tell the (by then closed) engine that its page closed.
     */
    override fun detach(session: PluginSession) {
        AndroidUtilities.runOnUIThread {
            val mine = openPages.filterKeys { it.session === session }
            for ((key, list) in mine) {
                openPages.remove(key)
                // not finishFragment(), which closes whatever is on top: a plugin page can be buried under one the user opened from it
                for (activity in list.toList()) activity.removeSelfFromStack()
            }
        }
    }

    fun openPage(session: PluginSession, pageId: Long): String? {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            fragment.presentFragment(PluginSettingsActivity(session, pageId))
        }
        return null
    }

    /** the handle is resolved before the ui-thread hop, so naming something that is not a `BaseFragment` throws where the plugin can catch it */
    fun openFragment(engine: QuickJs, handle: Long): String? {
        val fragment = PluginJvm.objectAt(engine, handle)
            ?: return PluginWire.encodePluginError("handle-expired", "openPage: that java object is gone")
        if (fragment !is BaseFragment) {
            return PluginWire.encodePluginError(
                "invalid-argument",
                "openPage: expected a BaseFragment, got ${fragment.javaClass.name}",
            )
        }
        AndroidUtilities.runOnUIThread {
            LaunchActivity.getSafeLastFragment()?.presentFragment(fragment)
        }
        return null
    }

    fun openScreen(optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (_: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "openPage: malformed screen")
        }
        val accountId = if (options.has("accountId")) options.optInt("accountId", -1) else UserConfig.selectedAccount
        val controller = if (accountId in 0 until UserConfig.MAX_ACCOUNT_COUNT && UserConfig.isValidAccount(accountId)) {
            MessagesController.getInstance(accountId)
        } else {
            return PluginWire.encodePluginError("not-found", "openPage: account #$accountId is not logged in")
        }
        val type = options.optString("type")
        val dialogId = options.optLong("dialogId")
        if ((type == "chat" || type == "profile") && DialogObject.isEncryptedDialog(dialogId)) {
            return PluginWire.encodePluginError("forbidden", "openPage: secret chats are not available to plugins")
        }
        if ((type == "chat" || type == "profile") && controller.getUserOrChat(dialogId) == null) {
            return PluginWire.encodePluginError("not-found", "openPage: dialog '$dialogId' is not cached")
        }
        AndroidUtilities.runOnUIThread {
            val current = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            val next = when (type) {
                "chat" -> ChatActivity(Bundle().apply {
                    if (dialogId > 0) putLong("user_id", dialogId) else putLong("chat_id", -dialogId)
                    options.optInt("topicId").takeIf { it != 0 }?.let { putInt("message_id", it) }
                })
                "profile" -> ProfileActivity.of(dialogId)
                "dialogs" -> DrawerHelper.createMainFragment()
                "settings" -> SettingsActivity()
                else -> return@runOnUIThread
            }
            next.setCurrentAccount(accountId)
            if (type != "chat" || controller.checkCanOpenChat(next.arguments, current)) {
                current.presentFragment(next)
            }
        }
        return null
    }

    fun registerSettings(session: PluginSession, pageId: Long) {
        session.settingsPageId = pageId
        PluginManager.notifyChanged()
    }

    /** guarded by the page id: disposing a page the plugin has already replaced must not clear it */
    fun unregisterSettings(session: PluginSession, pageId: Long) {
        if (session.settingsPageId != pageId) return
        session.settingsPageId = null
        PluginManager.notifyChanged()
    }

    fun invalidate(session: PluginSession, pageId: Long) {
        AndroidUtilities.runOnUIThread {
            openPages[PageKey(session, pageId)]?.lastOrNull()?.requestRender()
        }
    }

    /**
     * the anchor names a row, not a view: by the time a plugin opens a menu the page may have
     * re-rendered and the row's view been recycled onto another row. So a row no longer on screen
     * leaves the menu unopened and settled as dismissed - the same answer as tapping outside.
     */
    fun openMenu(session: PluginSession, menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? {
        val items = try {
            parseMenuItems(itemsJson)
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "openMenu: ${e.message}")
        }
        AndroidUtilities.runOnUIThread {
            fun settle(slot: Int) {
                EngineDispatch.onEngine(session) { session.engine.uiMenuClick(menuId, slot) }
            }
            val activity = openPages[PageKey(session, pageId)]?.lastOrNull()
            val anchorView = activity?.anchorViewFor(anchorKey)
            if (activity == null || anchorView == null || activity.parentActivity == null || !anchorView.isAttachedToWindow) {
                settle(-1)
                return@runOnUIThread
            }
            var clicked = false
            val opts = ItemOptions.makeOptions(activity, anchorView)
            // any item specifying `checked` (even false) makes the menu radio-style, so every row goes through addChecked to align on the checkmark column
            val radioStyle = items.any { it.checked != null }
            items.forEachIndexed { index, item ->
                val onClick = Runnable {
                    clicked = true
                    settle(index)
                    activity.requestRender()
                }
                if (radioStyle) {
                    opts.addChecked(item.checked == true, item.text, onClick)
                } else {
                    opts.add(0, item.text, item.danger, onClick)
                }
            }
            opts.setOnDismiss {
                if (!clicked) settle(-1)
            }
            opts.show()
        }
        return null
    }

    private class MenuItem(val text: String, val checked: Boolean?, val danger: Boolean)

    private fun parseMenuItems(json: String): List<MenuItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MenuItem(
                o.getString("text"),
                if (o.has("checked")) o.getBoolean("checked") else null,
                o.optBoolean("danger"),
            )
        }
    }

    /**
     * Read off the plugin queue, before anything is shown, so an icon the engine cannot resolve is
     * a refusal the plugin is thrown rather than a bulletin that never appears.
     */
    private class BulletinSpec(session: PluginSession, options: JSONObject) {
        val text: String = options.getString("text")
        val textEntities: String = options.optJSONArray("textEntities")?.toString() ?: ""
        val subtitle: String? = options.optString("subtitle").takeIf { options.has("subtitle") }
        val subtitleEntities: String = options.optJSONArray("subtitleEntities")?.toString() ?: ""
        val iconSpec: String = options.optString("icon")
        val avatars: List<Long> = options.optJSONArray("avatars")?.let { array ->
            (0 until array.length()).map { array.getLong(it) }
        } ?: emptyList()
        val duration: Int = options.optInt("duration", Bulletin.DURATION_LONG)
        /** the avatars belong to an account, which is not always the one looking at the screen */
        val account: Int = options.optInt("account", UserConfig.selectedAccount)
        val top: Boolean? = if (options.has("top")) options.getBoolean("top") else null
        val button: String? = if (options.has("button")) options.getString("button") else null

        val animation = PluginIcons.parseAnimationSpec(iconSpec).takeIf { iconSpec.startsWith('a') }

        /** the drawable is resolved here for the same reason the spec is: a gone handle is a refusal */
        val drawable = if (iconSpec.startsWith('j')) {
            PluginIcons.resolveImmediateDrawable(ApplicationLoader.applicationContext, iconSpec, session.engine)
                ?: refuse("handle-expired", "bulletin: drawable icon is gone")
        } else {
            null
        }

        init {
            val animationName = animation?.value
            if (animationName != null && PluginIcons.getRawAnimationResourceId(animationName) == 0) {
                refuse("not-found", "bulletin: animation '$animationName' is unavailable")
            }
        }

        val largeAnimation: Boolean
            get() = animation != null && !animation.isStatic && (animation.repeatCount == 0 || animation.repeatCount == null)
    }

    /**
     * One of stock's three bulletin layouts, picked by what the plugin asked for: avatars make it a
     * `UsersLayout`, a subtitle without them a `TwoLineLottieLayout`, and neither the plain
     * `LottieLayout` a one-line bulletin has always been.
     *
     * The outcome is settled once, by whichever came first: the button, a tap on the body, or the
     * bulletin going away on its own.
     */
    private fun showBulletin(session: PluginSession, spec: BulletinSpec, settle: (String) -> Unit) {
        val fragment = LaunchActivity.getSafeLastFragment()
        val factory = fragment?.let { BulletinFactory.of(it) } ?: BulletinFactory.global()
        val context = fragment?.parentActivity ?: ApplicationLoader.applicationContext
        val provider = fragment?.resourceProvider
        val hasSubtitle = !spec.subtitle.isNullOrEmpty()

        val layout: Bulletin.ButtonLayout
        val title: TextView
        val subtitle: TextView?
        if (spec.avatars.isNotEmpty()) {
            val users = Bulletin.UsersLayout(context, hasSubtitle, provider)
            fillAvatars(users, spec.account, spec.avatars)
            layout = users
            title = users.textView
            subtitle = users.subtitleView
        } else if (hasSubtitle) {
            val two = Bulletin.TwoLineLottieLayout(context, provider)
            if (!applyIcon(session, spec, two.imageView, provider)) return
            layout = two
            title = two.titleTextView
            subtitle = two.subtitleTextView
        } else {
            val one = Bulletin.LottieLayout(context, provider)
            if (!applyIcon(session, spec, one.imageView, provider)) return
            one.textView.setSingleLine(false)
            one.textView.maxLines = 2
            layout = one
            title = one.textView
            subtitle = null
        }

        // an emoji whose image is still loading draws blank until the view is told to redraw, and
        // stock arms that on the layouts' titles only - never on a subtitle
        NotificationCenter.listenEmojiLoading(title)
        title.text = PluginText.formatted(spec.text, spec.textEntities, title.paint.fontMetricsInt)
        if (subtitle != null && hasSubtitle) {
            NotificationCenter.listenEmojiLoading(subtitle)
            subtitle.text = PluginText.formatted(spec.subtitle!!, spec.subtitleEntities, subtitle.paint.fontMetricsInt)
        }

        if (spec.button != null) {
            layout.setButton(
                Bulletin.UndoButton(context, true, provider)
                    .setText(spec.button)
                    .setUndoAction { settle("button") },
            )
        }
        layout.setOnClickListener { settle("clicked") }

        val bulletin = factory.create(layout, spec.duration)
        // whatever else happened, the bulletin going away is what ends the wait - and a settle
        // after the first one is dropped, so the button and a tap still win on their own
        bulletin.setOnHideListener { settle("dismissed") }
        if (spec.top == null) bulletin.show() else bulletin.show(spec.top)
    }

    /** `false` when the icon could not be drawn, which leaves the bulletin unshown and the wait to time out on its own */
    private fun applyIcon(
        session: PluginSession,
        spec: BulletinSpec,
        imageView: RLottieImageView,
        provider: Theme.ResourcesProvider?,
    ): Boolean {
        if (spec.iconSpec.isNotEmpty() && spec.iconSpec[0] in "rset") {
            imageView.colorFilter = PluginManifestIcons.tintOf(Theme.getColor(Theme.key_undo_infoColor, provider))
        }
        if (spec.drawable != null) {
            imageView.setImageDrawable(spec.drawable)
            return true
        }
        return PluginIcons.setIcon(imageView, spec.iconSpec, session.engine, if (spec.largeAnimation) 36f else 24f)
    }

    /** stock's own avatar stack: a peer the app does not know is skipped rather than drawn blank */
    private fun fillAvatars(layout: Bulletin.UsersLayout, account: Int, dialogIds: List<Long>) {
        val controller = MessagesController.getInstance(account)
        var count = 0
        for (dialogId in dialogIds) {
            val peer: TLObject? =
                if (dialogId > 0) controller.getUser(dialogId) else controller.getChat(-dialogId)
            if (peer == null) continue
            count++
            layout.avatarsImageView.setCount(count)
            layout.avatarsImageView.setObject(count - 1, account, peer)
        }
        layout.avatarsImageView.commitTransition(false)
        shrinkAvatarSlot(layout, count)
    }

    /**
     * stock reserves the slot for three avatars and starts its text past it, so a stack of one or
     * two leaves a gap the layout was never meant to show
     */
    private fun shrinkAvatarSlot(layout: Bulletin.UsersLayout, count: Int) {
        val reserved = if (count == 0) 0 else AVATAR_SIZE_DP + AVATAR_STEP_DP * (count - 1) + 8
        val shrinkBy = AndroidUtilities.dp((AVATAR_SLOT_DP - reserved).toFloat())
        if (shrinkBy <= 0) return
        val avatars = layout.avatarsImageView
        avatars.visibility = if (count == 0) View.GONE else View.VISIBLE
        avatars.layoutParams = (avatars.layoutParams as FrameLayout.LayoutParams).also {
            it.width = AndroidUtilities.dp(reserved.toFloat())
        }
        // the text sits in a linear layout when there is a subtitle, and in the bulletin itself
        // when there is not
        val holder = (layout.textView.parent as? View)?.takeIf { it !== layout } ?: layout.textView
        holder.layoutParams = (holder.layoutParams as FrameLayout.LayoutParams).also {
            if (LocaleController.isRTL) it.rightMargin -= shrinkBy else it.leftMargin -= shrinkBy
        }
    }

    fun modal(session: PluginSession, op: Int, requestId: Long, optionsJson: String): String? = when (op) {
        OP_BULLETIN -> {
            val spec = try {
                BulletinSpec(session, JSONObject(optionsJson))
            } catch (e: PluginRefusal) {
                return e.wire
            } catch (e: Exception) {
                return PluginWire.encodePluginError("invalid-argument", "bulletin: ${e.message}")
            }
            showModal(
                session,
                "bulletin",
                dismissed = "dismissed",
                resolve = { session.engine.settle(QuickJs.SETTLE_MODAL, requestId, PluginWire.encodeString(it)) },
                prepare = { spec },
            ) { prepared, settle -> showBulletin(session, prepared, settle) }
        }

        OP_DIALOG -> showModal(
            session,
            "dialog",
            dismissed = "dismissed",
            resolve = { session.engine.settle(QuickJs.SETTLE_MODAL, requestId, PluginWire.encodeString(it)) },
            prepare = { JSONObject(optionsJson) },
        ) { options, settle -> showDialog(session.engine, options, settle) }

        OP_PROMPT -> showModal<JSONObject, String?>(
            session,
            "prompt",
            dismissed = null,
            resolve = { session.engine.settle(QuickJs.SETTLE_MODAL, requestId, it?.let(PluginWire::encodeString) ?: PluginWire.encodeNull()) },
            prepare = { JSONObject(optionsJson) },
        ) { options, settle -> showPrompt(options, settle) }

        OP_CHOOSER -> showModal<ChooserSpec, List<Int>?>(
            session,
            "chooser",
            dismissed = null,
            resolve = { picked ->
                val wire = picked?.let { PluginWire.encodeJson(JSONArray(it).toString()) } ?: PluginWire.encodeNull()
                session.engine.settle(QuickJs.SETTLE_MODAL, requestId, wire)
            },
            prepare = { ChooserSpec(JSONObject(optionsJson)) },
        ) { spec, settle -> showChooser(spec, settle) }

        OP_PICK_FILE -> PluginFilePicker.pick(session, requestId, optionsJson)

        OP_SAVE_FILE -> PluginFilePicker.save(session, requestId, optionsJson)

        else -> PluginWire.encodePluginError("internal", "modal: unknown op $op")
    }

    /**
     * every modal is the same shape: read the options (a failure there is the refusal the engine
     * answers the plugin with, before anything is shown), hop to the ui thread, and settle exactly
     * once - the engine drops a second settle, but a promise left hanging is left hanging forever.
     * So [dismissed] is what a [show] that threw answers with, each one handling for itself the
     * case it has no ui to attach to.
     */
    private fun <S, T> showModal(
        session: PluginSession,
        name: String,
        dismissed: T,
        resolve: (T) -> Unit,
        prepare: () -> S,
        show: (S, (T) -> Unit) -> Unit,
    ): String? {
        val prepared = try {
            prepare()
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "$name: ${e.message}")
        }
        AndroidUtilities.runOnUIThread {
            var settled = false
            val settle: (T) -> Unit = { result ->
                if (!settled) {
                    settled = true
                    EngineDispatch.onEngine(session) { resolve(result) }
                }
            }
            try {
                show(prepared, settle)
            } catch (e: Exception) {
                session.log.e("ui", "$name failed", e)
                settle(dismissed)
            }
        }
        return null
    }

    private fun showDialog(engine: QuickJs, options: JSONObject, settle: (String) -> Unit) {
        val activity = LaunchActivity.instance
        if (activity == null || activity.isFinishing) {
            settle("dismissed")
            return
        }
        val builder = AlertDialog.Builder(activity)
        options.formatted("title")?.let(builder::setTitle)
        options.formatted("message")?.let(builder::setMessage)
        // rust already refused every element but `inu.android.nativeView`, which is a jvm
        // handle id; one the plugin has since released simply leaves the dialog bodiless
        options.optJSONObject("body")?.optLong("handle")?.let { handle ->
            (PluginJvm.objectAt(engine, handle) as? View)?.let { builder.setView(it) }
        }
        options.text("positive")?.let { builder.setPositiveButton(it) { _, _ -> settle("positive") } }
        options.text("negative")?.let { builder.setNegativeButton(it) { _, _ -> settle("negative") } }
        options.text("neutral")?.let { builder.setNeutralButton(it) { _, _ -> settle("neutral") } }
        // buttons settle first (their click listeners run before dismissal), so this only
        // catches back-press / outside-tap / activity teardown
        presentModal(LaunchActivity.getSafeLastFragment(), builder.create()) { settle("dismissed") }
    }

    /**
     * BaseFragment.showDialog replaces the dialog's own dismiss listener, and returns null when it
     * refuses to show (mid-transition etc.) - either way a promise would otherwise hang forever
     */
    private fun presentModal(fragment: BaseFragment?, dialog: AlertDialog, onDismiss: () -> Unit) {
        if (fragment?.showDialog(dialog) { onDismiss() } == null) {
            dialog.setOnDismissListener { onDismiss() }
            dialog.show()
        }
    }

    private fun showPrompt(options: JSONObject, settle: (String?) -> Unit) {
        val fragment = LaunchActivity.getSafeLastFragment()
        if (fragment == null) {
            settle(null)
            return
        }
        val dialog = showInputDialog(
            fragment,
            title = options.optString("title"),
            hint = options.text("hint"),
            initialText = options.text("value"),
            selectAll = options.optBoolean("selectAll"),
        ) { text ->
            settle(text)
            true
        }
        if (dialog == null) {
            settle(null)
        } else {
            dialog.setOnDismissListener { settle(null) }
        }
    }

    /** one dialog for both modes, the engine having normalized `selected` into a list */
    private fun showChooser(spec: ChooserSpec, settle: (List<Int>?) -> Unit) {
        val activity = LaunchActivity.instance
        if (activity == null || activity.isFinishing) {
            settle(null)
            return
        }
        val fragment = LaunchActivity.getSafeLastFragment()
        presentModal(fragment, buildChooser(activity, fragment?.resourceProvider, spec, settle)) { settle(null) }
    }

    private class ChooserSpec(options: JSONObject) {
        val title: String? = options.text("title")
        val items: List<ChooserItem> = parseChooserItems(options.getJSONArray("items"))
        val multiple: Boolean = options.optBoolean("multiple")
        val selected: Set<Int> = options.optJSONArray("selected").let { arr ->
            (0 until (arr?.length() ?: 0)).mapTo(HashSet()) { arr!!.getInt(it) }
        }
    }

    private class ChooserItem(val text: String, val subtitle: String?, val danger: Boolean)

    private fun parseChooserItems(arr: JSONArray): List<ChooserItem> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChooserItem(
                o.getString("text"),
                o.text("subtitle"),
                o.optBoolean("danger"),
            )
        }

    /** danger is a red text colour rather than a cell flag: no stock list cell exposes one */
    private fun chooserLabel(item: ChooserItem, theme: Theme.ResourcesProvider?): CharSequence {
        if (!item.danger) return item.text
        val text = SpannableString(item.text)
        text.setSpan(
            ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular, theme)),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return text
    }

    private fun buildChooser(
        context: Context,
        theme: Theme.ResourcesProvider?,
        spec: ChooserSpec,
        settle: (List<Int>?) -> Unit,
    ): AlertDialog {
        val picked = spec.selected.toMutableSet()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val setters = spec.items.mapIndexed { index, item ->
            val label = chooserLabel(item, theme)
            val (cell, setChecked) = if (spec.multiple) {
                val cell = CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, 21, theme)
                cell.setText(label, item.subtitle.orEmpty(), index in picked, false)
                cell to { checked: Boolean -> cell.setChecked(checked, true) }
            } else {
                val cell = RadioColorCell(context, theme)
                cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
                cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground, theme),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, theme),
                )
                if (item.subtitle == null) {
                    cell.setTextAndValue(label, index in picked)
                } else {
                    cell.setTextAndText2AndValue(label, item.subtitle, index in picked)
                }
                cell to { checked: Boolean -> cell.setChecked(checked, true) }
            }
            cell.background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, theme),
                Theme.RIPPLE_MASK_ALL,
            )
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, if (spec.multiple) 50 else LayoutHelper.WRAP_CONTENT))
            setChecked
        }
        val builder = AlertDialog.Builder(context, theme)
        spec.title?.let { builder.setTitle(it) }
        builder.setView(container)
        builder.setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ -> settle(picked.sorted()) }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        val dialog = builder.create()
        val updateSubmit = { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = spec.multiple || picked.isNotEmpty() }
        dialog.setOnShowListener { updateSubmit() }
        setters.forEachIndexed { index, setChecked ->
            container.getChildAt(index).setOnClickListener {
                if (spec.multiple) {
                    if (index in picked) picked.remove(index) else picked.add(index)
                    setChecked(index in picked)
                } else if (index !in picked) {
                    picked.forEach { setters[it](false) }
                    picked.clear()
                    picked.add(index)
                    setChecked(true)
                }
                updateSubmit()
            }
        }
        return dialog
    }

    fun openRegisteredSettings(plugin: Plugin) {
        EngineDispatch.scheduler.postRunnable {
            val session = plugin.session ?: return@postRunnable
            val pageId = session.settingsPageId ?: return@postRunnable
            val err = openPage(session, pageId)
            if (err != null) session.log.e("ui", "openRegisteredSettings: $err")
        }
    }
}
