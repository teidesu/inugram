// Evaluated once per engine by `rpc.rs`, which hands in the real `inu.Message` - read before any
// plugin source has run, so replacing `inu.Message` cannot decide what a handler is given. The
// factory returned takes one demuxed event kind plus the plugin's own callback and hands back the
// `onUpdate` listener the registry actually stores.
//
// Each kind is a narrowing of the raw update stream over a fixed constructor list and touches no
// host state: the arrival paths, the per-arrival dedup and the takeover filter all sit behind the
// update this is handed, so there is nothing here for a second one of them to disagree with.
((Message) => {
  // int64 fields cross the bridge as decimal strings, and a peer id is never 0
  const toId = (value) => {
    const id = Number(value)
    return Number.isSafeInteger(id) && id !== 0 ? id : null
  }

  // a TL vector reaches js as an array-like view rather than an Array, so this walks `length`
  // rather than iterating it
  const readIds = (value) => {
    if (value === null || typeof value !== 'object') return []
    const length = Number(value.length)
    if (!Number.isSafeInteger(length)) return []
    const ids = []
    for (let i = 0; i < length; i++) {
      const id = Number(value[i])
      if (Number.isSafeInteger(id)) ids.push(id)
    }
    return ids
  }

  const wrapMessage = callback => (update, account) => {
    const raw = update.message
    if (raw === null || typeof raw !== 'object') return
    callback(new Message(raw), account)
  }

  const WRAPPERS = Object.assign(Object.create(null), {
    new_message: wrapMessage,
    edit_message: wrapMessage,
    delete_message: callback => (update, account) => {
      const ids = readIds(update.messages)
      if (ids.length === 0) return
      // reading a field the constructor does not declare throws, so the channel form is asked for
      // rather than assumed. `updateDeleteMessages` carries no peer at all: a message id is unique
      // per account in the non-channel space, and resolving the dialog off one means a query
      // against the app's own message database that this dispatch cannot wait on
      const dialogId = 'channel_id' in update ? toId(update.channel_id) : null
      callback(dialogId === null ? null : -dialogId, ids, account)
    },
  })

  return (kind, callback) => WRAPPERS[kind](callback)
})
