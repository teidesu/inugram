// ==UserScript==
// @name         settings test
// @author       teidesu
// @version      1.0
// @description  settings ui test
// @grant        none
// @plugin-api   1
// @platform   android
// ==/UserScript==
/// <reference path="./index.d.ts" />

const { ui } = inu
inu.registerSettings(ui.settingsPage({
  title: 'settings test',
  items: () => [
    ui.header('header'),
    ui.check({
      text: 'check',
      checked: true,
      onChange: (checked) => {
        console.log('check', checked)
      },
      onSecondaryClick: () => {
        console.log('secondary click')
      },
    }),
    ui.button({
      text: 'button',
      onClick: () => {
        console.log('button click')
      },
      onSecondaryClick: () => {
        console.log('secondary click')
      },
    }),
    ui.select({
      text: 'select',
      items: ['item1', 'item2', 'item3'],
      selected: 0,
      onChange: (value) => {
        console.log('select', value)
      },
      onSecondaryClick: () => {
        console.log('secondary click')
      },
    }),
    ui.slider({
      min: 0,
      max: 100,
      default: 50,
      value: 50,
      step: 1,
      text: 'slider',
      label: value => `${value}%`,
      onChange: (value) => {
        console.log('slider', value)
      },
    }),
    ui.separator(),
    ui.header('header2'),
  ],
}))

// a message summary line, which is what the Message wrapper and `utils` are for between them:
// every branch below is a getter whose union has to narrow, and every id crossing into `utils.peers`
// has to be the type that side declares
inu.interceptRpc('messages.getHistory', async (req, next) => {
  const history = await next(req)
  if (history === null || history._ === 'messages.messagesNotModified') return history
  for (const raw of history.messages ?? []) {
    const message = new inu.Message(raw)
    const dialogId: DialogId | null = message.dialogId
    const peer = raw.peer_id
    const where = dialogId === null || peer === undefined
      ? 'a secret chat'
      : `${inu.utils.peers.parseDialogId(dialogId).type} ${inu.utils.peers.toBotApiId(peer)}`
    const what = message.mediaType === null
      ? JSON.stringify(message.text.slice(0, 20))
      : `[${message.mediaType}${message.duration === null ? '' : ` ${inu.utils.formatDuration(message.duration)}`}]`
    console.log(`${inu.utils.formatDate(message.date, 'relative')} ${where} #${message.id} ${what}`)
  }
  return history
})

