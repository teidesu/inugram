(shared) => {
  const { createLocalMediaObject, readLocalMediaId } = shared

  // the host hands a send over as plain values and takes it back the same way. A LocalMedia carries
  // its id only on the wire, and a kept item is named by where it was, so the host knows it for the app's own
  const wrap = middleware => async (context, raw) => {
    const media = raw.media.map(item => (item._ === 'localMedia' ? createLocalMediaObject(item, item.spoiler, context.account.id) : item))
    let text = raw.text
    const message = Object.seal({
      peer: raw.peer,
      get text() {
        return text
      },
      // a string is text without entities
      set text(value) {
        text = typeof value === 'string' ? { text: value, entities: [] } : value
      },
      reply: raw.reply,
      forward: raw.forward,
      topicId: raw.topicId,
      scheduleDate: raw.scheduleDate,
      silent: raw.silent,
      media,
    })
    const drawn = [...media]
    const verdict = await middleware({
      message,
      account: context.account,
      get signal() {
        return context.signal
      },
    })
    if (verdict === 'drop') return null
    if (verdict !== 'send') {
      // a path that returns nothing reads as the plugin's bug, never as consent to send
      throw new Error(`interceptSendMessage: expected 'send' or 'drop', got ${JSON.stringify(verdict)}`)
    }
    const items = message.media.map((item) => {
      const kept = drawn.indexOf(item)
      const id = readLocalMediaId(item)
      if (id === undefined) return { kept, tl: item }
      return { kept, local: { id, kind: item.kind, name: item.name ?? '', mimeType: item.mimeType, spoiler: item.spoiler } }
    })
    return { ...message, text, media: items }
  }

  return wrap
}
