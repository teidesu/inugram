// Evaluated once per engine by `reads.rs`, which hands in the native ops, the peer helpers
// `utils.js` returned, and the two constructors this file must not let a plugin swap out. The
// factory returns the `Account` prototype: every handle `account.rs` mints gets it, so these
// methods exist once per engine rather than once per dispatch.
((natives, shared, Message, PluginError) => {
  const { baseName, invalid, SEPARATOR, toSpec, toSpecList, toMessageId, toMessageIds, toOptions, toCount, slotOf } =
    shared

  // keep in sync with rust `reads::KIND_*` and Kotlin `PluginReads.KIND_*`
  const KIND_PEER = 0
  const KIND_USER = 1
  const KIND_CHANNEL = 2

  // the asynchronous half; keep in sync with rust `reads::OP_*` and Kotlin `PluginReads.OP_*`
  const OP_USER_FULL = 11
  const OP_CHAT_FULL = 12
  const OP_HISTORY = 13
  const OP_DIALOGS = 14
  const OP_TOPICS = 15

  // what a resolve* was already handed and gives straight back, per kind
  const PASSTHROUGH = [
    new Set([
      'inputPeerSelf', 'inputPeerUser', 'inputPeerChat', 'inputPeerChannel',
      'inputPeerUserFromMessage', 'inputPeerChannelFromMessage',
    ]),
    new Set(['inputUserSelf', 'inputUser', 'inputUserFromMessage']),
    new Set(['inputChannel', 'inputChannelFromMessage']),
  ]

  // opaque per `common.d.ts`: it is a token native mints, and which list minted it is checked there
  const toCursor = (value, what) => {
    if (value === undefined || value === null) return ''
    if (typeof value !== 'string') throw invalid(`${what}: cursor must be a previous page's next`)
    return value
  }

  // what an iterator asks the host for per page when the plugin names no `batchSize`, and how many
  // peers `resolvePeerMany` keeps in flight. both are stated in `common.d.ts`
  const BATCH_SIZE = 100
  const RESOLVE_CONCURRENCY = 8

  // 0 is "no limit" for an iterator, which is what an omitted one means: it pages to the end
  const toLimit = (value, what) => Number(toCount(value, what, 'limit'))

  const toBatch = (value, what) => {
    const size = Number(toCount(value, what, 'batchSize'))
    return size === 0 ? BATCH_SIZE : size
  }

  const wrap = (raw) => (raw === null ? null : new Message(raw))

  // an input peer the caller already holds is the answer, per `common.d.ts`: nothing is read to
  // produce it, which is also why it needs no grant
  const passthrough = (peer, kind) =>
    peer !== null && typeof peer === 'object' && PASSTHROUGH[kind].has(baseName(peer))

  // every async member fails asynchronously, whatever went wrong - a bad argument, a missing grant,
  // a cursor from another list
  const fetchWith = (account, op, what, build) => {
    try {
      const slot = slotOf(account, what)
      const [arg, cursor] = build()
      return natives.fetch(slot, op, arg, cursor)
    } catch (e) {
      return Promise.reject(e)
    }
  }

  // at most `RESOLVE_CONCURRENCY` of them are in flight, per `common.d.ts`. A peer that is not
  // found stays `null` where it was asked about: the caller named several, and one of them being
  // unknown is an answer rather than a failure of the batch. Nothing else is - a refusal or a
  // failure answered as `null` cannot be told from "there is no such peer".
  const resolveMisses = (slot, out, misses) => {
    let next = 0
    const worker = async () => {
      for (;;) {
        const index = next++
        if (index >= misses.length) return
        const [at, spec] = misses[index]
        try {
          out[at] = await natives.resolve(slot, spec, KIND_PEER)
        } catch (e) {
          if (!(e instanceof PluginError) || e.code !== 'not-found') throw e
        }
      }
    }
    const workers = []
    for (let n = 0; n < Math.min(RESOLVE_CONCURRENCY, misses.length); n++) workers.push(worker())
    return Promise.all(workers).then(() => out)
  }

  const resolveWith = (account, peer, kind, what) => {
    try {
      const slot = slotOf(account, what)
      if (passthrough(peer, kind)) return Promise.resolve(peer)
      const spec = toSpec(peer)
      const cached = natives.inputPeer(slot, spec, kind)
      // the cache first, so the common case costs neither a promise hop nor a request
      if (cached !== null) return Promise.resolve(cached)
      return natives.resolve(slot, spec, kind)
    } catch (e) {
      // an async member fails asynchronously, whatever went wrong - including a missing grant
      return Promise.reject(e)
    }
  }

  const proto = {
    getMe() {
      return natives.getMe(slotOf(this, 'getMe'))
    },

    getUser(peer) {
      return natives.getUser(slotOf(this, 'getUser'), toSpec(peer))
    },

    getChat(peer) {
      return natives.getChat(slotOf(this, 'getChat'), toSpec(peer))
    },

    getPeer(peer) {
      return natives.getPeer(slotOf(this, 'getPeer'), toSpec(peer))
    },

    getDialog(peer) {
      return natives.getDialog(slotOf(this, 'getDialog'), toSpec(peer))
    },

    getMessage(peer, messageId) {
      const slot = slotOf(this, 'getMessage')
      return wrap(natives.getMessage(slot, toSpec(peer), toMessageId(messageId, 'getMessage')))
    },

    getUsers(peers) {
      return natives.getUsers(slotOf(this, 'getUsers'), toSpecList(peers, 'getUsers'))
    },

    getChats(peers) {
      return natives.getChats(slotOf(this, 'getChats'), toSpecList(peers, 'getChats'))
    },

    getMessages(peer, messageIds) {
      const slot = slotOf(this, 'getMessages')
      const ids = toMessageIds(messageIds, 'getMessages').join(SEPARATOR)
      return natives.getMessages(slot, toSpec(peer), ids).map(wrap)
    },

    resolvePeerCached(peer) {
      const slot = slotOf(this, 'resolvePeerCached')
      if (passthrough(peer, KIND_PEER)) return peer
      return natives.inputPeer(slot, toSpec(peer), KIND_PEER)
    },

    resolvePeer(peer) {
      return resolveWith(this, peer, KIND_PEER, 'resolvePeer')
    },

    resolvePeerMany(peers) {
      try {
        const slot = slotOf(this, 'resolvePeerMany')
        // the same gate every read runs, before the first element crosses - so an empty list is
        // refused for the same reason a full one is rather than answering `[]` to anybody
        natives.checkPeers(slot)
        if (!Array.isArray(peers)) throw invalid('resolvePeerMany: expected an array of peers')
        const out = new Array(peers.length).fill(null)
        const misses = []
        for (let index = 0; index < peers.length; index++) {
          const peer = peers[index]
          if (passthrough(peer, KIND_PEER)) {
            out[index] = peer
            continue
          }
          const spec = toSpec(peer)
          const cached = natives.inputPeer(slot, spec, KIND_PEER)
          // the cache first, exactly as `resolvePeer` does: only what it cannot answer costs a request
          if (cached !== null) out[index] = cached
          else misses.push([index, spec])
        }
        return resolveMisses(slot, out, misses)
      } catch (e) {
        return Promise.reject(e)
      }
    },

    resolveUser(peer) {
      return resolveWith(this, peer, KIND_USER, 'resolveUser')
    },

    resolveChannel(peer) {
      return resolveWith(this, peer, KIND_CHANNEL, 'resolveChannel')
    },

    getDraft(peer, options) {
      const slot = slotOf(this, 'getDraft')
      const opts = toOptions(options, 'getDraft')
      return natives.getDraft(slot, toSpec(peer), toCount(opts.topicId, 'getDraft', 'topicId'))
    },

    getUserFull(peer) {
      return fetchWith(this, OP_USER_FULL, 'getUserFull', () => [toSpec(peer), ''])
    },

    getChatFull(peer) {
      return fetchWith(this, OP_CHAT_FULL, 'getChatFull', () => [toSpec(peer), ''])
    },

    getHistory(peer, options) {
      return fetchWith(this, OP_HISTORY, 'getHistory', () => {
        const opts = toOptions(options, 'getHistory')
        return [
          [
            toSpec(peer),
            toCount(opts.limit, 'getHistory', 'limit'),
            toCount(opts.offsetId, 'getHistory', 'offsetId'),
            toCount(opts.minId, 'getHistory', 'minId'),
            toCount(opts.maxId, 'getHistory', 'maxId'),
            toCount(opts.topicId, 'getHistory', 'topicId'),
          ].join(SEPARATOR),
          '',
        ]
      }).then((messages) => messages.map(wrap))
    },

    getDialogs(options) {
      return fetchWith(this, OP_DIALOGS, 'getDialogs', () => {
        const opts = toOptions(options, 'getDialogs')
        return [
          [
            toCount(opts.folderId, 'getDialogs', 'folderId'),
            toCount(opts.limit, 'getDialogs', 'limit'),
          ].join(SEPARATOR),
          toCursor(opts.cursor, 'getDialogs'),
        ]
      })
    },

    getTopics(peer, options) {
      return fetchWith(this, OP_TOPICS, 'getTopics', () => {
        const opts = toOptions(options, 'getTopics')
        return [
          [toSpec(peer), toCount(opts.limit, 'getTopics', 'limit')].join(SEPARATOR),
          toCursor(opts.cursor, 'getTopics'),
        ]
      })
    },

    //
    // each pages the member above it by *calling* it through the captured prototype, so an argument
    // is normalized, gated and materialized in exactly one place rather than in two that agree.
    // Nothing runs until the first `next()`, so a bad argument and a missing grant reject there.

    async *iterDialogs(options) {
      const opts = toOptions(options, 'iterDialogs')
      const limit = toLimit(opts.limit, 'iterDialogs')
      const batch = toBatch(opts.batchSize, 'iterDialogs')
      let cursor
      let sent = 0
      for (;;) {
        const page = await proto.getDialogs.call(this, { folderId: opts.folderId, limit: batch, cursor })
        for (const dialog of page) {
          yield dialog
          if (++sent === limit) return
        }
        if (typeof page.next !== 'string') return
        cursor = page.next
      }
    },

    async *iterHistory(peer, options) {
      const opts = toOptions(options, 'iterHistory')
      const limit = toLimit(opts.limit, 'iterHistory')
      const batch = toBatch(opts.batchSize, 'iterHistory')
      let offsetId = Number(toCount(opts.offsetId, 'iterHistory', 'offsetId'))
      let sent = 0
      for (;;) {
        const page = await proto.getHistory.call(this, peer, {
          limit: batch,
          offsetId,
          minId: opts.minId,
          maxId: opts.maxId,
          topicId: opts.topicId,
        })
        for (const message of page) {
          yield message
          if (++sent === limit) return
        }
        if (page.length < batch) return
        // newest-first, so the next page starts below the oldest id this one carried. An offset that
        // did not move ends it: `offset_id` is exclusive, so a page repeating itself is a server
        // with nothing left rather than more history, and nothing else here would ever terminate
        const oldest = page[page.length - 1].id
        if (typeof oldest !== 'number' || oldest <= 0) return
        if (offsetId !== 0 && oldest >= offsetId) return
        offsetId = oldest
      }
    },

    async *iterTopics(peer, options) {
      const opts = toOptions(options, 'iterTopics')
      const limit = toLimit(opts.limit, 'iterTopics')
      const batch = toBatch(opts.batchSize, 'iterTopics')
      let cursor
      let sent = 0
      for (;;) {
        const page = await proto.getTopics.call(this, peer, { limit: batch, cursor })
        for (const topic of page) {
          yield topic
          if (++sent === limit) return
        }
        if (typeof page.next !== 'string') return
        cursor = page.next
      }
    },
  }

  return Object.freeze(proto)
})