// the cache getters, whose whole ergonomic claim is that one `InputPeerLike` names a peer however
// you happen to be holding it: an id, a username, a `Peer` off a message, an entity you just read
inu.withCurrentAccount((account) => {
  const me = account.getMe()
  if (me === null || me._ !== 'user') return
  console.log(`logged in as ${me.first_name} (@${me.username ?? 'none'})`)

  const saved: tl.TypeDialog | null = account.getDialog('me')
  const top = saved === null || saved._ !== 'dialog' ? null : account.getMessage(saved.peer, saved.top_message)
  if (top !== null) console.log(`last saved message: ${top.text.slice(0, 40)}`)

  // one crossing, misses in place, and the element type is the nullable one
  const contacts: (tl.TypeUser | null)[] = account.getUsers(['me', '@durov', 777000])
  for (const contact of contacts) {
    if (contact === null || contact._ !== 'user') continue
    const dialogId: DialogId = inu.utils.peers.toDialogId(contact)
    console.log(`${dialogId}: ${contact.first_name ?? ''} ${account.getPeer(dialogId) === null ? '(gone)' : ''}`)
  }

  // the resolver every write in the next phase goes through: cache-first, and narrowing is a
  // different call rather than a cast
  void (async () => {
    const peer: tl.TypeInputPeer = await account.resolvePeer('@telegram')
    const channel: tl.TypeInputChannel = await account.resolveChannel(peer)
    // an input peer in hand is the answer, so this one costs nothing and cannot miss
    const cached: tl.TypeInputPeer | null = account.resolvePeerCached(peer)
    const user: tl.TypeUser | null = account.getUser(peer)
    console.log(`@telegram is ${channel._} / ${cached?._ ?? 'uncached'} / ${user?._ ?? 'no entity'}`)
  })()

  // the reads that may go to the network: a page has to be usable as the array it is, and a cursor
  // has to be usable only where it came from
  void (async () => {
    const history = await account.getHistory('me', { limit: 20, topicId: undefined })
    console.log(history.map((message) => `#${message.id} ${message.text}`).join('\n'))

    let cursor: inu.Cursor<'dialogs'> | null = null
    let counted = 0
    do {
      const page: inu.Paged<tl.TypeDialog, 'dialogs'> = await account.getDialogs({
        folderId: 0,
        limit: 100,
        cursor: cursor ?? undefined,
      })
      counted += page.filter((dialog) => dialog._ === 'dialog' && dialog.unread_count > 0).length
      cursor = page.next
    } while (cursor !== null)
    console.log(`${counted} unread`)

    const first = await account.getDialogs({ limit: 1 })
    if (first.next !== null) {
      // @ts-expect-error the brand is what stops a cursor being paged against the wrong list
      void account.getTopics('@somewhere', { cursor: first.next })
    }
    // @ts-expect-error and what stops one being invented rather than handed back
    void account.getDialogs({ cursor: 'c1' })

    const topics = await account.getTopics('@somewhere')
    console.log(topics.map((topic) => (topic._ === 'forumTopic' ? topic.title : 'deleted')).join(', '))

    const full: tl.TypeUserFull | null = await account.getUserFull('me')
    const chat: tl.TypeChatFull | null = await account.getChatFull('@somewhere')
    // the one read in this group that is not a promise, because a draft is never fetched
    const draft: TextWithEntities | null = account.getDraft('me', { topicId: 7 })
    console.log(`${full?.about ?? 'no bio'} / ${chat?.about ?? 'no about'} / ${draft?.text ?? 'no draft'}`)
  })()

  // the writes, which take the same `InputPeerLike` as everything above and answer with the wrapper
  // the events hand over - so a plugin that reads a message and sends one uses one type either way
  void (async () => {
    const sent: inu.Message = await account.sendMessage('me', { text: 'hi', entities: [] }, {
      replyToMessageId: 7,
      silent: true,
      clearDraft: true,
    })
    const edited: inu.Message = await account.editMessage('me', sent.id, 'hi again', { noWebpage: true })
    await account.setReaction('me', edited.id, ['👍', { customEmojiId: '5361751237382052539' }], { big: true })
    await account.readHistory('me', { maxId: edited.id })
    await account.sendTyping('me', 'chooseSticker')
    await account.setDraft('me', { text: 'unsent', entities: [] }, { replyToMessageId: edited.id })
    await account.setDraft('me', null)
    const forwarded: inu.Message[] = await account.forwardMessages('me', [edited.id], '@somewhere', { dropAuthor: true })
    await account.deleteMessages('me', forwarded.map((message) => message.id), { revoke: true })

    // download -> send, which is the flow the `File` return type exists for: the name rides along
    // and neither half needs an `fs` grant
    const media = await account.getHistory('@somewhere', { limit: 1 })
    const first = media[0]
    if (first !== undefined) {
      const where: { path: string, exists: boolean } | null = account.getMessageFile(first)
      if (where !== null && !where.exists) {
        const file: File = await account.downloadMedia(first, {
          onProgress: (loaded, total) => console.log(`${loaded}/${total}`),
        })
        await account.sendMedia('me', file, { caption: 'here you go' })
        const saved: { path: string } = await account.downloadMediaToFile(first)
        console.log(saved.path)
      }
    }

    const uploaded: tl.TypeInputFile = await account.uploadFile(new Uint8Array([1, 2, 3]), {
      fileName: 'three.bin',
      onProgress: (loaded) => console.log(String(loaded)),
    })
    const album: inu.Message[] = await account.sendMultiMedia('me', [
      { file: uploaded, caption: 'one' },
      { file: { path: 'own.bin' }, fileName: 'two.bin', asDocument: true },
    ], { silent: true })
    console.log(`${album.length} in one bubble`)
  })()
})

