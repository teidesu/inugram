// ==UserScript==
// @name         intercept test
// @author       teidesu
// @namespace    inugram.dev
// @version      1.0
// @description  injects ads via interceptRpc
// @grant        inu.interceptRpc(help.getPromoData)
// @plugin-api   1
// @platform     android
// ==/UserScript==

console.log('init intercept help.getPromoData test')
inu.interceptRpc('help.getPromoData', (req) => {
  console.log('interceptRpc help.getPromoData')
  console.log({ ...req })
  console.log('meow 123')
  return ({
    _: 'help.promoData',
    expires: Math.round(Date.now() / 1000) + 60,
    custom_pending_suggestion: {
      _: 'pendingSuggestion',
      suggestion: '__INU__CUSTOM__',
      title: {
        _: 'textWithEntities',
        text: 'halooo',
        entities: [],
      },
      description: {
        _: 'textWithEntities',
        text: 'this suggestion was injected from inugram plugin',
        entities: [],
      },
      url: 'https://t.me/InugramCI',
    },
    pending_suggestions: [],
    dismissed_suggestions: [],
    chats: [],
    users: [],
  })
})
