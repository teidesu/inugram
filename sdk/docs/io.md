# Network, files and storage

The engine offers web-shaped I/O: `fetch`, `Blob`, `File`, `localStorage`, timers. It also has
`inu.fs` for a private directory. They look like their web counterparts, but each one has
limits and rules the web does not. This page covers those.

## fetch

To make HTTP requests, you can use the `fetch` function, which is largely similar to the WHATWG Fetch.

You need the `fetch` grant, which can be scoped to a host (and all its subdomains): `fetch(api.example.com)`.

```ts
const res = await fetch('https://api.example.com/v1/dogs', {
  headers: { accept: 'application/json' },
  timeout: 10_000,
})
if (!res.ok) throw new Error(`HTTP ${res.status}`)
const dogs = await res.json()
```

### What differs

- HTTP auth is currently not supported
- Redirects are checked for grants, hop by hop.
- No `credentials` and similar options, since the app does not store credentials.
- The app proxy is currently not used.
- A non-standard `timeout` option which rejects with `timed-out`
- The response bodies are written to temporary files, and the `Response` reads from a `Blob` over that file.

## Blob and File

A `Blob` is immutable content, held in memory or in a file. This allows you to use content
that is larger than the allowed JavaScript heap limit. Semantically it is very similar to Web `Blob`.

### Disposing

Blobs are garbage collected, but a blob can hold a lot of memory or disk.
A non-standard `dispose()` (or `using`) exists to free it right away.

### Blobs backed by the app's files

`account.downloadMedia` returns a `File` backed by the app's cached copy of the media.
It costs no quota, but the app owns the file. If the file disappears (e.g. cleared cache/deleted message),
every read of the `File` rejects with `handle-expired`.

If you need the content later, copy it right away:

```ts
async function keepAttachment(account: inu.Account, message: inu.Message) {
  const file = await account.downloadMedia(message)
  inu.fs.write(`media/${message.id}`, file)
}
```

## inu.fs

`inu.fs` needs the `fs` grant and works in a private directory for your plugin. All calls are
synchronous.

```ts
inu.fs.mkdir('cache')
inu.fs.write('cache/state.json', new TextEncoder().encode(JSON.stringify({ n: 1 })))
const state = JSON.parse(new TextDecoder().decode(inu.fs.read('cache/state.json')))
```

### Paths

- Paths are relative to the private directory. `.`, `..` and doubled slashes are resolved first,
  and the result must stay inside the directory.
- You can only access files outside the scoped directory if you have the `unsafe.fs` grant.

### Quota

The directory holds 50 MB by default, but can be raised via a scope: `fs(200mb)`.

`usage()` and `quota()` report the effective numbers.

## localStorage

For simple key-value storage, use `localStorage`, which is pretty much the same as Web Storage.

`localStorage` is limited to 1 MB, counted as UTF-8.

A non-standard `setItems({...})` exists for batch insertions

## Timers

`setTimeout`, `setInterval`, `queueMicrotask` and their `clear*` functions work as on the web,
with these limits:

- at most 512 timeouts and intervals live at once,
- an interval runs at most every 4 ms,
- while the app is in the background, timers are throttled

Timers stop when the plugin starts unloading.

## Clipboard

To access the clipboard, the API provides `inu.clipboard.write(text)` and `inu.clipboard.read()`.

Both require the respective `clipboard.write` and `clipboard.read` grants.

## openUrl

`inu.openUrl(url)` needs the `openUrl` grant. It takes an `http`, `https` or `tg:` link:

- `tg:` and `t.me` links open inside the app, as if the user tapped them,
- `telegram.org` links open in the in-app browser,
- anything else goes to the system browser.

A URL with whitespace, control characters, a backslash or a username is refused. When the app
has no visible screen, nothing opens, since Android blocks starting activities from the
background.