// the demuxed events, which have to narrow the same way off a payload the host built rather than
// one this file constructed - and `onMessageDeleted`, whose dialog id is the one nullable in the group
const seen = inu.onNewMessage((message, account) => {
  if (!account.isCurrent()) return
  const dialogId: DialogId | null = message.dialogId
  console.log(`+ #${message.id} in ${dialogId ?? 'a secret chat'}: ${message.text}`)
})
inu.onMessageEdited((message) => {
  console.log(`~ #${message.id} at ${inu.utils.formatDate(message.editDate ?? message.date, 'time')}`)
})
inu.onMessageDeleted((dialogId, messageIds, account) => {
  const where: string = dialogId === null ? 'an unknown dialog' : `${inu.utils.peers.parseDialogId(dialogId).type} ${dialogId}`
  console.log(`- ${messageIds.length} message(s) in ${where} on account ${account.id}`)
})
inu.onUnload(seen)

// disable ads
// inu.interceptRpc(async (req, next) => {
//   if (req._ === 'messages.getSponsoredMessages') {
//     return { _: 'messages.sponsoredMessagesEmpty' }
//   }
//   if (req._ === 'contacts.getSponsoredPeers') {
//     return { _: 'contacts.sponsoredPeersEmpty' }
//   }
//   if (req._ === 'help.getPromoData') {
//     return { _: 'help.promoDataEmpty' }
//   }
//   return next(req)
// })
inu.interceptRpc('messages.getSponsoredMessages', () => ({ _: 'messages.sponsoredMessagesEmpty' }))
inu.interceptRpc('contacts.getSponsoredPeers', () => ({ _: 'contacts.sponsoredPeersEmpty' }))
// `expires` is when the client may re-ask; a day out keeps it from refetching in a loop
inu.interceptRpc('help.getPromoData', () => ({
  _: 'help.promoDataEmpty',
  expires: Math.floor(Date.now() / 1000) + 86400,
}))

// the two verdict chains. neither has a `next()`, so the type has to make the choice total: a path
// that falls off the end is a compile error under `strict` rather than a dropped send
inu.interceptSendMessage(async (message, account) => {
  if (message.isEdit) return 'send'
  const text = message.text.text.trim()
  if (text === '/nope') return 'drop'
  if (text.startsWith('.')) {
    // silently, and to my own saved messages instead
    message.text = { text: text.slice(1), entities: [] }
    message.peer = account.userId
    message.silent = true
  }
  return 'send'
})

// narrowed to its constructor list, so `update.message` is a Message and not a union of everything
inu.interceptUpdate(['updateNewMessage', 'updateNewChannelMessage'], (update) => {
  const message = new inu.Message(update.message)
  if (message.text.includes('spoilers ahead')) return 'drop'
  update.message.message = message.text.replace(/\bteh\b/g, 'the')
  return 'deliver'
})

// local premium via hooking
const userConfigCls = inu.jvm.cls('org.telegram.messenger.UserConfig')
const returnTrueHook: inu.xposed.MethodHook = { before: ctx => ctx.setReturnValue(true) }

inu.xposed.hookMethod(userConfigCls.getDeclaredMethod('isPremium'), returnTrueHook)
inu.xposed.hookMethod(userConfigCls.getDeclaredMethod('hasPremiumOnAccounts'), returnTrueHook)
inu.xposed.hookMethod(
  inu.jvm.cls('org.telegram.messenger.MessagesController').getDeclaredMethod('premiumFeaturesBlocked'),
  { before: ctx => ctx.setReturnValue(false) },
)

// local premium via deserialization interception
// inu.interceptDeserialize((obj) => {
//   if (obj._ === 'user' && obj.self) {
//     obj.premium = true
//   }
//   return obj
// })
inu.interceptDeserialize([{
  type: 'user',
  when: { self: true },
  set: { premium: true },
}])

// disable FLAG_SECURE via hooking
const FLAG_SECURE = 0x00002000

