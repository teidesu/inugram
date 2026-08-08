// Evaluated once per engine by `rpc.rs`, which hands in the peer helpers `utils.js` returned plus
// the two constructors a plugin must not be able to swap out. The factory returned takes the
// plugin's verdict middleware and hands back an ordinary `interceptRpc` middleware over the four
// send methods - so `interceptSendMessage` is a *narrowing* of that chain rather than a second one,
// and the budget, the plugin-list order, the collapse-on-expiry, the cancel handling and the
// "a plugin's own send never re-enters" lease are the ones already tested there.
//
// Two rules fall out of that and are stated in `common.d.ts` rather than enforced twice: a secret
// chat never appears (it is `messages.sendEncrypted*`, which is not in this list) and a plugin's
// own send never appears (the bypass lease keeps it out of every chain).
((shared, PluginError, RpcError, selfUserId) => {
  const { baseName, toNumber, peerDialogId, invalid } = shared

  const unsupported = message => new PluginError('unsupported', message)

  // what the app is told when a middleware drops the send. the app is awaiting a response for a
  // request it will never get one for, so it has to be an rpc error, and the synthetic code is the
  // one `PluginRpc` tears a chain down with
  const DROP_CODE = -1000
  const DROP_TEXT = 'MESSAGE_DROPPED_BY_PLUGIN'

  // the four requests one `OutgoingMessage` covers, and what each of them can carry. read off the
  // method rather than probed per field: a flag-gated field that is *clear* is omitted from reads
  // exactly like one the constructor never declared, so `'silent' in raw` cannot tell "this send is
  // not silent" from "an edit has no such field"
  const SHAPES = Object.assign(Object.create(null), {
    'messages.sendMessage': { silent: true, reply: true, media: 'none', edit: false },
    'messages.sendMedia': { silent: true, reply: true, media: 'single', edit: false },
    'messages.sendMultiMedia': { silent: true, reply: true, media: 'album', edit: false },
    'messages.editMessage': { silent: false, reply: false, media: 'single', edit: true },
  })

  const optional = (raw, field) => {
    const value = raw[field]
    return value === undefined ? null : value
  }

  // an album carries its caption on the first item; everything else carries it on the request
  const captionOf = (raw, shape) => {
    if (shape.media !== 'album') return raw
    const items = raw.multi_media
    if (items === null || items === undefined || items.length === 0) return null
    return items[0]
  }

  const readEntities = (holder) => {
    const entities = optional(holder, 'entities')
    return entities === null ? [] : Array.from(entities)
  }

  const readText = (raw, shape) => {
    const holder = captionOf(raw, shape)
    if (holder === null) return { text: '', entities: [] }
    const text = optional(holder, 'message')
    return { text: typeof text === 'string' ? text : '', entities: readEntities(holder) }
  }

  const writeText = (raw, shape, value) => {
    const holder = captionOf(raw, shape)
    if (holder === null) throw invalid('text: this send carries nothing to write a caption on')
    if (typeof value === 'string') {
      holder.message = value
      holder.entities = []
      return
    }
    if (value === null || typeof value !== 'object' || typeof value.text !== 'string') {
      throw invalid('text: expected a string or { text, entities }')
    }
    holder.message = value.text
    holder.entities = Array.isArray(value.entities) ? value.entities : []
  }

  const replyOf = (raw, shape) => {
    if (!shape.reply) return null
    const reply = optional(raw, 'reply_to')
    return reply !== null && baseName(reply) === 'inputReplyToMessage' ? reply : null
  }

  const readTopicId = (raw, shape) => {
    const reply = replyOf(raw, shape)
    return reply === null ? null : toNumber(optional(reply, 'top_msg_id'))
  }

  // a message posted into a forum topic with no reply of its own addresses the topic's own root
  // message, so the two ids coincide - which is not a reply anyone wrote and must not read as one
  const readReplyId = (raw, shape) => {
    const reply = replyOf(raw, shape)
    if (reply === null) return null
    const id = toNumber(optional(reply, 'reply_to_msg_id'))
    return id !== null && id === readTopicId(raw, shape) ? null : id
  }

  const writeReply = (raw, shape, replyId, topicId) => {
    if (!shape.reply) throw unsupported('an edit carries no reply or topic to change')
    if (replyId === null && topicId === null) {
      raw.reply_to = null
      return
    }
    raw.reply_to = {
      _: 'inputReplyToMessage',
      reply_to_msg_id: replyId === null ? topicId : replyId,
      top_msg_id: topicId,
    }
  }

  const toOptionalId = (value, what) => {
    if (value === null || value === undefined) return null
    const id = toNumber(value)
    if (id === null || !Number.isInteger(id)) throw invalid(`${what}: expected an integer or null`)
    return id
  }

  const readMedia = (raw, shape) => {
    if (shape.media === 'none') return []
    if (shape.media === 'single') {
      const media = optional(raw, 'media')
      return media === null ? [] : [media]
    }
    const items = optional(raw, 'multi_media')
    if (items === null) return []
    const media = []
    for (let i = 0; i < items.length; i++) media.push(items[i].media)
    return media
  }

  // a length change means a different method - `sendMessage` with an attachment is `sendMedia`, and
  // an album with one item fewer is a different album - and `next()` refuses to rewrite the method
  // the app is already awaiting a response type for. replacing what is there in place is the part
  // that is decidable here; `common.d.ts` points at `drop` plus `account.sendMedia` for the rest
  const writeMedia = (raw, shape, value) => {
    if (!Array.isArray(value)) throw invalid('media: expected an array of InputMedia')
    if (shape.media === 'none') {
      if (value.length === 0) return
      throw unsupported('attaching media to a text send would change the method the app is awaiting; drop it and send your own')
    }
    if (shape.media === 'single') {
      if (value.length !== 1) {
        throw unsupported('this send carries exactly one media; drop it and send your own to change that')
      }
      raw.media = value[0]
      return
    }
    const items = raw.multi_media
    const length = items === null || items === undefined ? 0 : items.length
    if (value.length !== length) {
      throw unsupported(`this album carries ${length} items; drop it and send your own to change that`)
    }
    for (let i = 0; i < length; i++) items[i].media = value[i]
  }

  const buildOutgoing = (raw, shape, account) => {
    const message = {
      get peer() {
        const peer = raw.peer
        // `inputPeerSelf` is the account itself, and `account.userId` is gated on
        // `account.read(self)`: reading it here would throw out of a getter the contract says needs
        // no grant, failing the user's own send to Saved Messages and faulting the plugin for it
        if (baseName(peer) === 'inputPeerSelf') {
          const id = selfUserId(account.id)
          if (id === null || id === undefined) throw invalid(`peer: this account's own id is not known`)
          return id
        }
        const id = peerDialogId(peer)
        if (id === null) throw invalid(`peer: the request carries no readable peer`)
        return id
      },
      set peer(value) {
        const id = toNumber(value)
        if (id === null || id === 0) throw invalid(`peer: not a dialog id: ${value}`)
        // retargeting is naming a peer this middleware was not handed, which is a read: it goes
        // through the account handle's own gate rather than around it
        const resolved = account.resolvePeerCached(id)
        if (resolved === null) {
          throw new PluginError('not-found', `peer: ${id} is not cached, so a send cannot be retargeted at it`)
        }
        raw.peer = resolved
      },

      get text() {
        return readText(raw, shape)
      },
      set text(value) {
        writeText(raw, shape, value)
      },

      get replyToMessageId() {
        return readReplyId(raw, shape)
      },
      set replyToMessageId(value) {
        writeReply(raw, shape, toOptionalId(value, 'replyToMessageId'), readTopicId(raw, shape))
      },

      get topicId() {
        return readTopicId(raw, shape)
      },
      set topicId(value) {
        writeReply(raw, shape, readReplyId(raw, shape), toOptionalId(value, 'topicId'))
      },

      get scheduleDate() {
        return toNumber(optional(raw, 'schedule_date'))
      },
      set scheduleDate(value) {
        raw.schedule_date = toOptionalId(value, 'scheduleDate')
      },

      get silent() {
        return shape.silent && raw.silent === true
      },
      set silent(value) {
        if (!shape.silent) throw unsupported('an edit is never sent silently')
        raw.silent = value === true
      },

      get media() {
        return readMedia(raw, shape)
      },
      set media(value) {
        writeMedia(raw, shape, value)
      },

      get isEdit() {
        return shape.edit
      },
      get editMessageId() {
        return shape.edit ? toNumber(optional(raw, 'id')) : null
      },
    }
    // sealed rather than frozen: the accessors are the api, and a typo'd `msg.silence = true` is a
    // dropped intent that would otherwise pass silently
    return Object.seal(message)
  }

  return middleware => async (raw, next, account) => {
    const shape = SHAPES[baseName(raw)]
    // the host only ever dispatches the four, so this is a shape the engine does not know rather
    // than a plugin error: pass it on untouched instead of failing the user's send over it
    if (shape === undefined) return next(raw)
    const verdict = await middleware(buildOutgoing(raw, shape, account), account)
    if (verdict === 'send') return next(raw)
    if (verdict !== 'drop') {
      // a plain Error rather than an `RpcError`, which is why this reads as the plugin's fault and
      // `drop` does not: the verdict is what makes the choice total, so a path that returns nothing
      // is a bug, and it must not read as consent to send
      throw new Error(`interceptSendMessage: expected 'send' or 'drop', got ${JSON.stringify(verdict)}`)
    }
    return new RpcError(DROP_CODE, DROP_TEXT)
  }
})
