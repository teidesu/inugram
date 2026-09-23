// ==InuPlugin==
// @name         visibility test
// @description  asserts onAppVisibilityChange reports transitions only and that timers throttle while hidden
// @grant        onAppVisibilityChange
// ==/InuPlugin==

const PERIOD_MS = 100
let ticks = 0
setInterval(() => ticks++, PERIOD_MS)

const MODES = ['foreground', 'resumed', 'paused', 'background']

let heard = 0
let coarse = 0
let last = null
let disposed = false
let hiddenAt = 0
let ticksAtHide = 0

const disposer = inu.onAppVisibilityChange((mode) => {
  if (disposed) return fail('a disposed callback fired again', mode)

  heard++
  check('the mode is one of the four documented values', MODES.includes(mode), mode)
  if (last !== null) check('transitions only: never the same state twice', mode !== last, `${last} -> ${mode}`)
  last = mode

  // only the coarse pair throttles the timer wheel
  if (mode === 'background') {
    coarse++
    hiddenAt = performance.now()
    ticksAtHide = ticks
  } else if (mode === 'foreground' && hiddenAt !== 0) {
    coarse++
    const hiddenFor = performance.now() - hiddenAt
    const ran = ticks - ticksAtHide
    // hidden, the wheel ticks once a second (once a minute past five) and fires everything due together
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

  if (coarse === 4) {
    disposer()
    disposed = true
    pass(`disposed after 4 coarse transitions`, `${heard} event(s) in all; switching away again should print nothing`)
    console.log('visibility test done')
  }
})

console.log('visibility-test armed; switch away from the app and back to run it')
