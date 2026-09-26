(utils, PluginError, text) => {

  const toSub = (value) => {
    if (typeof value === 'boolean' || !value) return null
    if (typeof value === 'string') return value
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : null
    if (typeof value === 'bigint') return value.toString(10)
    if (typeof value === 'object' && typeof value.text === 'string') {
      return { text: value.text, entities: value.entities ?? null }
    }
    return null
  }

  // the two call shapes `common.d.ts` declares: a tagged template, or one string already written in
  // the format. A template's `strings` is an array, and a plain call's argument is not
  const makeFormatter = (format) => {
    const parse = (strings, ...values) => {
      if (typeof strings === 'string') return text.parse(format, [strings], [])
      if (!Array.isArray(strings)) throw new PluginError('invalid-argument', 'expected a string or a template literal')
      return text.parse(format, [...strings], values.map(toSub))
    }
    parse.escape = (value, quote = false) => {
      if (typeof value !== 'string') throw new PluginError('invalid-argument', 'escape: expected a string')
      return text.escape(format, value, quote === true)
    }
    parse.unparse = (input) => {
      if (typeof input === 'string') return text.unparse(format, input, [])
      if (input === null || typeof input !== 'object' || typeof input.text !== 'string') {
        throw new PluginError('invalid-argument', 'unparse: expected a string or { text, entities }')
      }
      return text.unparse(format, input.text, input.entities)
    }
    return Object.freeze(parse)
  }

  utils.md = makeFormatter(0)
  utils.html = makeFormatter(1)
  utils.thtml = makeFormatter(2)

  const toTextPart = (value, what) => {
    if (typeof value === 'string') return { text: value, entities: null }
    if (value !== null && typeof value === 'object' && typeof value.text === 'string') {
      const entities = value.entities
      if (entities !== undefined && entities !== null && !Array.isArray(entities)) {
        throw new PluginError('invalid-argument', `${what}: entities must be an array`)
      }
      return { text: value.text, entities: entities ?? null }
    }
    throw new PluginError('invalid-argument', `${what}: expected a string or { text, entities }`)
  }

  utils.joinTextWithEntities = (parts, delim = '') => {
    if (!Array.isArray(parts)) throw new PluginError('invalid-argument', 'joinTextWithEntities: expected an array of texts')
    const separator = toTextPart(delim, 'joinTextWithEntities')
    const texts = []
    const entities = []
    let offset = 0
    const push = (part) => {
      texts.push(part.text)
      for (const entity of part.entities ?? []) {
        entities.push({ ...entity, offset: entity.offset + offset })
      }
      offset += part.text.length
    }
    for (const part of parts) {
      // mtcute's own rule: the delimiter goes in once something has been written, so a leading
      // empty part is not separated from what follows it
      if (offset > 0) push(separator)
      push(toTextPart(part, 'joinTextWithEntities'))
    }
    return { text: texts.join(''), entities }
  }

  // Legacy names use `<base>_<suffix>`, such as `message_old7`, `documentAttributeSticker_old2`, or
  // `messageMediaDocument_layer197_2`. Current TL names have no underscores, so stripping the
  // suffix matches all variants of a modern name.
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

  const ZERO_CHANNEL_ID = -1000000000000

  const getMarkedPeerId = (peer) => {
    const described = describePeer(peer)
    if (described === null) return null
    if (described.kind === 'user') return described.id
    return described.kind === 'chat' ? -described.id : ZERO_CHANNEL_ID - described.id
  }

  const peerUserId = (peer) => {
    const described = describePeer(peer)
    return described !== null && described.kind === 'user' ? described.id : null
  }

  // the one peer normalizer for reads and writes: only `S`/`D<id>`/`U<name>` crosses, so a host parses
  // no TL and a batch of peers is one crossing

  const SEPARATOR = '\n'

  // telegram's own shape for a username, plus the '@' people paste in front of one. it is also what
  // keeps a spec free of the separator the batch ops join on
  const USERNAME = /^\w{1,32}$/
  const DIGITS = /^-?\d+$/

  const SELF = new Set(['inputPeerSelf', 'inputUserSelf'])

  const describe = peer => baseName(peer) || (peer === null ? 'null' : typeof peer)

  const toSpec = (peer) => {
    if (typeof peer === 'number') {
      if (!Number.isInteger(peer)) throw new PluginError('invalid-argument', `not a marked peer id: ${peer}`)
      return `D${peer}`
    }
    if (typeof peer === 'string') {
      if (peer === 'me' || peer === 'self') return 'S'
      // the decimal-string form keeps its digits rather than going through Number, which is the
      // one path where an id could arrive past 2^53 (int64 fields are strings on a TL snapshot)
      if (DIGITS.test(peer)) return `D${peer}`
      const username = peer.charCodeAt(0) === 64 ? peer.slice(1) : peer
      if (!USERNAME.test(username)) throw new PluginError('invalid-argument', `not a username: ${peer}`)
      return `U${username.toLowerCase()}`
    }
    if (peer !== null && typeof peer === 'object') {
      const name = baseName(peer)
      if (SELF.has(name)) return 'S'
      if (name === 'user' && peer.self === true) return 'S'
      const id = getMarkedPeerId(peer)
      if (id !== null && id !== 0) return `D${id}`
    }
    throw new PluginError('invalid-argument', `not a peer: ${describe(peer)}`)
  }

  const toSpecList = (peers, what) => {
    if (!Array.isArray(peers)) throw new PluginError('invalid-argument', `${what}: expected an array of peers`)
    return peers.map(peer => toSpec(peer)).join(SEPARATOR)
  }

  // telegram's ids, counts and dates are int32 on the wire; a wider value is refused rather than wrapped
  const INT32_MAX = 2147483647

  const toMessageId = (id, what) => {
    const value = toNumber(id)
    if (value === null || !Number.isInteger(value) || Math.abs(value) > INT32_MAX) {
      throw new PluginError('invalid-argument', `${what}: message id must be a 32-bit integer`)
    }
    return value
  }

  const toMessageIds = (ids, what) => {
    if (!Array.isArray(ids)) throw new PluginError('invalid-argument', `${what}: expected an array of message ids`)
    return ids.map(id => toMessageId(id, what))
  }

  // Field names must be Java identifiers, which excludes wire separators. The host preloads
  // supported fields and leaves other names for lazy reads.
  const FIELD_NAME = /^[A-Z_]\w{0,63}$/i

  const toFieldNames = (value, what) => {
    if (value === undefined || value === null) return null
    if (!Array.isArray(value)) throw new PluginError('invalid-argument', `${what}: fields must be an array of field names`)
    for (const name of value) {
      if (typeof name !== 'string') throw new PluginError('invalid-argument', `${what}: fields must be strings`)
      if (!FIELD_NAME.test(name)) throw new PluginError('invalid-argument', `${what}: not a field name: ${name}`)
    }
    return [...value]
  }

  const NO_OPTIONS = Object.freeze({})

  const toOptions = (options, what) => {
    if (options === undefined || options === null) return NO_OPTIONS
    if (typeof options !== 'object') throw new PluginError('invalid-argument', `${what}: options must be an object`)
    return options
  }

  // 0 is "whatever the host's default is", which is what an omitted option means for every one of
  // these: the ceilings are telegram's and belong on the side that talks to it
  const toCount = (value, what, field) => {
    if (value === undefined || value === null) return 0
    const count = toNumber(value)
    if (count === null || !Number.isInteger(count) || count < 0 || count > INT32_MAX) {
      throw new PluginError('invalid-argument', `${what}: ${field} must be a non-negative 32-bit integer`)
    }
    return count
  }

  // the account a method acts on is the handle it was called through, never anything captured: one
  // prototype serves every slot
  const readAccountSlot = (account, what) => {
    const id = account === null || account === undefined ? undefined : account.id
    if (typeof id !== 'number' || !Number.isInteger(id)) {
      throw new PluginError('invalid-argument', `${what}: not called on an account handle; use inu.account().${what}(...)`)
    }
    return id
  }

  utils.peers = Object.freeze({
    getMarkedPeerId(peer) {
      const id = getMarkedPeerId(peer)
      if (id === null) throw new PluginError('invalid-argument', `getMarkedPeerId: not a peer: ${baseName(peer) || typeof peer}`)
      return id
    },

    parseMarkedPeerId(id) {
      const value = toNumber(id)
      if (value === null || value === 0) throw new PluginError('invalid-argument', `parseMarkedPeerId: not a marked peer id: ${id}`)
      if (value > 0) return { type: 'user', id: value }
      if (value < ZERO_CHANNEL_ID) return { type: 'channel', id: ZERO_CHANNEL_ID - value }
      return { type: 'chat', id: -value }
    },

    toInputPeer(userOrChat) {
      const name = baseName(userOrChat)
      if (name === 'user' && userOrChat.self === true) return { _: 'inputPeerSelf' }
      if (!ENTITIES.has(name) || describePeer(userOrChat) === null) {
        throw new PluginError('invalid-argument', `toInputPeer: expected a user or a chat: ${name || typeof userOrChat}`)
      }
      const id = userOrChat.id
      const hash = userOrChat.access_hash ?? '0'
      if (name === 'user') return { _: 'inputPeerUser', user_id: id, access_hash: hash }
      if (name === 'chat' || name === 'chatForbidden') return { _: 'inputPeerChat', chat_id: id }
      return { _: 'inputPeerChannel', channel_id: id, access_hash: hash }
    },

    toSimpleDialogId(peer) {
      const value = typeof peer === 'number' ? peer : getMarkedPeerId(peer)
      if (value === null || !Number.isInteger(value) || value === 0) {
        throw new PluginError('invalid-argument', `toSimpleDialogId: not a peer: ${baseName(peer) || peer}`)
      }
      return value < ZERO_CHANNEL_ID ? value - ZERO_CHANNEL_ID : value
    },
  })

  Object.freeze(utils)

  return {
    baseName,
    toNumber,
    getMarkedPeerId,
    peerUserId,
    SEPARATOR,
    toSpec,
    toSpecList,
    toTextPart,
    toMessageId,
    toMessageIds,
    toOptions,
    toCount,
    toFieldNames,
    readAccountSlot,
  }
}
