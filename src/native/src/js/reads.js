(natives, shared, Message, PluginError, ops) => {
  const {
    baseName,
    SEPARATOR,
    toSpec,
    toSpecList,
    toMessageId,
    toMessageIds,
    toOptions,
    toCount,
    toFieldNames,
    readAccountSlot,
  } = shared

  // keep in sync with Kotlin `PluginReads.KIND_*`
  const KIND_PEER = 0
  const KIND_USER = 1
  const KIND_CHANNEL = 2

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
    if (typeof value !== 'string') throw new PluginError('invalid-argument', `${what}: cursor must be a previous page's next`)
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
    if (mode === undefined) throw new PluginError('invalid-argument', `${what}: archive must be 'exclude', 'only' or 'keep'`)
    return mode
  }

  const toBatch = (value, what) => {
    const size = toCount(value, what, 'batchSize')
    return size === 0 ? BATCH_SIZE : size
  }

  const inputPeer = (slot, spec, kind) => natives.read(slot, ops.inputPeer, spec + SEPARATOR + kind)

  async function* pageByCursor(limit, fetchPage) {
    let cursor
    let sent = 0
    for (;;) {
      const page = await fetchPage(cursor)
      for (const item of page) {
        yield item
        if (++sent === limit) return
      }
      if (typeof page.next !== 'string') return
      cursor = page.next
    }
  }

  const wrap = raw => (raw === null ? null : new Message(raw))

  // an input peer the caller already holds is the answer, per `common.d.ts`: nothing is read to
  // produce it, which is also why it needs no grant
  const passthrough = (peer, kind) =>
    peer !== null && typeof peer === 'object' && PASSTHROUGH[kind].has(baseName(peer))

  // every async member rejects rather than throws
  const fetchWith = (account, op, what, build) => {
    try {
      const slot = readAccountSlot(account, what)
      const [peer, args, cursor = ''] = build()
      return natives.fetch(slot, op, peer, JSON.stringify(args), cursor)
    } catch (e) {
      return Promise.reject(e)
    }
  }

  // at most `RESOLVE_CONCURRENCY` in flight (`common.d.ts`). Only not-found becomes `null`: a refusal
  // answered as `null` could not be told from "there is no such peer"
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
      const slot = readAccountSlot(account, what)
      if (passthrough(peer, kind)) return Promise.resolve(peer)
      const spec = toSpec(peer)
      const cached = inputPeer(slot, spec, kind)
      if (cached !== null) return Promise.resolve(cached)
      return natives.resolve(slot, spec, kind)
    } catch (e) {
      return Promise.reject(e)
    }
  }

  const proto = {
    getMe() {
      return natives.read(readAccountSlot(this, 'getMe'), ops.me, '')
    },

    getUser(peer) {
      return natives.read(readAccountSlot(this, 'getUser'), ops.user, toSpec(peer))
    },

    getChat(peer) {
      return natives.read(readAccountSlot(this, 'getChat'), ops.chat, toSpec(peer))
    },

    getPeer(peer) {
      return natives.read(readAccountSlot(this, 'getPeer'), ops.peer, toSpec(peer))
    },

    getDialog(peer) {
      return natives.read(readAccountSlot(this, 'getDialog'), ops.dialog, toSpec(peer))
    },

    isDialogMuted(peer, options) {
      const slot = readAccountSlot(this, 'isDialogMuted')
      const opts = toOptions(options, 'isDialogMuted')
      return natives.read(slot, ops.dialogMuted, toSpec(peer) + SEPARATOR + toCount(opts.topicId, 'isDialogMuted', 'topicId'))
    },

    previewMessage(message, options) {
      const slot = readAccountSlot(this, 'previewMessage')
      const opts = toOptions(options, 'previewMessage')
      const raw = message === null || typeof message !== 'object' ? message : message.raw ?? message
      return natives.previewMessage(slot, raw, opts.hideSpoilers === true)
    },

    getTopicCached(peer, topicId) {
      const slot = readAccountSlot(this, 'getTopicCached')
      return natives.read(slot, ops.topic, toSpec(peer) + SEPARATOR + toCount(topicId, 'getTopicCached', 'topicId'))
    },

    getUsers(peers) {
      return natives.readMany(readAccountSlot(this, 'getUsers'), ops.users, toSpecList(peers, 'getUsers'))
    },

    getChats(peers) {
      return natives.readMany(readAccountSlot(this, 'getChats'), ops.chats, toSpecList(peers, 'getChats'))
    },

    getMessagesCached(peer, messageIds) {
      const slot = readAccountSlot(this, 'getMessagesCached')
      const spec = toSpec(peer)
      if (!Array.isArray(messageIds)) {
        return wrap(natives.read(slot, ops.messageCached, spec + SEPARATOR + toMessageId(messageIds, 'getMessagesCached')))
      }
      const ids = toMessageIds(messageIds, 'getMessagesCached').join(SEPARATOR)
      return natives.readMany(slot, ops.messagesCached, spec + SEPARATOR + ids).map(wrap)
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
      const slot = readAccountSlot(this, 'resolvePeerCached')
      if (passthrough(peer, KIND_PEER)) return peer
      return inputPeer(slot, toSpec(peer), KIND_PEER)
    },

    resolvePeer(peer) {
      return resolveWith(this, peer, KIND_PEER, 'resolvePeer')
    },

    resolvePeerMany(peers) {
      try {
        const slot = readAccountSlot(this, 'resolvePeerMany')
        // the same gate every read runs, before the first element crosses - so an empty list is
        // refused for the same reason a full one is rather than answering `[]` to anybody
        natives.checkPeers(slot)
        if (!Array.isArray(peers)) throw new PluginError('invalid-argument', 'resolvePeerMany: expected an array of peers')
        const out = Array.from({ length: peers.length }).fill(null)
        const misses = []
        for (let index = 0; index < peers.length; index++) {
          const peer = peers[index]
          if (passthrough(peer, KIND_PEER)) {
            out[index] = peer
            continue
          }
          const spec = toSpec(peer)
          const cached = inputPeer(slot, spec, KIND_PEER)
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
      const slot = readAccountSlot(this, 'getDraft')
      const opts = toOptions(options, 'getDraft')
      return natives.read(slot, ops.draft, toSpec(peer) + SEPARATOR + toCount(opts.topicId, 'getDraft', 'topicId'))
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
          // Folders have their own archive flag. Reject an explicit archive option to avoid
          // filtering out chats the folder includes.
          throw new PluginError('invalid-argument', 'getDialogsCached: name either archive or chatFolderId, not both')
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

    // each iterator pages the member above it through the captured prototype, so arguments are checked
    // in one place. Nothing runs until the first `next()`, so a bad argument rejects there.

    async* iterDialogs(options) {
      const opts = toOptions(options, 'iterDialogs')
      const limit = toLimit(opts.limit, 'iterDialogs')
      const batch = toBatch(opts.batchSize, 'iterDialogs')
      yield* pageByCursor(limit, cursor => proto.getDialogs.call(this, {
        folderId: opts.folderId,
        limit: batch,
        fields: opts.fields,
        cursor,
      }))
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
        // Pages are newest-first, so continue below the oldest ID. Stop if the offset does not
        // change: `offset_id` is exclusive, and a repeated page means no more history.
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
      yield* pageByCursor(limit, cursor => proto.getTopics.call(this, peer, { limit: batch, cursor }))
    },
  }

  return Object.freeze(proto)
}
