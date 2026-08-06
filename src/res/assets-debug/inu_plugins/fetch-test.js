// ==UserScript==
// @name         fetch test
// @author       teidesu
// @version      1.0
// @description  asserts fetch: the domain scope, the url shapes it refuses, Response, abort and timeout
// @plugin-api   1
// @platform     android
// @grant        fetch(example.com)
// ==/UserScript==

function pass(label, detail) {
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

async function expectReject(label, code, promise) {
  let error
  try {
    await promise
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not reject')
  check(label, error instanceof inu.PluginError && error.code === code, `${error.name}: ${error.code} ${error.message}`)
  return error
}

// the fake host behind this oracle answers `/refuse` with the address refusal, never answers
// `/never`, and answers everything else with the same json 200
const OK = 'https://example.com/thing'

async function main() {
  // -- the domain scope --

  const notGranted = await expectReject('a host outside the grant is refused', 'not-granted', fetch('https://evil.com/x'))
  check('and the error names the token that would allow it', notGranted.grant === 'fetch(evil.com)', notGranted.grant)

  await expectReject('a lookalike host is not the granted one', 'not-granted', fetch('https://notexample.com/x'))
  await expectReject('and neither is one that only mentions it', 'not-granted', fetch('https://evil.com/?to=example.com'))

  const sub = await fetch('https://api.example.com/x')
  check('a subdomain of the granted domain is covered', sub.status === 200, sub.status)
  const cased = await fetch('https://EXAMPLE.com:8443/x')
  check('and so is the same name in another case, on another port', cased.status === 200)

  // -- the url shapes that never leave --

  await expectReject(
    'a url whose host hides behind userinfo is refused',
    'invalid-argument',
    fetch('https://example.com@127.0.0.1/x'),
  )
  for (const url of ['file:///etc/hosts', 'content://media/external/1', 'ftp://example.com/x', 'not a url']) {
    await expectReject(`'${url}' is not something this api speaks`, 'invalid-argument', fetch(url))
  }

  // -- what the init may say --

  await expectReject(
    'a header the transport owns is refused',
    'invalid-argument',
    fetch(OK, { headers: { 'Content-Length': '10' } }),
  )
  await expectReject(
    'a header value with a line break in it is refused',
    'invalid-argument',
    fetch(OK, { headers: { 'X-A': 'a\r\nX-B: b' } }),
  )
  // @ts-expect-error
  await expectReject('an unknown redirect mode is refused', 'invalid-argument', fetch(OK, { redirect: 'ignore' }))
  await expectReject('a non-positive timeout is refused', 'invalid-argument', fetch(OK, { timeout: 0 }))
  // @ts-expect-error
  await expectReject('a body that is not content is refused', 'invalid-argument', fetch(OK, { method: 'POST', body: { a: 1 } }))

  const disposed = new Blob(['gone'])
  disposed.dispose()
  await expectReject('a disposed body blob is refused', 'handle-expired', fetch(OK, { method: 'POST', body: disposed }))

  // -- Response --

  const res = await fetch(OK, {
    method: 'post',
    headers: { 'X-One': 'a', 'X-Many': ['b', 'c'] },
    body: new Blob(['{"sent":true}']),
  })
  check('a 2xx response is ok', res.ok === true && res.status === 200, `${res.ok}/${res.status}`)
  check('statusText comes through', res.statusText === 'OK', res.statusText)
  check('url is the final one, not the one asked for', res.url === 'https://example.com/final', res.url)
  check('a header seen once is a string', res.headers['content-type'] === 'application/json', JSON.stringify(res.headers['content-type']))
  check(
    'a header that repeated is an array',
    Array.isArray(res.headers['set-cookie']) && res.headers['set-cookie'].join(',') === 'a=1,b=2',
    JSON.stringify(res.headers['set-cookie']),
  )

  const parsed = await res.json()
  check('json() parses the body', parsed.hello === 'world', JSON.stringify(parsed))
  const asText = await res.text()
  check('text() reads the same body again', asText === '{"hello":"world"}', asText)
  const asBytes = await res.bytes()
  check('bytes() is a Uint8Array of it', asBytes instanceof Uint8Array && asBytes.length === asText.length, asBytes.length)
  const buffer = await res.arrayBuffer()
  check('arrayBuffer() is the same length', buffer.byteLength === asText.length, buffer.byteLength)

  const body = await res.blob()
  check('blob() keeps the body on the app side', body instanceof Blob && body.size === asText.length, body.size)
  check('with the type the server said', body.type === 'application/json', body.type)
  check('and it slices like any other blob', (await body.slice(2, 7).text()) === 'hello', await body.slice(2, 7).text())

  // -- the failures the host decides --

  await expectReject(
    'an address the host refuses reaches the plugin as forbidden',
    'forbidden',
    fetch('https://example.com/refuse'),
  )

  // -- abort and timeout --

  const early = new AbortController()
  early.abort()
  await expectReject('a signal that already fired never sends', 'aborted', fetch(OK, { signal: early.signal }))

  const controller = new AbortController()
  const pending = fetch('https://example.com/never', { signal: controller.signal })
  controller.abort()
  await expectReject('aborting a request in flight rejects it', 'aborted', pending)

  const timedOut = await expectReject(
    'a request that never answers times out',
    'timed-out',
    fetch('https://example.com/never', { timeout: 250 }),
  )
  check('and says how long it waited', timedOut.message.includes('250'), timedOut.message)

  const settled = await fetch(OK, { timeout: 250 })
  check('while a request that answered cannot be timed out afterwards', settled.ok === true)
}

main().then(
  () => console.log('fetch test done'),
  e => fail('fetch test', (e && e.stack) || String(e)),
)
