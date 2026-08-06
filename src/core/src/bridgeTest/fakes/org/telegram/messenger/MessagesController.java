package org.telegram.messenger;

import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * The entity/dialog/message caches the `Account` read surface reads, with stock's own lookup rules
 * kept: `getUser(0)` answers with the logged-in user, `getInputPeer` fills in a zero access_hash for
 * a peer it does not know, and `getInputChannel` answers `TL_inputChannelEmpty` for a basic group.
 * Those three are exactly what `PluginReads` has to work around, so a stub without them tests nothing.
 */
public class MessagesController {
    private static final MessagesController[] instances = new MessagesController[8];

    public final List<TLRPC.Updates> processed = new ArrayList<>();

    public final ConcurrentHashMap<Long, TLRPC.User> users = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, TLRPC.Chat> chats = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, TLObject> objectsByUsernames = new ConcurrentHashMap<>();
    public LongSparseArray<TLRPC.Dialog> dialogs_dict = new LongSparseArray<>();
    public LongSparseArray<ArrayList<MessageObject>> dialogMessage = new LongSparseArray<>();
    public final LongSparseArray<TLRPC.UserFull> fullUsers = new LongSparseArray<>();
    public final LongSparseArray<TLRPC.ChatFull> fullChats = new LongSparseArray<>();

    private int currentAccount;

    public static MessagesController getInstance(int num) {
        if (instances[num] == null) {
            instances[num] = new MessagesController();
            instances[num].currentAccount = num;
        }
        return instances[num];
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
    }

    // the stock hook, verbatim: an interceptUpdate chain hands the batch back through here, and a
    // fake that skipped the hook would never fan the arrival out to the observers
    public void processUpdates(TLRPC.Updates updates, boolean fromQueue) {
        if (desu.inugram.helpers.plugins.tg.PluginRpc.onUpdates(this, updates, currentAccount, fromQueue)) {
            return;
        }
        processed.add(updates);
    }

    public TLRPC.User getUser(Long id) {
        if (id == 0) {
            return UserConfig.getInstance(currentAccount).getCurrentUser();
        }
        return users.get(id);
    }

    public TLRPC.Chat getChat(Long id) {
        return chats.get(id);
    }

    public TLObject getUserOrChat(long dialogId) {
        if (users.containsKey(dialogId)) return users.get(dialogId);
        if (chats.containsKey(-dialogId)) return chats.get(-dialogId);
        return null;
    }

    public TLObject getUserOrChat(String username) {
        if (username == null || username.length() == 0) return null;
        return objectsByUsernames.get(username.toLowerCase());
    }

    public void putUsers(ArrayList<TLRPC.User> users, boolean fromCache) {
        if (users == null) return;
        for (TLRPC.User user : users) inu_putUser(user);
    }

    public void putChats(ArrayList<TLRPC.Chat> chats, boolean fromCache) {
        if (chats == null) return;
        for (TLRPC.Chat chat : chats) inu_putChat(chat);
    }

    public void inu_putUser(TLRPC.User user) {
        users.put(user.id, user);
        if (user.username != null) objectsByUsernames.put(user.username.toLowerCase(), user);
    }

    public void inu_putChat(TLRPC.Chat chat) {
        chats.put(chat.id, chat);
        if (chat.username != null) objectsByUsernames.put(chat.username.toLowerCase(), chat);
    }

    public TLRPC.UserFull getUserFull(long uid) {
        return fullUsers.get(uid);
    }

    public TLRPC.ChatFull getChatFull(long chatId) {
        return fullChats.get(chatId);
    }

    public TLRPC.InputUser getInputUser(long userId) {
        TLRPC.User user = getUser(userId);
        if (user == null) return new TLRPC.TL_inputUserEmpty();
        if (user.id == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return new TLRPC.TL_inputUserSelf();
        }
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = user.id;
        inputUser.access_hash = user.access_hash;
        return inputUser;
    }

    public TLRPC.InputChannel getInputChannel(long chatId) {
        TLRPC.Chat chat = getChat(chatId);
        if (chat == null || !chat.broadcast && !chat.megagroup) return new TLRPC.TL_inputChannelEmpty();
        if (isMin(chat)) {
            TLRPC.TL_inputChannelFromMessage fromMessage = new TLRPC.TL_inputChannelFromMessage();
            fromMessage.channel_id = chat.id;
            fromMessage.peer = getInputPeer(chat.fromMessageDialogId);
            fromMessage.msg_id = chat.fromMessageId;
            return fromMessage;
        }
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        return inputChannel;
    }

    /**
     * stock's shape for a channel it only ever saw referenced from someone else's message: no
     * access_hash of its own, addressed through the message it was seen in. What makes it worth
     * modelling here is that the peer it produces is a *sibling* of TL_inputPeerChannel, not a
     * subclass, so an `instanceof TL_inputPeerChannel` discriminator walks straight past it.
     */
    private boolean isMin(TLRPC.Chat chat) {
        return chat.access_hash == 0 && chat.fromMessageDialogId != 0 && chat.fromMessageId != 0;
    }

    public TLRPC.InputPeer getInputPeer(long id) {
        if (id == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return new TLRPC.TL_inputPeerSelf();
        }
        if (id < 0) {
            TLRPC.Chat chat = getChat(-id);
            if (chat != null && (chat.broadcast || chat.megagroup)) {
                if (isMin(chat)) {
                    TLRPC.TL_inputPeerChannelFromMessage fromMessage = new TLRPC.TL_inputPeerChannelFromMessage();
                    fromMessage.channel_id = -id;
                    fromMessage.peer = getInputPeer(chat.fromMessageDialogId);
                    fromMessage.msg_id = chat.fromMessageId;
                    return fromMessage;
                }
                TLRPC.TL_inputPeerChannel inputPeer = new TLRPC.TL_inputPeerChannel();
                inputPeer.channel_id = -id;
                inputPeer.access_hash = chat.access_hash;
                return inputPeer;
            }
            TLRPC.TL_inputPeerChat inputPeer = new TLRPC.TL_inputPeerChat();
            inputPeer.chat_id = -id;
            return inputPeer;
        }
        TLRPC.User user = getUser(id);
        TLRPC.TL_inputPeerUser inputPeer = new TLRPC.TL_inputPeerUser();
        inputPeer.user_id = id;
        if (user != null) inputPeer.access_hash = user.access_hash;
        return inputPeer;
    }
}
