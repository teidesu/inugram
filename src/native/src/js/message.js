((shared, PluginError) => {
  const { baseName, toNumber, peerDialogId, peerUserId } = shared

  const MEDIA_TYPES = Object.assign(Object.create(null), {
    messageMediaPhoto: 'photo',
    messageMediaPoll: 'poll',
    messageMediaContact: 'contact',
    messageMediaGeo: 'location',
    messageMediaGeoLive: 'location',
    messageMediaVenue: 'venue',
    messageMediaStory: 'story',
    messageMediaGiveaway: 'giveaway',
    messageMediaGiveawayResults: 'giveaway',
    messageMediaInvoice: 'invoice',
  })

  // `DialogObject.isEncryptedDialog`: the app tags a secret chat's dialog id with a bit no real
  // peer id has. it is the only marker a secret *service* message carries, `TL_message_secret`
  // being the class of ordinary ones alone
  const ENCRYPTED_DIALOG_BIT = 0x4000000000000000n

  const toBigInt = (value) => {
    if (typeof value === 'bigint') return value
    if (typeof value === 'number') return Number.isSafeInteger(value) ? BigInt(value) : null
    if (typeof value === 'string' && /^-?\d+$/.test(value)) return BigInt(value)
    return null
  }

  const isEncryptedDialogId = (value) => {
    const id = toBigInt(value)
    return id !== null && id > 0n && (id & ENCRYPTED_DIALOG_BIT) !== 0n
  }

  const orNull = value => (value === undefined ? null : value)

  const documentOf = (media) => {
    if (media === null || baseName(media) !== 'messageMediaDocument') return null
    const document = orNull(media.document)
    return document === null || baseName(document) === 'documentEmpty' ? null : document
  }

  // stock spreads this across isVoice()/isMusic()/isSticker()/isRoundVideo()/isGif(); the order is
  // theirs, and it matters - a webm sticker carries a video attribute too, and an animated sticker
  // carries the animated one
  const documentMediaType = (document) => {
    if (document === null) return 'document'
    let video = false
    let animated = false
    for (const attribute of orNull(document.attributes) || []) {
      switch (baseName(attribute)) {
        case 'documentAttributeSticker':
        case 'documentAttributeCustomEmoji':
          return 'sticker'
        case 'documentAttributeAudio':
          return attribute.voice === true ? 'voice' : 'music'
        case 'documentAttributeVideo':
          if (attribute.round_message === true) return 'roundVideo'
          video = true
          break
        case 'documentAttributeAnimated':
          animated = true
          break
      }
    }
    if (animated) return 'gif'
    if (video) return 'video'
    return 'document'
  }

  const documentDuration = (document) => {
    for (const attribute of orNull(document.attributes) || []) {
      const name = baseName(attribute)
      if (name === 'documentAttributeAudio' || name === 'documentAttributeVideo') {
        return toNumber(attribute.duration)
      }
    }
    return null
  }

  class Message {
    constructor(raw) {
      if (raw === null || typeof raw !== 'object') {
        throw new PluginError('invalid-argument', `Message: expected a raw TL message, got ${typeof raw}`)
      }
      Object.defineProperty(this, 'raw', { value: raw, enumerable: true })
    }

    get id() {
      return toNumber(this.raw.id) ?? 0
    }

    get isSecret() {
      const name = this.raw._
      if (typeof name === 'string' && name.startsWith('message_secret')) return true
      return isEncryptedDialogId(this.raw.dialog_id)
    }

    get dialogId() {
      if (this.isSecret) return null
      const annotated = toBigInt(this.raw.dialog_id)
      if (annotated !== null && annotated !== 0n) return Number(annotated)
      return peerDialogId(this.raw.peer_id)
    }

    get senderId() {
      const from = peerDialogId(this.raw.from_id)
      if (from !== null) return from
      // `from_id` is flags.8?Peer and the server omits it in a 1:1 dialog, where an incoming
      // message's sender is the dialog peer. outgoing, it is whoever we are, which is not
      // something a pure function of `raw` can know
      if (this.raw.out === true) return null
      return peerUserId(this.raw.peer_id)
    }

    get topicId() {
      const replyTo = orNull(this.raw.reply_to)
      if (replyTo === null || replyTo.forum_topic !== true) return null
      return toNumber(replyTo.reply_to_top_id) ?? toNumber(replyTo.reply_to_msg_id)
    }

    get date() {
      return toNumber(this.raw.date) ?? 0
    }

    get editDate() {
      return toNumber(this.raw.edit_date)
    }

    get out() {
      return this.raw.out === true
    }

    get text() {
      const text = this.raw.message
      return typeof text === 'string' ? text : ''
    }

    get textWithEntities() {
      const entities = orNull(this.raw.entities)
      return entities === null ? { text: this.text } : { text: this.text, entities }
    }

    get media() {
      const media = orNull(this.raw.media)
      return media === null || baseName(media) === 'messageMediaEmpty' ? null : media
    }

    get document() {
      return documentOf(this.media)
    }

    get mediaType() {
      const media = this.media
      if (media === null) return null
      const name = baseName(media)
      if (name === 'messageMediaDocument') return documentMediaType(documentOf(media))
      return MEDIA_TYPES[name] ?? 'other'
    }

    get duration() {
      const document = this.document
      return document === null ? null : documentDuration(document)
    }

    get groupedId() {
      const grouped = orNull(this.raw.grouped_id)
      return grouped === null ? null : String(grouped)
    }

    get replyToMessageId() {
      const replyTo = orNull(this.raw.reply_to)
      if (replyTo === null) return null
      // in a forum, a message that merely *sits* in a topic carries the topic's own root id here
      // and nothing beside it; a real reply carries the replied-to id here and the topic's root in
      // `reply_to_top_id`. so the header alone says which of the two this is
      if (replyTo.forum_topic === true && toNumber(replyTo.reply_to_top_id) === null) return null
      return toNumber(replyTo.reply_to_msg_id)
    }

    get forwardedFrom() {
      return orNull(this.raw.fwd_from)
    }

    get viaBotId() {
      return toNumber(this.raw.via_bot_id)
    }

    get isPinned() {
      return this.raw.pinned === true
    }

    get views() {
      return toNumber(this.raw.views)
    }

    get forwards() {
      return toNumber(this.raw.forwards)
    }

    get reactions() {
      return orNull(this.raw.reactions)
    }

    get isService() {
      return baseName(this.raw) === 'messageService'
    }

    toJSON() {
      return this.raw
    }
  }

  return Message
})
