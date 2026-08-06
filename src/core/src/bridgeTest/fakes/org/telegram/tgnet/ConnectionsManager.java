package org.telegram.tgnet;

import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.Utilities;

/**
 * recording double. `sendRequestInternal` never answers on its own - a test answers the send it
 * wants to, which is how a passthrough that races a cancel or a CONNECTION_NOT_INITED retry is
 * written.
 */
public class ConnectionsManager {
    public static class Sent {
        public final TLObject request;
        public final RequestDelegate onComplete;
        public final RequestDelegateTimestamp onCompleteTimestamp;
        public final int requestToken;

        Sent(TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, int requestToken) {
            this.request = request;
            this.onComplete = onComplete;
            this.onCompleteTimestamp = onCompleteTimestamp;
            this.requestToken = requestToken;
        }

        /** answers the delegate the way stock's own tail does, on stageQueue */
        public void answer(TLObject response, TLRPC.TL_error error, long responseTime) {
            Utilities.stageQueue.postRunnable(() -> {
                if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(response, error, responseTime);
                } else if (onComplete != null) {
                    onComplete.run(response, error);
                }
                if (response != null) response.freeResources();
            });
        }
    }

    public static class Cancel {
        public final int token;
        public final boolean notifyServer;
        public final Runnable onCancelled;

        Cancel(int token, boolean notifyServer, Runnable onCancelled) {
            this.token = token;
            this.notifyServer = notifyServer;
            this.onCancelled = onCancelled;
        }
    }

    public static class GuidBind {
        public final int account;
        public final int requestToken;
        public final int guid;

        GuidBind(int account, int requestToken, int guid) {
            this.account = account;
            this.requestToken = requestToken;
            this.guid = guid;
        }
    }

    private static final ConnectionsManager[] instances = new ConnectionsManager[8];
    public static final List<GuidBind> guidBinds = new ArrayList<>();

    public final List<Sent> sent = new ArrayList<>();
    public final List<Cancel> cancels = new ArrayList<>();
    public long inu_currentTimeMillis = 1_700_000_000_000L;
    private int lastRequestToken = 0;

    public final int currentAccount;

    public ConnectionsManager(int account) {
        this.currentAccount = account;
    }

    public static ConnectionsManager getInstance(int account) {
        if (instances[account] == null) instances[account] = new ConnectionsManager(account);
        return instances[account];
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
        guidBinds.clear();
    }

    public long getCurrentTimeMillis() {
        return inu_currentTimeMillis;
    }

    public static final int RequestFlagFailOnServerErrors = 2;
    public static final int FileTypeFile = 0x04000000;

    public int sendRequest(TLObject object, RequestDelegate onComplete) {
        return sendRequest(object, onComplete, 0);
    }

    /**
     * routed through `sendRequestInternal`, i.e. through the interceptor hook, exactly as stock's
     * own `sendRequest` is: anything a plugin sends is kept out of the chains by the bypass lease
     * and by nothing else, so a fake that skipped the hook would make that rule untestable.
     *
     * Stock posts the internal call to stageQueue and answers the token first; here it is
     * synchronous, so a test can read `lastSent()` without draining. What the hop buys - a cancel
     * arriving before the send it names - is `sendRequestInternal`'s own to exercise, and the tests
     * that do call it directly.
     */
    public int sendRequest(TLObject object, RequestDelegate onComplete, int flags) {
        int requestToken = ++lastRequestToken;
        sendRequestInternal(object, onComplete, null, null, null, flags, 0, 0, true, requestToken);
        return requestToken;
    }

    /**
     * the hook and the request free are stock's, in stock's order (see
     * patches/feature/plugins.patch and sendRequestInternal's own serialize/freeResources tail) -
     * a chain's passthrough re-enters here, which is the only reason the bypass lease exists.
     */
    public void sendRequestInternal(
        TLObject object,
        RequestDelegate onComplete,
        RequestDelegateTimestamp onCompleteTimestamp,
        QuickAckDelegate onQuickAck,
        WriteToSocketDelegate onWriteToSocket,
        int flags,
        int datacenterId,
        int connectionType,
        boolean immediate,
        int requestToken
    ) {
        if (desu.inugram.helpers.plugins.tg.PluginRpc.maybeIntercept(
            this, object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket,
            flags, datacenterId, connectionType, immediate, requestToken, currentAccount
        )) {
            return;
        }
        object.freeResources();
        sent.add(new Sent(object, onComplete, onCompleteTimestamp, requestToken));
    }

    /** stock's CONNECTION_NOT_INITED path: the same instance, a fresh token, delegate untouched */
    public void inu_retryNotInited(Sent original, int newToken) {
        sendRequestInternal(
            original.request, original.onComplete, original.onCompleteTimestamp,
            null, null, 0, 0, 0, false, newToken
        );
    }

    public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) {
        cancels.add(new Cancel(token, notifyServer, onCancelled));
    }

    public static void native_bindRequestToGuid(int account, int requestToken, int guid) {
        guidBinds.add(new GuidBind(account, requestToken, guid));
    }

    public Sent lastSent() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }
}