inu.xposed.hookMethod(inu.jvm.cls('android.view.Window').getDeclaredMethod('setFlags(II)V'), {
  before: (ctx) => {
    const flags = ctx.args[0] as number
    const mask = ctx.args[1] as number
    if (mask & FLAG_SECURE) {
      const newFlags = flags & ~FLAG_SECURE
      ctx.args[0] = newFlags
    }
  },
})
inu.xposed.hookMethod(inu.jvm.cls('android.view.Window').getDeclaredMethod('setAttributes'), {
  before: (ctx) => {
    const params = ctx.args[0] as JavaObject & { flags: number }
    if (params.flags & FLAG_SECURE) {
      params.flags = params.flags & ~FLAG_SECURE
    }
  },
})
inu.xposed.hookMethod(inu.jvm.cls('android.view.WindowManagerImpl').getDeclaredMethod('addView'), {
  before: (ctx) => {
    const params = ctx.args[1] as JavaObject & { flags: number }
    if (params.flags & FLAG_SECURE) {
      params.flags = params.flags & ~FLAG_SECURE
    }
  },
})
inu.xposed.hookMethod(inu.jvm.cls('android.view.WindowManagerImpl').getDeclaredMethod('updateViewLayout'), {
  before: (ctx) => {
    const params = ctx.args[1] as JavaObject & { flags: number }
    if (params.flags & FLAG_SECURE) {
      params.flags = params.flags & ~FLAG_SECURE
    }
  },
})
inu.xposed.hookMethod(
  inu.jvm.cls('org.telegram.messenger.FlagSecureReason').getDeclaredMethod('attach'),
  { before: ctx => ctx.setReturnValue(null) },
)

// disable FLAG_SECURE via deserialization interception (remove noforwards)
// inu.interceptDeserialize((obj) => {
//   if (obj._ === 'userFull') {
//     obj.noforwards_my_enabled = false
//     obj.noforwards_peer_enabled = false
//   } else if (obj._ === 'channel' || obj._ === 'message' || obj._ === 'storyItem') {
//     obj.noforwards = false
//   }
//   return obj
// })
inu.interceptDeserialize([{
  type: 'userFull',
  set: { noforwards_my_enabled: false, noforwards_peer_enabled: false },
}, {
  type: ['channel', 'message', 'storyItem'],
  set: { noforwards: false },
}])

// --- worked example: pride-gradient text span (ergonomics pressure-test) ---
// a runtime subclass of CharacterStyle. updateDrawState installs a horizontal
// gradient shader across the span. the state the draw method needs (colors, width)
// lives in real dex fields — a hot body can't reach the js heap.
const GradientSpan = inu.jvm.defineClass('my/plugin/GradientSpan', {
  superclass: inu.jvm.cls('android/text/style/CharacterStyle'),

  fields: {
    colors: 'int[]',
    width: 'float',
  },

  constructors: [{
    params: ['int[]', 'float'],
    // CharacterStyle has only an implicit no-arg super ctor -> nothing to forward.
    super: [],
    init: (self, colors, width) => {
      // cold (plain js): seed the real dex fields the hot method will read.
      self.setField('width', width)
      self.setField('colors', colors)
    },
  }],

  // cold override — toString is called ~never, a js jump is free.
  methods: {
    toString: (self: JavaObject) => `GradientSpan(${self.getField('width')}px)`,
  },

  // hot override — runs inside text draw, so it must be native dex.
  // NOT YET IMPLEMENTED (needs the js->dalvik compiler); kept here as the spec of
  // what that compiler must accept. note how the body only touches: params (paint),
  // self-field reads (self.width, self.colors), and explicitly named ctors.
  // no closures, no js allocation, no async — that's the whole restricted subset.
  // hot: {
  //   'updateDrawState(Landroid/text/TextPaint;)V': {
  //     params: ['android/text/TextPaint'],
  //     returns: 'void',
  //     body: (self, paint) => {
  //       const shader = new this.cls('android/graphics/LinearGradient')(0, 0, self.width, 0, self.colors, null, this.cls('android/graphics/Shader$TileMode').CLAMP)
  //       paint.setShader(shader)
  //     },
  //   },
  // },

  // todo: we should probably just use smali-to-dex here lol.
})

// usage: splice `new GradientSpan(PRIDE, widthPx)` over a word range in a Spannable.
const span = new GradientSpan([0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF004DFF, 0xFF750787], 240)
console.log(inu.jvm.callSuper(span, 'toString')) // super impl, bypassing our override
