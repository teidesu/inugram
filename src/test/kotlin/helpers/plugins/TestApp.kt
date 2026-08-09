package desu.inugram.helpers.plugins

import androidx.collection.LongSparseArray
import java.io.File
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/**
 * Seeds the app's own caches, and the one transfer engine that has to be a recorder.
 *
 * Everything here writes through stock's real accessors where one exists (`putUsers`, `putChat`),
 * because those are what the bridge is written around: `putUsers` does the username indexing and the
 * min-entity merging that a two-line stand-in silently would not. Reflection is only for the caches
 * stock has no setter for.
 */
object TestApp {
    /**
     * allocates without running a constructor, for a stock singleton whose real one opens sockets or
     * starts threads. Every field it would have set stays at the zero value, so only overridden
     * members may be called.
     */
    fun <T> allocate(cls: Class<T>, account: Int): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        val instance = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls) as T
        if (account != 0) {
            org.telegram.messenger.BaseController::class.java.getDeclaredField("currentAccount")
                .apply { isAccessible = true }
                .setInt(instance, account)
        }
        return instance
    }

    private fun instanceArray(owner: Class<*>): Array<Any?> {
        @Suppress("UNCHECKED_CAST")
        return owner.getDeclaredField("Instance").apply { isAccessible = true }.get(null) as Array<Any?>
    }

    private val originalLoaders = HashMap<Int, Any?>()
    private val updateControllers = HashMap<Int, RecordingMessagesController>()
    private val loaders = HashMap<Int, RecordingFileLoader>()
    private val touchedAccounts = HashSet<Int>()

    fun reset() {
        for (account in touchedAccounts) {
            val controller = MessagesController.getInstance(account)
            controller.dialogs_dict.clear()
            controller.dialogMessage.clear()
            clearMap(controller, "users")
            clearMap(controller, "chats")
            clearMap(controller, "objectsByUsernames")
            clearSparse(controller, "fullUsers")
            clearSparse(controller, "fullChats")
            clearSparse(MediaDataController.getInstance(account), "drafts")
            currentUserField().set(UserConfig.getInstance(account), null)
        }
        touchedAccounts.clear()
        loaders.clear()
        updateControllers.clear()
        // eagerly, for the reason `RecordingConnectionsManager.reset` is eager: a transfer started
        // before a test first asked for the loader would be started by the app's real one
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) fileLoader(account)
    }

    private fun clearMap(owner: Any, name: String) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        (field.get(owner) as MutableMap<*, *>).clear()
    }

    private fun clearSparse(owner: Any, name: String) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        (field.get(owner) as LongSparseArray<*>).clear()
    }

    private fun currentUserField() =
        UserConfig::class.java.getDeclaredField("currentUser").apply { isAccessible = true }

    private fun touch(account: Int) = touchedAccounts.add(account)

    /**
     * the slot's logged-in user. Assigned rather than passed to `setCurrentUser`, which saves to
     * prefs and posts on the notification centre; what the bridge reads is the field.
     */
    fun signIn(account: Int = 0, id: Long = 100L + account): TLRPC.User {
        touch(account)
        val user = TLRPC.TL_user().apply {
            this.id = id
            access_hash = 1000L + id
        }
        currentUserField().set(UserConfig.getInstance(account), user)
        putUser(user, account)
        return user
    }

    /** [signIn] with a user the test built itself */
    fun signInAs(account: Int, user: TLRPC.User) {
        touch(account)
        currentUserField().set(UserConfig.getInstance(account), user)
        putUser(user, account)
    }

    fun putUser(user: TLRPC.User, account: Int = 0) {
        touch(account)
        MessagesController.getInstance(account).putUsers(arrayListOf(user), false)
    }

    fun putChat(chat: TLRPC.Chat, account: Int = 0) {
        touch(account)
        MessagesController.getInstance(account).putChat(chat, false)
    }

    @Suppress("UNCHECKED_CAST")
    fun putUserFull(userId: Long, full: TLRPC.UserFull, account: Int = 0) {
        touch(account)
        val controller = MessagesController.getInstance(account)
        val field = MessagesController::class.java.getDeclaredField("fullUsers").apply { isAccessible = true }
        (field.get(controller) as LongSparseArray<TLRPC.UserFull>).put(userId, full)
    }

    @Suppress("UNCHECKED_CAST")
    fun putChatFull(chatId: Long, full: TLRPC.ChatFull, account: Int = 0) {
        touch(account)
        val controller = MessagesController.getInstance(account)
        val field = MessagesController::class.java.getDeclaredField("fullChats").apply { isAccessible = true }
        (field.get(controller) as LongSparseArray<TLRPC.ChatFull>).put(chatId, full)
    }

    fun putDialog(dialog: TLRPC.Dialog, account: Int = 0) {
        touch(account)
        MessagesController.getInstance(account).dialogs_dict.put(dialog.id, dialog)
    }

    /** stock's own `saveDraft` goes to the database and the network; the cache is what is read */
    @Suppress("UNCHECKED_CAST")
    fun putDraft(dialogId: Long, threadId: Long, draft: TLRPC.DraftMessage, account: Int = 0) {
        touch(account)
        val controller = MediaDataController.getInstance(account)
        val field = MediaDataController::class.java.getDeclaredField("drafts").apply { isAccessible = true }
        val drafts = field.get(controller) as LongSparseArray<LongSparseArray<TLRPC.DraftMessage>>
        val byThread = drafts.get(dialogId) ?: LongSparseArray<TLRPC.DraftMessage>().also { drafts.put(dialogId, it) }
        byThread.put(threadId, draft)
    }

    /**
     * the controller a test hands `PluginUpdates.onUpdates`, which is the only use it has for
     * one. Deliberately *not* installed into `MessagesController.Instance`: every other read on the
     * update path is meant to reach the app's own.
     */
    fun updatesController(account: Int = 0): RecordingMessagesController =
        updateControllers.getOrPut(account) { allocate(RecordingMessagesController::class.java, account) }

    fun fileLoader(account: Int = 0): RecordingFileLoader = loaders.getOrPut(account) {
        val recorder = allocate(RecordingFileLoader::class.java, account)
        val array = instanceArray(FileLoader::class.java)
        if (!originalLoaders.containsKey(account)) originalLoaders[account] = array[account]
        array[account] = recorder
        recorder
    }
}

