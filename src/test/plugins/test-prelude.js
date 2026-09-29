/* eslint-disable unused-imports/no-unused-vars */
/* eslint-disable no-unused-vars */
let ran = 0

function pass(label, detail) {
  ran++
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  ran++
  console.error(`FAIL ${label}: ${detail}`)
}

function skip(label, why) {
  console.log(`SKIP ${label}: ${why}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function equals(label, actual, expected) {
  const same = JSON.stringify(actual) === JSON.stringify(expected)
  check(label, same, same ? JSON.stringify(actual) : `${JSON.stringify(actual)} != ${JSON.stringify(expected)}`)
}

/**
 * `want` is an `inu.PluginError` code, an error class, or `null` for any error.
 * `needle`, when given, must appear in the message.
 */
function checkError(label, want, error, needle) {
  const matches
    = want === null
      || (typeof want === 'string' ? error instanceof inu.PluginError && error.code === want : error instanceof want)
  const detail = error instanceof Error ? `${error.name}: ${error.code ?? ''} ${error.message}` : String(error)
  check(label, matches && (needle === undefined || String(error?.message).includes(needle)), detail)
}

function expectThrow(label, want, fn, needle) {
  try {
    fn()
  } catch (e) {
    checkError(label, want, e, needle)
    return e
  }
  fail(label, 'did not throw')
}

function expectDomException(label, name, fn) {
  try {
    fn()
  } catch (e) {
    check(label, e instanceof DOMException && e.name === name, `${e.name}: ${e.message}`)
    return e
  }
  fail(label, 'did not throw')
}

async function expectReject(label, want, pending, needle) {
  try {
    await (typeof pending === 'function' ? pending() : pending)
  } catch (e) {
    checkError(label, want, e, needle)
    return e
  }
  fail(label, 'did not reject')
}
