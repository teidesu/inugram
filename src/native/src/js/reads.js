(natives, shared, Message, PluginError, ops) => {
  const {
    baseName,
    invalid,
    SEPARATOR,
    toSpec,
    toSpecList,
    toMessageId,
    toMessageIds,
    toOptions,
    toCount,
    toFieldNames,
    slotOf,
  } = shared

  // keep in sync with rust `reads::KIND_*` and Kotlin `PluginReads.KIND_*`
  const KIND_PEER = 0
  const KIND_USER = 1
  const KIND_CHANNEL = 2

  // what a resolve* was already handed and gives straight back, per kind
  const PASSTHROUGH = [
    new Set([
      'inputPeerSelf',
      'inputPeerUser',
      'inputPeerChat',
      'inputPeerChannel',
      'inputPeerUserFromMessage',
      'inputPeerChannelFromMessage',
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
  const toLimit = (value, what) => toCount(value, what, 'limit')

  // keep in sync with Kotlin `PluginReads.ARCHIVE_*`, which reads anything else as exclude
  // a Map rather than an object literal: `archive` is plugin input, and a lookup on a literal
  // answers for 'constructor' and friends too
  const ARCHIVE = new Map([['exclude', 0], ['only', 1], ['keep', 2]])

  const toArchive = (value, what) => {
    if (value === undefined || value === null) return ARCHIVE.get('exclude')
    const mode = ARCHIVE.get(value)
    if (mode === undefined) throw invalid(`${what}: archive must be 'exclude', 'only' or 'keep'`)
    return mode
  }

  const toBatch = (value, what) => {
    const size = toCount(value, what, 'batchSize')
    return size === 0 ? BATCH_SIZE : size
  }

  const wrap = raw => (raw === null ? null : new Message(raw))

  // an input peer the caller already holds is the answer, per `common.d.ts`: nothing is read to
  // produce it, which is also why it needs no grant
  const passthrough = (peer, kind) =>
    peer !== null && typeof peer === 'object' && PASSTHROUGH[kind].has(baseName(peer))

  // every async member fails asynchronously, whatever went wrong - a bad argument, a missing grant,
  // a cursor from another list
  const fetchWith = (account, op, what, build) => {
    try {
      const slot = slotOf(account, what)
      const [peer, args, cursor = ''] = build()
      return natives.fetch(slot, op, peer, JSON.stringify(args), cursor)
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

    getUsers(peers) {
      return natives.getUsers(slotOf(this, 'getUsers'), toSpecList(peers, 'getUsers'))
    },

    getChats(peers) {
      return natives.getChats(slotOf(this, 'getChats'), toSpecList(peers, 'getChats'))
    },

    getMessagesCached(peer, messageIds) {
      const slot = slotOf(this, 'getMessagesCached')
      const spec = toSpec(peer)
      if (!Array.isArray(messageIds)) {
        return wrap(natives.getMessage(slot, spec, toMessageId(messageIds, 'getMessagesCached')))
      }
      const ids = toMessageIds(messageIds, 'getMessagesCached').join(SEPARATOR)
      return natives.getMessages(slot, spec, ids).map(wrap)
    },

    getMessages(peer, messageIds) {
      const one = !Array.isArray(messageIds)
      return fetchWith(this, ops.messages, 'getMessages', () => {
        const spec = toSpec(peer)
        const ids = one
          ? [toMessageId(messageIds, 'getMessages')]
          : toMessageIds(messageIds, 'getMessages')
        return [spec, { ids }]
      }).then(messages => (one ? wrap(messages[0] ?? null) : messages.map(wrap)))
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
        const out = Array.from({ length: peers.length }).fill(null)
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
      return natives.getDraft(slot, toSpec(peer), String(toCount(opts.topicId, 'getDraft', 'topicId')))
    },

    getUserFull(peer) {
      return fetchWith(this, ops.userFull, 'getUserFull', () => [toSpec(peer), {}])
    },

    getChatFull(peer) {
      return fetchWith(this, ops.chatFull, 'getChatFull', () => [toSpec(peer), {}])
    },

    getHistory(peer, options) {
      return fetchWith(this, ops.history, 'getHistory', () => {
        const opts = toOptions(options, 'getHistory')
        return [
          toSpec(peer),
          {
            limit: toCount(opts.limit, 'getHistory', 'limit'),
            offsetId: toCount(opts.offsetId, 'getHistory', 'offsetId'),
            minId: toCount(opts.minId, 'getHistory', 'minId'),
            maxId: toCount(opts.maxId, 'getHistory', 'maxId'),
            topicId: toCount(opts.topicId, 'getHistory', 'topicId'),
          },
        ]
      }).then(messages => messages.map(wrap))
    },

    getDialogs(options) {
      return fetchWith(this, ops.dialogs, 'getDialogs', () => {
        const opts = toOptions(options, 'getDialogs')
        return [
          '',
          {
            folderId: toCount(opts.folderId, 'getDialogs', 'folderId'),
            limit: toCount(opts.limit, 'getDialogs', 'limit'),
            fields: toFieldNames(opts.fields, 'getDialogs'),
          },
          toCursor(opts.cursor, 'getDialogs'),
        ]
      })
    },

    getDialogsCached(options) {
      return fetchWith(this, ops.dialogsCached, 'getDialogsCached', () => {
        const opts = toOptions(options, 'getDialogsCached')
        const folder = opts.chatFolderId
        const named = folder !== undefined && folder !== null
        if (named && opts.archive !== undefined && opts.archive !== null) {
          // a folder carries its own "exclude archived" flag, so the default `'exclude'` would
          // drop what that folder was set up to keep. Refusing beats answering the wrong list
          throw invalid('getDialogsCached: name either archive or chatFolderId, not both')
        }
        return [
          '',
          {
            archive: toArchive(opts.archive, 'getDialogsCached'),
            chatFolderId: named ? toCount(folder, 'getDialogsCached', 'chatFolderId') : null,
            limit: toCount(opts.limit, 'getDialogsCached', 'limit'),
            fields: toFieldNames(opts.fields, 'getDialogsCached'),
          },
        ]
      })
    },

    getChatFoldersCached() {
      return fetchWith(this, ops.chatFolders, 'getChatFoldersCached', () => ['', {}])
    },

    getTopics(peer, options) {
      return fetchWith(this, ops.topics, 'getTopics', () => {
        const opts = toOptions(options, 'getTopics')
        return [
          toSpec(peer),
          { limit: toCount(opts.limit, 'getTopics', 'limit') },
          toCursor(opts.cursor, 'getTopics'),
        ]
      })
    },

    //
    // each pages the member above it by *calling* it through the captured prototype, so an argument
    // is normalized, gated and materialized in exactly one place rather than in two that agree.
    // Nothing runs until the first `next()`, so a bad argument and a missing grant reject there.

    async* iterDialogs(options) {
      const opts = toOptions(options, 'iterDialogs')
      const limit = toLimit(opts.limit, 'iterDialogs')
      const batch = toBatch(opts.batchSize, 'iterDialogs')
      let cursor
      let sent = 0
      for (;;) {
        const page = await proto.getDialogs.call(this, {
          folderId: opts.folderId,
          limit: batch,
          fields: opts.fields,
          cursor,
        })
        for (const dialog of page) {
          yield dialog
          if (++sent === limit) return
        }
        if (typeof page.next !== 'string') return
        cursor = page.next
      }
    },

    async* iterHistory(peer, options) {
      const opts = toOptions(options, 'iterHistory')
      const limit = toLimit(opts.limit, 'iterHistory')
      const batch = toBatch(opts.batchSize, 'iterHistory')
      let offsetId = toCount(opts.offsetId, 'iterHistory', 'offsetId')
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

    async* iterTopics(peer, options) {
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
}
