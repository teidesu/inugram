package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.core.plugins.GrantTier
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginRepo
import desu.inugram.helpers.plugins.PluginRepos
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.theme.M3SectionsHelper
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.LaunchActivity

class PluginReposActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {
    private val rows = HashMap<String, PluginRepoRow>()
    private val repoRows = HashMap<Long, RepoRow>()

    /** null while checking */
    private var check: PluginRepos.UpdateCheck? = null
    private var checkGeneration = 0

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPluginRepos)

    private val onPluginsChanged: () -> Unit = { checkUpdates() }

    override fun onFragmentCreate(): Boolean {
        notificationCenter.addObserver(this, NotificationCenter.chatInfoDidLoad)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        notificationCenter.removeObserver(this, NotificationCenter.chatInfoDidLoad)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val chatId = (args.firstOrNull() as? TLRPC.ChatFull)?.id ?: return
        if (PluginRepos.getRepos().any { it.channelId == chatId }) listView?.adapter?.update(true)
    }

    override fun onResume() {
        super.onResume()
        PluginManager.ensureLoaded()
        PluginManager.addOnChangedListener(onPluginsChanged)
        checkUpdates()
        loadChannels()
    }

    override fun onPause() {
        super.onPause()
        PluginManager.removeOnChangedListener(onPluginsChanged)
    }

    private fun loadChannels() {
        for (repo in PluginRepos.getRepos()) {
            PluginRepos.loadChat(currentAccount, repo) { chat ->
                if (chat == null) return@loadChat
                listView?.adapter?.update(true)
                messagesController.loadFullChat(chat.id, classGuid, false)
            }
        }
    }

    private fun checkUpdates() {
        val generation = ++checkGeneration
        check = null
        listView?.adapter?.update(true)
        PluginRepos.findUpdates(currentAccount) { result ->
            if (generation != checkGeneration) return@findUpdates
            check = result
            listView?.adapter?.update(true)
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val check = check
        val updates = check?.updates.orEmpty()
        if (updates.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginReposUpdates)))
            for (update in updates) {
                val row = rows.getOrPut(update.plugin.id) { PluginRepoRow(context) }
                val meta = "${update.plugin.manifest.version ?: "?"} → ${update.manifest.version ?: "?"}"
                row.bind(
                    update.manifest,
                    meta,
                    LocaleController.getString(R.string.InuPluginRepoUpdate),
                    active = true,
                    onUsernameClick = { messagesController.openByUserName(it, this, 0) },
                ) {
                    installRepoPost(this, update.post)
                }
                items.add(UItem.asCustom(row).apply { id = ITEM_BASE + (update.plugin.id.hashCode() and 0xffff) })
            }
        }
        items.add(
            UItem.asShadow(
                when {
                    check == null -> LocaleController.getString(R.string.InuPluginReposChecking)
                    check.failedChannelIds.isNotEmpty() -> LocaleController.getString(R.string.InuPluginReposCheckFailed)
                    updates.isEmpty() -> LocaleController.getString(R.string.InuPluginReposUpToDate)
                    else -> null
                }
            )
        )

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginRepos)))
        val classic = !M3SectionsHelper.isEnabled()
        for (repo in PluginRepos.getRepos()) {
            val row = repoRows.getOrPut(repo.channelId) { RepoRow(context) }
            row.bind(
                repo,
                messagesController.getChat(repo.channelId),
                messagesController.getChatFull(repo.channelId)?.about,
                failed = check?.failedChannelIds?.contains(repo.channelId) == true,
                onOpen = { presentFragment(PluginRepoActivity(repo)) },
                onOpenChannel = { messagesController.openByUserName(repo.username, this, 0) },
                onUsernameClick = { messagesController.openByUserName(it, this, 0) },
                onRemove = {
                    PluginRepos.removeRepo(repo)
                    repoRows.remove(repo.channelId)
                    checkUpdates()
                },
            )
            row.setDivider(classic)
            items.add(UItem.asCustom(row).apply { id = REPO_BASE + (repo.channelId.hashCode() and 0xffff) })
        }
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuPluginReposAdd)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginReposInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) showAddDialog()
    }

    private fun showAddDialog() {
        val ctx = context ?: parentActivity ?: return
        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        val clipText = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        // a bare word parses as a username too, so only text with a link or `@` prefix counts
        val prefill = clipText.takeIf { PluginRepos.parseUsername(it).let { name -> name != null && name != it } }
        showInputDialog(
            this,
            LocaleController.getString(R.string.InuPluginReposAdd),
            hint = "@channel",
            initialText = prefill,
            selectAll = true,
        ) { input ->
            if (PluginRepos.parseUsername(input) == null) return@showInputDialog false
            PluginRepos.addRepo(currentAccount, input) { error ->
                if (error != null) {
                    BulletinFactory.of(this).createErrorBulletin(error).show()
                } else {
                    checkUpdates()
                    loadChannels()
                }
            }
            true
        }
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ITEM_BASE = 20000
        private const val REPO_BASE = 90000

        /** `tg://inurepo?domain=<channel>` */
        @JvmStatic
        fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
            if (ParanoiaHelper.shouldHideSettings()) return false
            val uri = intent?.data ?: return false
            if (uri.scheme != "tg" || uri.host != "inurepo") return false
            val fragment = activity.actionBarLayout?.lastFragment ?: return false
            val username = uri.getQueryParameter("domain")?.let { PluginRepos.parseUsername(it) } ?: return false
            PluginRepos.getRepos().firstOrNull { it.username.equals(username, ignoreCase = true) }?.let {
                fragment.presentFragment(PluginRepoActivity(it))
                return true
            }
            val dialog = AlertDialog.Builder(activity, fragment.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuPluginReposAdd))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.InuPluginReposAddConfirm, "@$username")))
                .setPositiveButton(LocaleController.getString(R.string.Add)) { _, _ ->
                    PluginRepos.addRepo(fragment.currentAccount, username) { error ->
                        if (error != null) {
                            BulletinFactory.of(fragment).createErrorBulletin(error).show()
                            return@addRepo
                        }
                        PluginRepos.getRepos().lastOrNull()?.let { fragment.presentFragment(PluginRepoActivity(it)) }
                    }
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
            fragment.showDialog(dialog)
            return true
        }
    }
}

