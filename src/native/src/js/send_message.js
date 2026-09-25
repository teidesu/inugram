((shared, PluginError, RpcError, DROP_CODE, DROP_TEXT) => {
  const { baseName, toNumber, getMarkedPeerId } = shared


  // Request shapes handled by `OutgoingMessage` and its middleware. Read capabilities from the
  // method name: absent fields may be unsupported or merely cleared by flags, so `'silent' in raw`
  // cannot distinguish an unsilenced send from an edit.
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

  const selectCaptionHolder = (raw, shape) => {
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
    const holder = selectCaptionHolder(raw, shape)
    if (holder === null) return { text: '', entities: [] }
    const text = optional(holder, 'message')
    return { text: typeof text === 'string' ? text : '', entities: readEntities(holder) }
  }

  const writeText = (raw, shape, value) => {
    const holder = selectCaptionHolder(raw, shape)
    if (holder === null) throw new PluginError('invalid-argument', 'text: this send carries nothing to write a caption on')
    if (typeof value === 'string') {
      holder.message = value
      holder.entities = []
      return
    }
    if (value === null || typeof value !== 'object' || typeof value.text !== 'string') {
      throw new PluginError('invalid-argument', 'text: expected a string or { text, entities }')
    }
    holder.message = value.text
    holder.entities = Array.isArray(value.entities) ? value.entities : []
  }

  const readReply = (raw, shape) => {
    if (!shape.reply) return null
    const reply = optional(raw, 'reply_to')
    return reply !== null && baseName(reply) === 'inputReplyToMessage' ? reply : null
  }

  const readTopicId = (raw, shape) => {
    const reply = readReply(raw, shape)
    return reply === null ? null : toNumber(optional(reply, 'top_msg_id'))
  }

  // A forum message without a reply uses the topic root as its reply ID. Matching IDs therefore
  // indicate the topic, not a user-written reply.
  const readReplyId = (raw, shape) => {
    const reply = readReply(raw, shape)
    if (reply === null) return null
    const id = toNumber(optional(reply, 'reply_to_msg_id'))
    return id !== null && id === readTopicId(raw, shape) ? null : id
  }

  const writeReply = (raw, shape, replyId, topicId) => {
    if (!shape.reply) throw new PluginError('unsupported', 'an edit carries no reply or topic to change')
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
    if (id === null || !Number.isInteger(id)) throw new PluginError('invalid-argument', `${what}: expected an integer or null`)
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

  // a length change means a different method, which `next()` refuses to rewrite; `common.d.ts` points
  // at `drop` plus `account.sendMedia` for that
  const writeMedia = (raw, shape, value) => {
    if (!Array.isArray(value)) throw new PluginError('invalid-argument', 'media: expected an array of InputMedia')
    if (shape.media === 'none') {
      if (value.length === 0) return
      throw new PluginError('unsupported', 'attaching media to a text send would change the method the app is awaiting; drop it and send your own')
    }
    if (shape.media === 'single') {
      if (value.length !== 1) {
        throw new PluginError('unsupported', 'this send carries exactly one media; drop it and send your own to change that')
      }
      raw.media = value[0]
      return
    }
    const items = raw.multi_media
    const length = items === null || items === undefined ? 0 : items.length
    if (value.length !== length) {
      throw new PluginError('unsupported', `this album carries ${length} items; drop it and send your own to change that`)
    }
    for (let i = 0; i < length; i++) items[i].media = value[i]
  }

  const buildOutgoing = (raw, shape, account, dispatchId) => {
    const message = {
      get peer() {
        const peer = raw.peer
        if (baseName(peer) === 'inputPeerSelf') return account.userId
        const id = getMarkedPeerId(peer)
        if (id === null) throw new PluginError('invalid-argument', `peer: the request carries no readable peer`)
        return id
      },
      set peer(value) {
        const id = toNumber(value)
        if (id === null || id === 0) throw new PluginError('invalid-argument', `peer: not a marked peer id: ${value}`)
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
        if (!shape.silent) throw new PluginError('unsupported', 'an edit is never sent silently')
        raw.silent = value === true
      },

      get media() {
        return readMedia(raw, shape)
      },
      set media(value) {
        writeMedia(raw, shape, value)
      },

      /** staged, not uploaded: the app uploads it into the local message it already drew */
      setMedia(file, options) {
        if (shape.edit) throw new PluginError('unsupported', 'an edit carries no media to replace')
        if (shape.media === 'album') throw new PluginError('unsupported', `an album's media cannot be replaced; drop it and send your own`)
        return shared.setSendMedia(account, dispatchId, file, options)
      },

      get isEdit() {
        return shape.edit
      },
      get editMessageId() {
        return shape.edit ? toNumber(optional(raw, 'id')) : null
      },
    }
    return Object.seal(message)
  }

  // a drop answers the app with `DROP_CODE`/`DROP_TEXT`: it is awaiting a response for a request
  // it will never get one for, so it has to be an rpc error, and the code is the one `PluginRpc`
  // tears a chain down with
  const wrap = middleware => async (context, next, dispatchId) => {
    const raw = context.request
    const account = context.account
    const shape = SHAPES[baseName(raw)]
    // The host should dispatch only SHAPES methods. Pass unknown shapes through unchanged; a host
    // mismatch must not fail the user's send.
    if (shape === undefined) return next()
    const verdict = await middleware({
      message: buildOutgoing(raw, shape, account, dispatchId),
      account,
      get signal() {
        return context.signal
      },
    })
    // a `setMedia` send resolves this with null: the request is never sent, the app taking the
    // message over instead, and the host tells it so itself rather than through this value
    if (verdict === 'send') return next()
    if (verdict !== 'drop') {
      // a plain Error, unlike `drop`, so a path that returns nothing reads as the plugin's bug, never as consent to send
      throw new Error(`interceptSendMessage: expected 'send' or 'drop', got ${JSON.stringify(verdict)}`)
    }
    return new RpcError(DROP_CODE, DROP_TEXT)
  }

  return { wrap, methods: Object.keys(SHAPES) }
})
