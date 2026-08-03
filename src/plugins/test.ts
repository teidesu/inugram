// ==UserScript==
// @name         settings test
// @author       teidesu
// @namespace    teidesu
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
