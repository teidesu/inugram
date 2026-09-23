package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.SessionResource
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import desu.inugram.helpers.plugins.telegram.PeerSpecs
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

    // keyed per engine so page ids can't cross plugins. ui thread only
    private data class PageKey(val session: PluginSession, val pageId: Long)
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

        override fun uiOpenPage(pageId: Long): String? {
            openPage(session, pageId)
            return null
        }

        override fun uiOpenFragment(handle: Long): String? = openFragment(session.engine, handle)

        override fun uiOpenScreen(optionsJson: String): String? = openScreen(optionsJson)

        override fun uiRegisterSettings(pageId: Long) = onHost { registerSettings(session, pageId) }

        override fun uiUnregisterSettings(pageId: Long) = onHost { unregisterSettings(session, pageId) }

        override fun uiInvalidate(pageId: Long) = invalidate(session, pageId)

        override fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? =
            openMenu(session, menuId, pageId, anchorKey, itemsJson)

        override fun iconResolves(kind: Int, value: String): Boolean = PluginIcons.iconResolves(kind, value)

        override fun commonIcon(name: String): String? = PluginIcons.commonIconName(name)

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

    /** the key goes before the fragment, so teardown does not notify the closed engine */
    override fun detach(session: PluginSession) {
        AndroidUtilities.runOnUIThread {
            val mine = openPages.filterKeys { it.session === session }
            for ((key, list) in mine) {
                openPages.remove(key)
                // not finishFragment(), which closes whatever is on top
                for (activity in list.toList()) activity.removeSelfFromStack()
            }
        }
    }

    fun openPage(session: PluginSession, pageId: Long) {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            fragment.presentFragment(PluginSettingsActivity(session, pageId))
        }
    }

    /** resolved before the ui hop, so a non-`BaseFragment` throws where the plugin can catch it */
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
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PluginWire.encodePluginError("not-found", "openPage: account #$accountId is not logged in")
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

    /** disposing a page the plugin already replaced must not clear it */
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

    /** the row's view may be recycled after a re-render, so a row off screen settles as dismissed */
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
            // any `checked` (even false) makes the menu radio-style, aligning every row on the checkmark column
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

    /** read on the plugin queue so an unresolvable icon throws instead of never showing */
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
        /** the avatars' account is not always the current one */
        val account: Int = options.optInt("account", UserConfig.selectedAccount)
        val top: Boolean? = if (options.has("top")) options.getBoolean("top") else null
        val button: String? = if (options.has("button")) options.getString("button") else null

        val animation = PluginIcons.parseAnimationSpec(iconSpec).takeIf { iconSpec.startsWith('a') }

        val drawable = if (iconSpec.startsWith('j')) {
            PluginIcons.resolveImmediateDrawable(ApplicationLoader.applicationContext, iconSpec, session.engine)
                ?: refuse("handle-expired", "bulletin: drawable icon is gone")
        } else {
            null
        }

        init {
            val animationName = animation?.value
            if (animationName != null && !PluginIcons.iconResolves(PluginIcons.KIND_RAW_ANIMATION, animationName)) {
                refuse("not-found", "bulletin: animation '$animationName' is unavailable")
            }
        }

        val largeAnimation: Boolean
            get() = animation != null && !animation.isStatic && (animation.repeatCount == 0 || animation.repeatCount == null)
    }

    /** settled once, by the button, a body tap, or the bulletin hiding */
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

        // stock arms emoji-loading redraws on layout titles only, never on a subtitle
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
        // a settle after the first is dropped, so button and tap still win
        bulletin.setOnHideListener { settle("dismissed") }
        if (spec.top == null) bulletin.show() else bulletin.show(spec.top)
    }

    /** `false` leaves the bulletin unshown and the wait to time out */
    private fun applyIcon(
        session: PluginSession,
        spec: BulletinSpec,
        imageView: RLottieImageView,
        provider: Theme.ResourcesProvider?,
    ): Boolean {
        if (spec.iconSpec.isNotEmpty() && spec.iconSpec[0] in "rset") {
            imageView.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_undo_infoColor, provider), PorterDuff.Mode.SRC_IN)
        }
        if (spec.drawable != null) {
            imageView.setImageDrawable(spec.drawable)
            return true
        }
        return PluginIcons.setIcon(imageView, spec.iconSpec, session.engine, if (spec.largeAnimation) 36f else 24f)
    }

    /** a peer the app does not know is skipped rather than drawn blank */
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

    /** stock reserves a slot for three avatars and starts text past it */
    private fun shrinkAvatarSlot(layout: Bulletin.UsersLayout, count: Int) {
        val reserved = if (count == 0) 0 else AVATAR_SIZE_DP + AVATAR_STEP_DP * (count - 1) + 8
        val shrinkBy = AndroidUtilities.dp((AVATAR_SLOT_DP - reserved).toFloat())
        if (shrinkBy <= 0) return
        val avatars = layout.avatarsImageView
        avatars.visibility = if (count == 0) View.GONE else View.VISIBLE
        avatars.layoutParams = (avatars.layoutParams as FrameLayout.LayoutParams).also {
            it.width = AndroidUtilities.dp(reserved.toFloat())
        }
        val holder = (layout.textView.parent as? View)?.takeIf { it !== layout } ?: layout.textView
        holder.layoutParams = (holder.layoutParams as FrameLayout.LayoutParams).also {
            if (LocaleController.isRTL) it.rightMargin -= shrinkBy else it.leftMargin -= shrinkBy
        }
    }

    fun modal(session: PluginSession, op: Int, requestId: Long, optionsJson: String): String? {
        val resolveString: (String) -> Unit = { session.engine.settle(QuickJs.SETTLE_MODAL, requestId, PluginWire.encodeString(it)) }
        return when (op) {
            OP_BULLETIN -> showModal(
                session,
                "bulletin",
                dismissed = "dismissed",
                resolve = resolveString,
                prepare = { BulletinSpec(session, JSONObject(optionsJson)) },
            ) { spec, settle -> showBulletin(session, spec, settle) }

            OP_DIALOG -> showModal(
                session,
                "dialog",
                dismissed = "dismissed",
                resolve = resolveString,
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
    }

    /**
     * options failing to parse is the refusal, before anything shows. Must settle exactly once: the engine
     * drops a second settle, but a missing one hangs forever, so [dismissed] answers a [show] that threw.
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
        } catch (e: PluginRefusal) {
            return e.wire
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
        // rust already refused everything but a `nativeView` jvm handle; a released one leaves the dialog bodiless
        options.optJSONObject("body")?.optLong("handle")?.let { handle ->
            (PluginJvm.objectAt(engine, handle) as? View)?.let { builder.setView(it) }
        }
        options.text("positive")?.let { builder.setPositiveButton(it) { _, _ -> settle("positive") } }
        options.text("negative")?.let { builder.setNegativeButton(it) { _, _ -> settle("negative") } }
        options.text("neutral")?.let { builder.setNeutralButton(it) { _, _ -> settle("neutral") } }
        // button clicks settle before dismissal, so this catches back-press, outside tap and teardown
        presentModal(LaunchActivity.getSafeLastFragment(), builder.create()) { settle("dismissed") }
    }

    /** BaseFragment.showDialog replaces the dismiss listener and returns null when it refuses (mid-transition) */
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

    /** no stock list cell exposes a danger flag */
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
            openPage(session, pageId)
        }
    }
}
