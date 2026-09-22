(natives, shared, Message, PluginError, readsPrototype, ops) => {
  const { invalid, toSpec, toOptions, toCount, toMessageId, toMessageIds, slotOf } = shared

  const INT64 = /^-?\d+$/

  const TYPING_ACTIONS = new Set([
    'typing',
    'cancel',
    'recordVideo',
    'uploadVideo',
    'recordVoice',
    'uploadVoice',
    'uploadPhoto',
    'uploadDocument',
    'chooseSticker',
    'chooseContact',
  ])

  // `InputText`: a bare string is unformatted text, per `common.d.ts`. entities stay whatever the
  // plugin handed over - a live view included, which stringifies through its own `toJSON`
  const toText = (value, what) => {
    if (typeof value === 'string') return { text: value, entities: null }
    if (value !== null && typeof value === 'object' && typeof value.text === 'string') {
      const entities = value.entities
      if (entities !== undefined && entities !== null && !Array.isArray(entities)) {
        throw invalid(`${what}: entities must be an array`)
      }
      return { text: value.text, entities: entities === undefined ? null : entities }
    }
    throw invalid(`${what}: expected a string or { text, entities }`)
  }

  const toFlag = (value, what, field) => {
    if (value === undefined || value === null) return false
    if (typeof value !== 'boolean') throw invalid(`${what}: ${field} must be a boolean`)
    return value
  }

  const toOptedIn = (value, what, field) => {
    if (value === undefined || value === null) return true
    if (typeof value !== 'boolean') throw invalid(`${what}: ${field} must be a boolean`)
    return value
  }

  const toName = (value, what, field) => {
    if (value === undefined || value === null) return ''
    if (typeof value !== 'string') throw invalid(`${what}: ${field} must be a string`)
    return value
  }

  const toPeerOrNull = value => (value === undefined || value === null ? null : toSpec(value))

  const toProgress = (value, what) => {
    if (value === undefined || value === null) return null
    if (typeof value !== 'function') throw invalid(`${what}: onProgress must be a function`)
    return value
  }

  const toReactions = (list, what) => {
    if (!Array.isArray(list)) throw invalid(`${what}: expected an array of reactions`)
    return list.map((reaction) => {
      if (typeof reaction === 'string') {
        if (reaction.length === 0) throw invalid(`${what}: an empty string is not an emoji`)
        return { emoji: reaction }
      }
      if (reaction !== null && typeof reaction === 'object') {
        const id = reaction.customEmojiId
        if (id !== undefined && id !== null && INT64.test(String(id))) return { customEmojiId: String(id) }
      }
      throw invalid(`${what}: expected an emoji or { customEmojiId }`)
    })
  }

  // Unwrap either supported message shape without taking a snapshot. The host can resolve the live
  // handle to its original object, which lets downloads refresh attachment file references using
  // their parent message.
  const toRawMessage = (message, what) => {
    const raw = message instanceof Message ? message.raw : message
    if (raw === null || typeof raw !== 'object') throw invalid(`${what}: expected a message`)
    return raw
  }

  // the `file` union, minus the parts only the host can judge: a `Blob`, bytes and a TL object all
  // cross as themselves (`writes.rs` encodes each). `{ path }` is normalized to exactly that one
  // key, which is how the encoder tells it from a TL object literal without guessing
  const toFile = (file, what) => {
    if (file === null || typeof file !== 'object') {
      throw invalid(`${what}: expected a Blob, bytes, an InputFile/InputMedia or { path }`)
    }
    if (file._ !== undefined) return file
    // A `path` property selects the path variant and must be a string. Do not fall through to TL
    // encoding, which would report the wrong error.
    if (file.path !== undefined) {
      if (typeof file.path !== 'string') throw invalid(`${what}: path must be a string`)
      return { path: file.path }
    }
    return file
  }

  const startWrite = (account, op, what, build) => {
    try {
      const slot = slotOf(account, what)
      const [arg, values, onProgress] = build()
      return natives.write(slot, op, JSON.stringify(arg), values, onProgress)
    } catch (e) {
      // every write fails asynchronously, whatever went wrong - a bad argument, a missing grant, a
      // peer that turned out to be a secret chat
      return Promise.reject(e)
    }
  }

  const wrap = raw => (raw === null ? null : new Message(raw))

  // the `Promise<void>` members: the host answers a null wire, and `undefined` is what a plugin
  // writing `await acc.readHistory(...) === undefined` is entitled to see
  const voidly = promise => promise.then(() => undefined)

  // `message.setMedia()`, reached from the send prelude rather than from a plugin: the same staged
  // file write every media send is, and the host matches the dispatch to the local message the
  // composer already drew. The caption is the send's own text, so it is not named here
  shared.setSendMedia = (account, dispatchId, file, options) =>
    voidly(startWrite(account, ops.setSendMedia, 'setMedia', () => {
      const opts = toOptions(options, 'setMedia')
      return [
        {
          dispatch: dispatchId,
          asDocument: toFlag(opts.asDocument, 'setMedia', 'asDocument'),
          fileName: toName(opts.fileName, 'setMedia', 'fileName'),
        },
        [toFile(file, 'setMedia')],
        null,
      ]
    }))

  /** the options every send shares, so one shape reaches the host however it was called */
  const sendOptions = (opts, what) => ({
    replyTo: toCount(opts.replyToMessageId, what, 'replyToMessageId'),
    topicId: toCount(opts.topicId, what, 'topicId'),
    silent: toFlag(opts.silent, what, 'silent'),
    scheduleDate: toCount(opts.scheduleDate, what, 'scheduleDate'),
    sendAs: toPeerOrNull(opts.sendAs),
  })

  const proto = {
    sendMessage(peer, text, options) {
      return startWrite(this, ops.sendMessage, 'sendMessage', () => {
        const opts = toOptions(options, 'sendMessage')
        const body = toText(text, 'sendMessage')
        return [
          {
            peer: toSpec(peer),
            ...body,
            ...sendOptions(opts, 'sendMessage'),
            optimistic: toOptedIn(opts.optimistic, 'sendMessage', 'optimistic'),
            noWebpage: toFlag(opts.noWebpage, 'sendMessage', 'noWebpage'),
            clearDraft: toFlag(opts.clearDraft, 'sendMessage', 'clearDraft'),
          },
          [],
          null,
        ]
      }).then(wrap)
    },

    sendMedia(peer, file, options) {
      return startWrite(this, ops.sendMedia, 'sendMedia', () => {
        const opts = toOptions(options, 'sendMedia')
        const caption = opts.caption === undefined || opts.caption === null
          ? { text: '', entities: null }
          : toText(opts.caption, 'sendMedia')
        return [
          {
            peer: toSpec(peer),
            ...caption,
            ...sendOptions(opts, 'sendMedia'),
            optimistic: toOptedIn(opts.optimistic, 'sendMedia', 'optimistic'),
            asDocument: toFlag(opts.asDocument, 'sendMedia', 'asDocument'),
            fileName: toName(opts.fileName, 'sendMedia', 'fileName'),
          },
          [toFile(file, 'sendMedia')],
          toProgress(opts.onProgress, 'sendMedia'),
        ]
      }).then(wrap)
    },

    sendMultiMedia(peer, items, options) {
      return startWrite(this, ops.sendMultiMedia, 'sendMultiMedia', () => {
        if (!Array.isArray(items) || items.length === 0) {
          throw invalid('sendMultiMedia: expected a non-empty array of items')
        }
        const opts = toOptions(options, 'sendMultiMedia')
        const files = []
        const described = items.map((item) => {
          const one = toOptions(item, 'sendMultiMedia')
          files.push(toFile(one.file, 'sendMultiMedia'))
          const caption = one.caption === undefined || one.caption === null
            ? { text: '', entities: null }
            : toText(one.caption, 'sendMultiMedia')
          return {
            ...caption,
            asDocument: toFlag(one.asDocument, 'sendMultiMedia', 'asDocument'),
            fileName: toName(one.fileName, 'sendMultiMedia', 'fileName'),
          }
        })
        return [
          { peer: toSpec(peer), items: described, ...sendOptions(opts, 'sendMultiMedia') },
          files,
          toProgress(opts.onProgress, 'sendMultiMedia'),
        ]
      }).then(messages => messages.map(wrap))
    },

    editMessage(peer, messageId, text, options) {
      return startWrite(this, ops.editMessage, 'editMessage', () => {
        const opts = toOptions(options, 'editMessage')
        return [
          {
            peer: toSpec(peer),
            id: toMessageId(messageId, 'editMessage'),
            ...toText(text, 'editMessage'),
            noWebpage: toFlag(opts.noWebpage, 'editMessage', 'noWebpage'),
          },
          [],
          null,
        ]
      }).then(wrap)
    },

    deleteMessages(peer, messageIds, options) {
      return voidly(startWrite(this, ops.deleteMessages, 'deleteMessages', () => {
        const opts = toOptions(options, 'deleteMessages')
        return [
          {
            peer: toSpec(peer),
            ids: toMessageIds(messageIds, 'deleteMessages'),
            revoke: toFlag(opts.revoke, 'deleteMessages', 'revoke'),
          },
          [],
          null,
        ]
      }))
    },

    forwardMessages(fromPeer, messageIds, toPeer, options) {
      return startWrite(this, ops.forwardMessages, 'forwardMessages', () => {
        const opts = toOptions(options, 'forwardMessages')
        return [
          {
            peer: toSpec(fromPeer),
            toPeer: toSpec(toPeer),
            ids: toMessageIds(messageIds, 'forwardMessages'),
            topicId: toCount(opts.topicId, 'forwardMessages', 'topicId'),
            silent: toFlag(opts.silent, 'forwardMessages', 'silent'),
            scheduleDate: toCount(opts.scheduleDate, 'forwardMessages', 'scheduleDate'),
            dropAuthor: toFlag(opts.dropAuthor, 'forwardMessages', 'dropAuthor'),
            dropCaption: toFlag(opts.dropCaption, 'forwardMessages', 'dropCaption'),
          },
          [],
          null,
        ]
      }).then(messages => messages.map(wrap))
    },

    setReaction(peer, messageId, reactions, options) {
      return voidly(startWrite(this, ops.setReaction, 'setReaction', () => {
        const opts = toOptions(options, 'setReaction')
        return [
          {
            peer: toSpec(peer),
            id: toMessageId(messageId, 'setReaction'),
            reactions: toReactions(reactions, 'setReaction'),
            big: toFlag(opts.big, 'setReaction', 'big'),
          },
          [],
          null,
        ]
      }))
    },

    readHistory(peer, options) {
      return voidly(startWrite(this, ops.readHistory, 'readHistory', () => {
        const opts = toOptions(options, 'readHistory')
        return [
          {
            peer: toSpec(peer),
            maxId: toCount(opts.maxId, 'readHistory', 'maxId'),
            topicId: toCount(opts.topicId, 'readHistory', 'topicId'),
          },
          [],
          null,
        ]
      }))
    },

    sendTyping(peer, action, options) {
      return voidly(startWrite(this, ops.sendTyping, 'sendTyping', () => {
        const opts = toOptions(options, 'sendTyping')
        const what = action ?? 'typing'
        if (!TYPING_ACTIONS.has(what)) throw invalid(`sendTyping: unknown action '${what}'`)
        return [
          { peer: toSpec(peer), action: what, topicId: toCount(opts.topicId, 'sendTyping', 'topicId') },
          [],
          null,
        ]
      }))
    },

    setDraft(peer, draft, options) {
      return voidly(startWrite(this, ops.setDraft, 'setDraft', () => {
        const opts = toOptions(options, 'setDraft')
        // null clears it, which is a different call from setting an empty one
        const body = draft === null || draft === undefined ? { text: null, entities: null } : toText(draft, 'setDraft')
        return [
          {
            peer: toSpec(peer),
            ...body,
            topicId: toCount(opts.topicId, 'setDraft', 'topicId'),
            replyTo: toCount(opts.replyToMessageId, 'setDraft', 'replyToMessageId'),
          },
          [],
          null,
        ]
      }))
    },

    getMessageFile(message) {
      const slot = slotOf(this, 'getMessageFile')
      return natives.messageFile(slot, toRawMessage(message, 'getMessageFile'))
    },

    downloadMedia(message, options) {
      return startWrite(this, ops.downloadMedia, 'downloadMedia', () => {
        const opts = toOptions(options, 'downloadMedia')
        return [{}, [toRawMessage(message, 'downloadMedia')], toProgress(opts.onProgress, 'downloadMedia')]
      })
    },

    downloadMediaToFile(message, options) {
      return startWrite(this, ops.downloadMediaToFile, 'downloadMediaToFile', () => {
        const opts = toOptions(options, 'downloadMediaToFile')
        return [{}, [toRawMessage(message, 'downloadMediaToFile')], toProgress(opts.onProgress, 'downloadMediaToFile')]
      })
    },

    uploadFile(file, options) {
      return startWrite(this, ops.uploadFile, 'uploadFile', () => {
        const opts = toOptions(options, 'uploadFile')
        return [
          { fileName: toName(opts.fileName, 'uploadFile', 'fileName') },
          [toFile(file, 'uploadFile')],
          toProgress(opts.onProgress, 'uploadFile'),
        ]
      })
    },
  }

  // Extend the frozen read prototype so one Account supports reads and writes without coupling
  // their member lists. Set the prototype before freezing this object.
  if (readsPrototype !== null && readsPrototype !== undefined) Object.setPrototypeOf(proto, readsPrototype)

  return Object.freeze(proto)
}
