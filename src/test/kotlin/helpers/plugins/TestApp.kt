package desu.inugram.helpers.plugins

import androidx.collection.LongSparseArray
import java.io.File
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/** writes through stock accessors where one exists: `putUsers` also indexes usernames and merges min entities */
object TestApp {
    /** skips the constructor of a stock singleton that opens sockets or starts threads */
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
            controller.dialogMessagesByIds.clear()
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
        // eagerly, or a transfer started before first use would reach stock's real loader
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

    /** stock `setCurrentUser` saves to prefs and posts notifications */
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

    /** fills both views stock keeps of dialog messages, as `loadDialogs` does */
    fun cacheDialogMessage(account: Int, dialogId: Long, message: TLRPC.Message) {
        touch(account)
        val controller = MessagesController.getInstance(account)
        val cached = MessageObject(account, message, false, false)
        controller.dialogMessage.put(dialogId, arrayListOf(cached))
        controller.dialogMessagesByIds.put(message.id, cached)
    }

    /** stock `saveDraft` goes to the database and the network */
    @Suppress("UNCHECKED_CAST")
    fun putDraft(dialogId: Long, threadId: Long, draft: TLRPC.DraftMessage, account: Int = 0) {
        touch(account)
        val controller = MediaDataController.getInstance(account)
        val field = MediaDataController::class.java.getDeclaredField("drafts").apply { isAccessible = true }
        val drafts = field.get(controller) as LongSparseArray<LongSparseArray<TLRPC.DraftMessage>>
        val byThread = drafts.get(dialogId) ?: LongSparseArray<TLRPC.DraftMessage>().also { drafts.put(dialogId, it) }
        byThread.put(threadId, draft)
    }

    /** not installed into `MessagesController.Instance`, so other reads reach stock's own */
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

class RecordingFileLoader : FileLoader {
    class Load(val what: Any?, val parent: Any?)

    private constructor() : super(0)

    // [TestApp.allocate] runs no constructor, so initializers never run
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
