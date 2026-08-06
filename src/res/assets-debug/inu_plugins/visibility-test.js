// ==UserScript==
// @name         visibility test
// @author       teidesu
// @version      1.0
// @description  asserts onAppVisibilityChange reports transitions only and that timers throttle while hidden
// @grant        onAppVisibilityChange
// @plugin-api   1
// @platform     android
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

const PERIOD_MS = 100
let ticks = 0
setInterval(() => ticks++, PERIOD_MS)

let heard = 0
let last = null
let disposed = false
let hiddenAt = 0
let ticksAtHide = 0

const disposer = inu.onAppVisibilityChange((mode) => {
  if (disposed) return fail('a disposed callback fired again', mode)

  heard++
  check('the mode is one of the two documented values', mode === 'foreground' || mode === 'background', mode)
  if (last !== null) check('transitions only: never the same state twice', mode !== last, `${last} -> ${mode}`)
  last = mode

  if (mode === 'background') {
    hiddenAt = performance.now()
    ticksAtHide = ticks
  } else if (hiddenAt !== 0) {
    const hiddenFor = performance.now() - hiddenAt
    const ran = ticks - ticksAtHide
    // hidden, the whole wheel gets one tick a second (one a minute past five), and everything due
    // at it fires together - so a 100ms interval that ran ten times a second is the regression
    const allowed = Math.ceil(hiddenFor / 1000) + 2
    check(
      `a ${PERIOD_MS}ms interval is throttled while the app is hidden`,
      ran <= allowed,
      `${ran} tick(s) over ${Math.round(hiddenFor)}ms, at most ${allowed}`,
    )

    const resumedAt = ticks
    setTimeout(() => {
      const resumed = ticks - resumedAt
      check(
        'the interval runs at its own rate again once the app is back',
        resumed >= 5,
        `${resumed} tick(s) in a second, expected about ${Math.floor(1000 / PERIOD_MS)}`,
      )
    }, 1000)
  }

  if (heard === 4) {
    disposer()
    disposed = true
    pass('disposed after 4 transitions', 'switching away again should print nothing at all')
    console.log('visibility test done')
  }
})

console.log('visibility-test armed; switch away from the app and back to run it')
