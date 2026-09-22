// ==InuPlugin==
// @name         settings test
// @author       teidesu
// @version      1.0
// @description  settings ui test
// @grant        none
// @plugin-api   1
// @platform   android
// ==/InuPlugin==
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

inu.interceptRpc('messages.getHistory', async ({ request: req }, next) => {
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

inu.withCurrentAccount((account) => {
  const me = account.getMe()
  if (me === null || me._ !== 'user') return
  console.log(`logged in as ${me.first_name} (@${me.username ?? 'none'})`)

  const saved: tl.TypeDialog | null = account.getDialog('me')
  const top = saved === null || saved._ !== 'dialog' ? null : account.getMessagesCached(saved.peer, saved.top_message)
  if (top !== null) console.log(`last saved message: ${top.text.slice(0, 40)}`)

  const contacts: (tl.TypeUser | null)[] = account.getUsers(['me', '@durov', 777000])
  for (const contact of contacts) {
    if (contact === null || contact._ !== 'user') continue
    const dialogId: DialogId = inu.utils.peers.toDialogId(contact)
    console.log(`${dialogId}: ${contact.first_name ?? ''} ${account.getPeer(dialogId) === null ? '(gone)' : ''}`)
  }

  void (async () => {
    const peer: tl.TypeInputPeer = await account.resolvePeer('@telegram')
    const channel: tl.TypeInputChannel = await account.resolveChannel(peer)
    const cached: tl.TypeInputPeer | null = account.resolvePeerCached(peer)
    const user: tl.TypeUser | null = account.getUser(peer)
    console.log(`@telegram is ${channel._} / ${cached?._ ?? 'uncached'} / ${user?._ ?? 'no entity'}`)
  })()

  void (async () => {
    const history = await account.getHistory('me', { limit: 20, topicId: undefined })
    console.log(history.map(message => `#${message.id} ${message.text}`).join('\n'))

    let cursor: inu.Cursor<'dialogs'> | null = null
    let counted = 0
    do {
      const page: inu.Paged<tl.TypeDialog, 'dialogs'> = await account.getDialogs({
        folderId: 0,
        limit: 100,
        cursor: cursor ?? undefined,
      })
      counted += page.filter(dialog => dialog._ === 'dialog' && dialog.unread_count > 0).length
      cursor = page.next
    } while (cursor !== null)
    console.log(`${counted} unread`)

    const first = await account.getDialogs({ limit: 1 })
    if (first.next !== null) {
      // @ts-expect-error A cursor is valid only for its source list.
      void account.getTopics('@somewhere', { cursor: first.next })
    }
    // @ts-expect-error Only an API call can create a cursor.
    void account.getDialogs({ cursor: 'c1' })

    const topics = await account.getTopics('@somewhere')
    console.log(topics.map(topic => (topic._ === 'forumTopic' ? topic.title : 'deleted')).join(', '))

    const full: tl.TypeUserFull | null = await account.getUserFull('me')
    const chat: tl.TypeChatFull | null = await account.getChatFull('@somewhere')
    const draft: TextWithEntities | null = account.getDraft('me', { topicId: 7 })
    console.log(`${full?.about ?? 'no bio'} / ${chat?.about ?? 'no about'} / ${draft?.text ?? 'no draft'}`)
  })()

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
    await account.deleteMessages('me', forwarded.map(message => message.id), { revoke: true })

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
      onProgress: loaded => console.log(String(loaded)),
    })
    const album: inu.Message[] = await account.sendMultiMedia('me', [
      { file: uploaded, caption: 'one' },
      { file: { path: 'own.bin' }, fileName: 'two.bin', asDocument: true },
    ], { silent: true })
    console.log(`${album.length} in one bubble`)
  })()
})

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

inu.interceptRpc('messages.getSponsoredMessages', () => ({ _: 'messages.sponsoredMessagesEmpty' }), { strict: true })
inu.interceptRpc('contacts.getSponsoredPeers', () => ({ _: 'contacts.sponsoredPeersEmpty' }))
inu.interceptRpc('help.getPromoData', () => ({
  _: 'help.promoDataEmpty',
  expires: Math.floor(Date.now() / 1000) + 86400,
}))

inu.interceptSendMessage(async ({ message, account }) => {
  if (message.isEdit) return 'send'
  const text = message.text.text.trim()
  if (text === '/nope') return 'drop'
  if (text.startsWith('.')) {
    message.text = { text: text.slice(1), entities: [] }
    message.peer = account.userId
    message.silent = true
  }
  return 'send'
})

inu.interceptUpdate(['updateNewMessage', 'updateNewChannelMessage'], ({ update }) => {
  const message = new inu.Message(update.message)
  if (message.text.includes('spoilers ahead')) return 'drop'
  update.message.message = message.text.replace(/\bteh\b/g, 'the')
  return 'deliver'
})

const userConfigCls = inu.jvm.cls('org.telegram.messenger.UserConfig')
const returnTrueHook: inu.xposed.MethodHook = { before: ctx => ctx.setReturnValue(true) }

inu.xposed.hookMethod(userConfigCls.getDeclaredMethod('isPremium'), returnTrueHook)
inu.xposed.hookMethod(userConfigCls.getDeclaredMethod('hasPremiumOnAccounts'), returnTrueHook)
inu.xposed.hookMethod(
  inu.jvm.cls('org.telegram.messenger.MessagesController').getDeclaredMethod('premiumFeaturesBlocked'),
  { before: ctx => ctx.setReturnValue(false) },
)

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

const GradientSpan = inu.jvm.defineClass({
  superclass: inu.jvm.cls('android/text/style/CharacterStyle'),

  fields: {
    colors: 'int[]',
    width: 'float',
  },

  constructors: [{
    params: ['int[]', 'float'],
    super: [],
    init: (self, colors, width) => {
      self.setField('width', width)
      self.setField('colors', colors)
    },
  }],

  methods: {
    toString: (self: JavaObject) => `GradientSpan(${self.getField('width')}px)`,
  },

})

const span = new GradientSpan([0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF004DFF, 0xFF750787], 240)
console.log(GradientSpan.name, inu.jvm.callSuper(span, 'toString'))
