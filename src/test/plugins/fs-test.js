// ==InuPlugin==
// @name         fs test
// @author       teidesu
// @version      1.0
// @description  asserts inu.fs: the scoped directory, path normalization before containment, the quota
// @plugin-api   1
// @platform     android
// @grant        fs(64kb)
// ==/InuPlugin==

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

function expectThrow(label, code, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  check(label, error instanceof inu.PluginError && error.code === code, `${error.name}: ${error.code}`)
}

const text = new TextDecoder()
const bytes = new TextEncoder()

function main() {
  // -- a clean start, whatever a previous run left --

  for (const name of inu.fs.readdir('.')) inu.fs.rm(name, { recursive: true })
  check('the directory starts empty', inu.fs.readdir('.').length === 0 && inu.fs.usage() === 0)

  // -- round trips --

  inu.fs.write('notes.txt', bytes.encode('hello'))
  check('a file reads back', text.decode(inu.fs.read('notes.txt')) === 'hello', text.decode(inu.fs.read('notes.txt')))
  inu.fs.append('notes.txt', bytes.encode(' world'))
  check('append adds to the end', text.decode(inu.fs.read('notes.txt')) === 'hello world')
  inu.fs.append('fresh.txt', bytes.encode('made by append'))
  check('append creates what is missing', text.decode(inu.fs.read('fresh.txt')) === 'made by append')

  const raw = inu.fs.read('notes.txt')
  check('read hands back bytes', raw instanceof Uint8Array && raw.length === 11, raw.length)

  // -- a Blob is written without its content ever entering js --

  inu.fs.write('blob.bin', new Blob(['abc', new Uint8Array([100, 101]), 'f']))
  check('a Blob writes as its content', text.decode(inu.fs.read('blob.bin')) === 'abcdef', text.decode(inu.fs.read('blob.bin')))
  inu.fs.write('slice.bin', new Blob(['0123456789']).slice(3, 6))
  check('and a slice writes its own range', text.decode(inu.fs.read('slice.bin')) === '345')

  const dead = new Blob(['gone'])
  dead.dispose()
  expectThrow('a disposed Blob cannot be written', 'handle-expired', () => inu.fs.write('dead.bin', dead))
  check('and nothing was left behind', !inu.fs.exists('dead.bin'))

  // @ts-expect-error
  expectThrow('a string is not content', 'invalid-argument', () => inu.fs.write('x', 'a string'))
  // @ts-expect-error
  expectThrow('nor is an array', 'invalid-argument', () => inu.fs.write('x', [1, 2, 3]))

  // -- directories --

  inu.fs.mkdir('a/b/c')
  check('mkdir makes parents too', inu.fs.stat('a/b').isDirectory)
  inu.fs.write('a/b/c/two.txt', bytes.encode('2'))
  inu.fs.write('a/b/c/one.txt', bytes.encode('1'))
  check(
    'readdir gives names, sorted, without . or ..',
    JSON.stringify(inu.fs.readdir('a/b/c')) === '["one.txt","two.txt"]',
    JSON.stringify(inu.fs.readdir('a/b/c')),
  )

  const stat = inu.fs.stat('a/b/c/one.txt')
  check('stat tells a file from a directory', stat.isFile && !stat.isDirectory && stat.size === 1, stat.size)
  check('and carries times', stat.mtime > 0 && stat.ctime > 0, `${stat.mtime}/${stat.ctime}`)
  check('a directory says so', inu.fs.stat('a/b').isDirectory && !inu.fs.stat('a/b').isFile)

  expectThrow('a directory is not a file', 'invalid-argument', () => inu.fs.read('a/b'))
  expectThrow('removing a directory needs the flag', 'invalid-argument', () => inu.fs.rm('a'))
  inu.fs.rm('a', { recursive: true })
  check('and with it the whole tree goes', !inu.fs.exists('a'))
  inu.fs.rm('a')
  inu.fs.rm('never-existed')
  pass('rm is idempotent')

  // -- copy and move --

  inu.fs.copy('notes.txt', 'copy.txt')
  check('copy leaves both', inu.fs.exists('notes.txt') && text.decode(inu.fs.read('copy.txt')) === 'hello world')
  inu.fs.move('copy.txt', 'moved.txt')
  check('move leaves one', !inu.fs.exists('copy.txt') && text.decode(inu.fs.read('moved.txt')) === 'hello world')
  expectThrow('copying what is not there is not-found', 'not-found', () => inu.fs.copy('nope.txt', 'x.txt'))
  expectThrow('moving what is not there is not-found', 'not-found', () => inu.fs.move('nope.txt', 'x.txt'))
  expectThrow('reading what is not there is not-found', 'not-found', () => inu.fs.read('nope.txt'))
  expectThrow('stat on what is not there is not-found', 'not-found', () => inu.fs.stat('nope.txt'))
  check('exists answers instead of throwing', inu.fs.exists('nope.txt') === false)

  // -- normalization, then containment --

  // a path that leaves the directory and comes back is fine: what is checked is where it lands,
  // not whether it was spelled with a '..'
  inu.fs.mkdir('deep')
  inu.fs.write('deep/x.txt', bytes.encode('deep'))
  check("'..' that comes back resolves", text.decode(inu.fs.read('deep/../deep/x.txt')) === 'deep')
  check('doubled separators collapse', text.decode(inu.fs.read('deep//x.txt')) === 'deep')
  check("'.' segments collapse", text.decode(inu.fs.read('./deep/./x.txt')) === 'deep')

  for (const escape of ['../outside.txt', 'deep/../../outside.txt', '../../etc/hosts', 'deep/../deep/../../x']) {
    expectThrow(`'${escape}' cannot leave the directory`, 'not-granted', () => inu.fs.read(escape))
  }
  expectThrow('and neither can a write', 'not-granted', () => inu.fs.write('../outside.txt', bytes.encode('x')))
  expectThrow('nor a copy out', 'not-granted', () => inu.fs.copy('notes.txt', '../outside.txt'))
  expectThrow('nor a move out', 'not-granted', () => inu.fs.move('notes.txt', '../outside.txt'))
  expectThrow('nor an exists, which would be an oracle by itself', 'not-granted', () => inu.fs.exists('../'))

  for (const absolute of ['/etc/hosts', '/data/data/org.telegram.messenger/databases/cache4.db', '/']) {
    expectThrow(`'${absolute}' is refused as absolute`, 'not-granted', () => inu.fs.read(absolute))
  }

  expectThrow('an empty path names nothing', 'invalid-argument', () => inu.fs.read(''))
  expectThrow('and neither does one with a NUL', 'invalid-argument', () => inu.fs.read('a\u0000b'))
  expectThrow('the directory itself cannot be removed', 'invalid-argument', () => inu.fs.rm('.', { recursive: true }))
  check('so it is still there', inu.fs.stat('.').isDirectory)

  // -- the quota --

  const quota = inu.fs.quota()
  check('quota is what the manifest asked for', quota === 64 * 1024, quota)
  const before = inu.fs.usage()
  check('usage counts what is stored', before > 0 && before < quota, `${before}/${quota}`)

  inu.fs.write('sized.bin', new Uint8Array(1000))
  check('and follows a write', inu.fs.usage() === before + 1000, inu.fs.usage())
  inu.fs.write('sized.bin', new Uint8Array(10))
  check('a rewrite is charged for its difference', inu.fs.usage() === before + 10, inu.fs.usage())
  inu.fs.rm('sized.bin')
  check('and a remove gives it back', inu.fs.usage() === before, inu.fs.usage())

  expectThrow('a write past the cap is refused', 'quota-exceeded', () => {
    inu.fs.write('huge.bin', new Uint8Array(quota + 1))
  })
  check('having written nothing', !inu.fs.exists('huge.bin') && inu.fs.usage() === before, inu.fs.usage())

  let quotaError
  try {
    inu.fs.write('huge.bin', new Uint8Array(quota + 1))
  } catch (e) {
    quotaError = e
  }
  check(
    'and it names the numbers',
    quotaError.usage === before + quota + 1 && quotaError.quota === quota,
    `${quotaError.usage}/${quotaError.quota}`,
  )

  expectThrow('an append past the cap is refused too', 'quota-exceeded', () => {
    inu.fs.append('notes.txt', new Uint8Array(quota))
  })
  check('and appended nothing', inu.fs.stat('notes.txt').size === 11, inu.fs.stat('notes.txt').size)

  // -- the app's own directories --

  // they name absolute paths outside this plugin's root, so `fs` alone buys none of them: the
  // refusal is on the string, not on the read it would go on to fail
  for (const call of [
    () => inu.android.getPluginsDir(),
    () => inu.android.getCacheDir(),
    () => inu.android.getMediaDir('images'),
  ]) {
    expectThrow('an app directory needs unsafe.fs', 'not-granted', call)
  }

  // -- durability, as far as one run can tell --

  const marker = String(Date.now())
  inu.fs.write('marker.txt', bytes.encode(marker))
  check('what was written stays written', text.decode(inu.fs.read('marker.txt')) === marker)
  check('and shows up in the listing', inu.fs.readdir('.').includes('marker.txt'), JSON.stringify(inu.fs.readdir('.')))
}

try {
  main()
  console.log('fs test done')
} catch (e) {
  fail('fs test', (e && e.stack) || String(e))
}
