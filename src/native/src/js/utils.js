(utils, PluginError) => {
  const invalid = message => new PluginError('invalid-argument', message)

  // a legacy constructor is `<base>_<suffix>` (`message_old7`, `documentAttributeSticker_old2`,
  // `messageMediaDocument_layer197_2`) and no live TL name contains an underscore, so cutting at
  // the first one is what makes a check written against the modern name see every variant of it
  const baseName = (value) => {
    if (value === null || typeof value !== 'object') return ''
    const name = value._
    if (typeof name !== 'string') return ''
    const at = name.indexOf('_')
    return at === -1 ? name : name.slice(0, at)
  }

  // int64 fields cross the bridge as decimal strings; every id this api answers with is a number
  const toNumber = (value) => {
    if (typeof value === 'number') return Number.isFinite(value) ? value : null
    if (typeof value === 'string' && /^-?\d+$/.test(value)) return Number(value)
    return null
  }

  // null-prototype: a lookup keyed on a TL name would otherwise answer for 'constructor'
  const PEER_FIELDS = Object.assign(Object.create(null), {
    peerUser: ['user', 'user_id'],
    inputPeerUser: ['user', 'user_id'],
    inputPeerUserFromMessage: ['user', 'user_id'],
    inputUser: ['user', 'user_id'],
    inputUserFromMessage: ['user', 'user_id'],
    user: ['user', 'id'],
    userEmpty: ['user', 'id'],
    peerChat: ['chat', 'chat_id'],
    inputPeerChat: ['chat', 'chat_id'],
    chat: ['chat', 'id'],
    chatEmpty: ['chat', 'id'],
    chatForbidden: ['chat', 'id'],
    peerChannel: ['channel', 'channel_id'],
    inputPeerChannel: ['channel', 'channel_id'],
    inputPeerChannelFromMessage: ['channel', 'channel_id'],
    inputChannel: ['channel', 'channel_id'],
    inputChannelFromMessage: ['channel', 'channel_id'],
    channel: ['channel', 'id'],
    channelForbidden: ['channel', 'id'],
  })

  const ENTITIES = new Set(['user', 'chat', 'chatForbidden', 'channel', 'channelForbidden'])

  const describePeer = (value) => {
    const entry = PEER_FIELDS[baseName(value)]
    if (entry === undefined) return null
    const id = toNumber(value[entry[1]])
    return id === null ? null : { kind: entry[0], id }
  }

  const peerDialogId = (peer) => {
    const described = describePeer(peer)
    if (described === null) return null
    return described.kind === 'user' ? described.id : -described.id
  }

  const peerUserId = (peer) => {
    const described = describePeer(peer)
    return described !== null && described.kind === 'user' ? described.id : null
  }

  //
  // `reads.js` and `writes.js` share this one copy rather than each normalizing a peer its own way:
  // what a peer *is* is decided here, above the bridge, and only `S`/`D<id>`/`U<name>` ever crosses.
  // so a host parses no TL, a batch of peers is one crossing, and a write api is an op that passes
  // the same spec rather than a second resolver.

  const SEPARATOR = '\n'

  // telegram's own shape for a username, plus the '@' people paste in front of one. it is also what
  // keeps a spec free of the separator the batch ops join on
  const USERNAME = /^\w{1,32}$/
  const DIGITS = /^-?\d+$/

  const SELF = new Set(['inputPeerSelf', 'inputUserSelf'])

  const describe = peer => baseName(peer) || (peer === null ? 'null' : typeof peer)

  const toSpec = (peer) => {
    if (typeof peer === 'number') {
      if (!Number.isInteger(peer)) throw invalid(`not a dialog id: ${peer}`)
      return `D${peer}`
    }
    if (typeof peer === 'string') {
      if (peer === 'me' || peer === 'self') return 'S'
      // the decimal-string form keeps its digits rather than going through Number, which is the
      // one path where an id could arrive past 2^53 (int64 fields are strings on a TL snapshot)
      if (DIGITS.test(peer)) return `D${peer}`
      const username = peer.charCodeAt(0) === 64 ? peer.slice(1) : peer
      if (!USERNAME.test(username)) throw invalid(`not a username: ${peer}`)
      return `U${username.toLowerCase()}`
    }
    if (peer !== null && typeof peer === 'object') {
      const name = baseName(peer)
      if (SELF.has(name)) return 'S'
      if (name === 'user' && peer.self === true) return 'S'
      const id = peerDialogId(peer)
      if (id !== null && id !== 0) return `D${id}`
    }
    throw invalid(`not a peer: ${describe(peer)}`)
  }

  const toSpecList = (peers, what) => {
    if (!Array.isArray(peers)) throw invalid(`${what}: expected an array of peers`)
    return peers.map(peer => toSpec(peer)).join(SEPARATOR)
  }

  // telegram's ids, counts and dates are int32 on the wire; a wider value is refused rather than wrapped
  const INT32_MAX = 2147483647

  const toMessageId = (id, what) => {
    const value = toNumber(id)
    if (value === null || !Number.isInteger(value) || Math.abs(value) > INT32_MAX) {
      throw invalid(`${what}: message id must be a 32-bit integer`)
    }
    return value
  }

  const toMessageIds = (ids, what) => {
    if (!Array.isArray(ids)) throw invalid(`${what}: expected an array of message ids`)
    return ids.map(id => toMessageId(id, what))
  }

  // a TL field name is a java identifier, which is also what keeps one clear of the separators the
  // wire joins on. Whether the object *has* the field is the host's business: it carries what it
  // can and leaves the rest to the lazy read, so a name it does not know costs nothing but itself
  const FIELD_NAME = /^[A-Za-z_]\w{0,63}$/

  const toFieldNames = (value, what) => {
    if (value === undefined || value === null) return null
    if (!Array.isArray(value)) throw invalid(`${what}: fields must be an array of field names`)
    for (const name of value) {
      if (typeof name !== 'string') throw invalid(`${what}: fields must be strings`)
      if (!FIELD_NAME.test(name)) throw invalid(`${what}: not a field name: ${name}`)
    }
    return [...value]
  }

  const NO_OPTIONS = Object.freeze({})

  const toOptions = (options, what) => {
    if (options === undefined || options === null) return NO_OPTIONS
    if (typeof options !== 'object') throw invalid(`${what}: options must be an object`)
    return options
  }

  // 0 is "whatever the host's default is", which is what an omitted option means for every one of
  // these: the ceilings are telegram's and belong on the side that talks to it
  const toCount = (value, what, field) => {
    if (value === undefined || value === null) return 0
    const count = toNumber(value)
    if (count === null || !Number.isInteger(count) || count < 0 || count > INT32_MAX) {
      throw invalid(`${what}: ${field} must be a non-negative 32-bit integer`)
    }
    return count
  }

  // the account a method acts on is the handle it was called through, never anything captured: one
  // prototype serves every slot
  const slotOf = (account, what) => {
    const id = account === null || account === undefined ? undefined : account.id
    if (typeof id !== 'number' || !Number.isInteger(id)) {
      throw invalid(`${what}: not called on an account handle; use inu.account().${what}(...)`)
    }
    return id
  }

  // the bot api offsets channels and nothing else, which is the whole difference between the two
  // schemes: -1000000000000 - channel_id
  const BOT_API_CHANNEL_BASE = -1000000000000

  utils.peers = Object.freeze({
    toDialogId(peer) {
      const id = peerDialogId(peer)
      if (id === null) throw invalid(`toDialogId: not a peer: ${baseName(peer) || typeof peer}`)
      return id
    },

    parseDialogId(id) {
      const value = toNumber(id)
      if (value === null || value === 0) throw invalid(`parseDialogId: not a dialog id: ${id}`)
      return { type: value > 0 ? 'user' : 'chat', id: Math.abs(value) }
    },

    toInputPeer(userOrChat) {
      const name = baseName(userOrChat)
      if (name === 'user' && userOrChat.self === true) return { _: 'inputPeerSelf' }
      if (!ENTITIES.has(name) || describePeer(userOrChat) === null) {
        throw invalid(`toInputPeer: expected a user or a chat: ${name || typeof userOrChat}`)
      }
      // the id keeps whatever form it arrived in: a number on a snapshot, or a decimal string a plugin wrote
      const id = userOrChat.id
      const hash = userOrChat.access_hash ?? '0'
      if (name === 'user') return { _: 'inputPeerUser', user_id: id, access_hash: hash }
      if (name === 'chat' || name === 'chatForbidden') return { _: 'inputPeerChat', chat_id: id }
      return { _: 'inputPeerChannel', channel_id: id, access_hash: hash }
    },

    toBotApiId(peer) {
      const described = describePeer(peer)
      if (described === null) throw invalid(`toBotApiId: not a peer: ${baseName(peer) || typeof peer}`)
      if (described.kind === 'user') return described.id
      if (described.kind === 'chat') return -described.id
      return BOT_API_CHANNEL_BASE - described.id
    },

    fromBotApiId(id) {
      const value = toNumber(id)
      if (value === null || value === 0) throw invalid(`fromBotApiId: not a bot api id: ${id}`)
      // a basic group is -id in both schemes, so only the offset branch has anything to undo
      return value < BOT_API_CHANNEL_BASE ? value - BOT_API_CHANNEL_BASE : value
    },
  })

  Object.freeze(utils)

  return {
    baseName,
    toNumber,
    peerDialogId,
    peerUserId,
    SEPARATOR,
    invalid,
    toSpec,
    toSpecList,
    toMessageId,
    toMessageIds,
    toOptions,
    toCount,
    toFieldNames,
    slotOf,
  }
}