/** the normal-view plugin row, minus the switch and reordering */
@SuppressLint("ViewConstructor")
private class RepoRow(context: Context) : LinearLayout(context) {
    private val avatar = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(16f)) }
    private val avatarDrawable = AvatarDrawable()
    private val title = RowTitle(context)
    private val subtitle = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 13f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val description = LinkSpanDrawable.LinksTextView(context).apply {
        setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 14f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }
    private val actions = RowActions(context)
    private val openChannelAction = actions.addAction(R.string.InuPluginReposOpenChannel, red = false)
    private val removeAction = actions.addAction(R.string.InuPluginsRemove, red = true)
    private var needDivider = false

    init {
        orientation = VERTICAL
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        val rtl = LocaleController.isRTL
        val gutter = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        gutter.addView(avatar, LayoutHelper.createLinear(32, 32, Gravity.CENTER_HORIZONTAL, 0f, 4f, 0f, 0f))

        val content = LinearLayout(context).apply { orientation = VERTICAL }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, if (rtl) 22 else 0, 0, if (rtl) 0 else 22, 0))
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, if (rtl) 22 else 0, 2, if (rtl) 0 else 22, 0))
        content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, if (rtl) 22 else 0, 4, if (rtl) 0 else 22, 0))

        addView(
            RowBody(context, gutter, content, actions),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 10f),
        )
    }

    fun bind(
        repo: PluginRepo,
        chat: TLRPC.Chat?,
        about: String?,
        failed: Boolean,
        onOpen: () -> Unit,
        onOpenChannel: () -> Unit,
        onUsernameClick: (String) -> Unit,
        onRemove: () -> Unit,
    ) {
        if (chat != null) {
            avatarDrawable.setInfo(chat)
            avatar.setForUserOrChat(chat, avatarDrawable)
        } else {
            avatarDrawable.setInfo(repo.channelId, repo.title, null)
            avatar.setImageDrawable(avatarDrawable)
        }
        title.text = chat?.title ?: repo.title
        title.setWarning(if (failed) tierColors(GrantTier.CAUTION).first else null)
        subtitle.text = "@${repo.username}"
        description.text = if (about.isNullOrEmpty()) "" else InuUtils.linkUsernames(about, onUsernameClick)
        description.visibility = if (about.isNullOrEmpty()) GONE else VISIBLE
        setOnClickListener { onOpen() }
        openChannelAction.setOnClickListener { onOpenChannel() }
        removeAction.setOnClickListener { onRemove() }
    }

    /** classic-sections only: md3 cards separate rows by shape instead */
    fun setDivider(value: Boolean) {
        if (needDivider == value) return
        needDivider = value
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (needDivider) drawRowDivider(canvas, DIVIDER_INSET_DP)
    }

    companion object {
        private const val DIVIDER_INSET_DP = 60f
    }
}
