((utils, PluginError) => {
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
  const USERNAME = /^[a-zA-Z0-9_]{1,32}$/
  const DIGITS = /^-?\d+$/

  const SELF = new Set(['inputPeerSelf', 'inputUserSelf'])

  const describe = (peer) => baseName(peer) || (peer === null ? 'null' : typeof peer)

  const toSpec = (peer) => {
    if (typeof peer === 'number') {
      if (!Number.isInteger(peer) || peer === 0) throw invalid(`not a dialog id: ${peer}`)
      return `D${peer}`
    }
    if (typeof peer === 'string') {
      if (peer === 'me' || peer === 'self') return 'S'
      // the decimal-string form keeps its digits rather than going through Number, which is the
      // one path where an id could arrive past 2^53 (int64 fields are strings on a TL snapshot)
      if (DIGITS.test(peer)) {
        if (/^-?0+$/.test(peer)) throw invalid(`not a dialog id: ${peer}`)
        return `D${peer}`
      }
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
    return peers.map((peer) => toSpec(peer)).join(SEPARATOR)
  }

  const toMessageId = (id, what) => {
    const value = toNumber(id)
    if (value === null || !Number.isInteger(value)) throw invalid(`${what}: message id must be an integer`)
    return String(value)
  }

  const toMessageIds = (ids, what) => {
    if (!Array.isArray(ids)) throw invalid(`${what}: expected an array of message ids`)
    return ids.map((id) => toMessageId(id, what))
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
    if (value === undefined || value === null) return '0'
    const count = toNumber(value)
    if (count === null || !Number.isInteger(count) || count < 0) {
      throw invalid(`${what}: ${field} must be a non-negative integer`)
    }
    return String(count)
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
      // the id keeps whatever form it arrived in, int64s being strings on a snapshot
      const id = userOrChat.id
      const hash = userOrChat.access_hash === undefined || userOrChat.access_hash === null
        ? '0'
        : userOrChat.access_hash
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

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
  const DAY_MS = 86400000

  const pad2 = value => (value < 10 ? `0${value}` : String(value))
  const day = date => `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`
  const clock = date => `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  const midnight = date => new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()

  // a number and nothing coercible to one: `Number('42')`/`Number(true)`/`Number([])` all succeed,
  // and formatting `0` for `null` is a wrong answer where `common.d.ts` promises an error
  const finite = (value, what) => {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw invalid(`${what}: not a number: ${typeof value === 'number' ? String(value) : typeof value}`)
    }
    return value
  }

  utils.formatDate = (unix, style) => {
    const date = new Date(Math.trunc(finite(unix, 'formatDate')) * 1000)
    switch (style === undefined ? 'dateTime' : style) {
      case 'date': return day(date)
      case 'time': return clock(date)
      case 'dateTime': return `${day(date)}, ${clock(date)}`
      case 'relative': {
        // whole days apart rather than elapsed seconds, so "today" ends at midnight the way a
        // dialog row's does. rounded, because a dst shift makes one of them 23 or 25 hours long
        const days = Math.round((midnight(new Date()) - midnight(date)) / DAY_MS)
        if (days === 0) return clock(date)
        if (days > 0 && days < 7) return WEEKDAYS[date.getDay()]
        return day(date)
      }
      default: throw invalid(`formatDate: unknown style '${style}'`)
    }
  }

  const COMPACT = [[1e9, 'B'], [1e6, 'M'], [1e3, 'K']]

  const compact = (value) => {
    const sign = value < 0 ? '-' : ''
    const abs = Math.abs(value)
    for (const [scale, suffix] of COMPACT) {
      if (abs < scale) continue
      // truncated, not rounded: 1999 is 1.9K, so a count never reads as one it has not reached
      const whole = Math.floor(abs / scale)
      const tenth = Math.floor((abs % scale) / (scale / 10))
      return tenth === 0 ? `${sign}${whole}${suffix}` : `${sign}${whole}.${tenth}${suffix}`
    }
    return `${sign}${Math.floor(abs)}`
  }

  utils.formatNumber = (value, options) => {
    const number = finite(value, 'formatNumber')
    if (options !== undefined && options !== null && options.compact === true) return compact(number)
    const sign = number < 0 ? '-' : ''
    const parts = String(Math.abs(number)).split('.')
    if (!/^\d+$/.test(parts[0])) return `${sign}${Math.abs(number)}`
    const grouped = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
    return parts.length === 1 ? `${sign}${grouped}` : `${sign}${grouped}.${parts[1]}`
  }

  const SIZES = [[1073741824, 'GB'], [1048576, 'MB'], [1024, 'KB']]

  utils.formatFileSize = (bytes) => {
    const value = finite(bytes, 'formatFileSize')
    const sign = value < 0 ? '-' : ''
    const abs = Math.abs(value)
    for (const [scale, suffix] of SIZES) {
      if (abs >= scale) return `${sign}${(abs / scale).toFixed(1)} ${suffix}`
    }
    return `${sign}${Math.round(abs)} B`
  }

  utils.formatDuration = (seconds) => {
    const total = Math.max(0, Math.floor(finite(seconds, 'formatDuration')))
    const s = total % 60
    const m = Math.floor(total / 60) % 60
    const h = Math.floor(total / 3600)
    return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`
  }

  Object.freeze(utils)

  return {
    baseName, toNumber, peerDialogId, peerUserId,
    SEPARATOR, invalid, toSpec, toSpecList, toMessageId, toMessageIds, toOptions, toCount, slotOf,
  }
})