/**
 * Nothing here transfers: a test posts the [org.telegram.messenger.NotificationCenter] events the
 * real loader would, which is how a download that failed, one already on disk, and one reporting
 * progress are each written. [paths] is what `getPathToMessage` answers, so a test decides whether
 * the file exists.
 */
class RecordingFileLoader : FileLoader {
    class Load(val what: Any?, val parent: Any?)

    private constructor() : super(0)

    // built on first read: [TestApp.allocate] runs no constructor, so no initializer here runs
    private var loadList: ArrayList<Load>? = null
    private var uploadList: ArrayList<String>? = null
    private var pathMap: HashMap<Int, File>? = null

    val loads: ArrayList<Load> get() = loadList ?: ArrayList<Load>().also { loadList = it }

    val uploads: ArrayList<String> get() = uploadList ?: ArrayList<String>().also { uploadList = it }

    val paths: HashMap<Int, File> get() = pathMap ?: HashMap<Int, File>().also { pathMap = it }

    override fun loadFile(document: TLRPC.Document?, parentObject: Any?, priority: Int, cacheType: Int) {
        loads.add(Load(document, parentObject))
    }

    override fun loadFile(imageLocation: ImageLocation?, parentObject: Any?, ext: String?, priority: Int, cacheType: Int) {
        loads.add(Load(imageLocation, parentObject))
    }

    override fun uploadFile(location: String, encrypted: Boolean, small: Boolean, type: Int) {
        uploads.add(location)
    }

    override fun getPathToMessage(message: TLRPC.Message): File? = paths[message.id]

    override fun getPathToMessage(message: TLRPC.Message, useFileDatabaseQueue: Boolean): File? = paths[message.id]
}
