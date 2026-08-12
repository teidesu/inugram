// ==InuPlugin==
// @name         timers test
// @author       teidesu
// @version      1.0
// @description  asserts timers fire, clear, are paced in the foreground and do not outlive an unload
// @grant        kv
// @plugin-api   1
// @platform     android
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

// the two timed halves below both report from a callback, so "done" is the latch rather than the
// last line of the file
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('timers test done')
}

// -- did the previous run's timers die with it? --

// the previous run wrote `pending` from its onUnload iff it still held a live timer at that point,
// and that timer would have written the same number under `leaked` had it run anyway
const generation = String(Number(inu.kv.get('generation') ?? '0') + 1)
inu.kv.set('generation', generation)
const pending = inu.kv.get('pending')
const leaked = inu.kv.get('leaked')
inu.kv.del('pending')
inu.kv.del('leaked')

if (pending === null) {
  console.log('no unload evidence yet: reload this plugin to check that its timers died with it')
} else {
  check(
    'a timer armed by the previous run did not survive its unload',
    leaked !== pending,
    `pending=${pending} leaked=${leaked}`,
  )
}

let survivor = false
// long enough that reloading beats it; if it does fire first, this run simply records no evidence
setTimeout(() => {
  survivor = true
  inu.kv.set('leaked', generation)
}, 30_000)

inu.onUnload(() => {
  if (!survivor) inu.kv.set('pending', generation)
  // the wheel refuses new work from the moment unloading starts, so this registers nothing and
  // there is no id to clear
  const late = setTimeout(() => inu.kv.set('leaked', generation), 0)
  check('setTimeout inside onUnload registers nothing', late === 0, `id=${late}`)
})

// -- firing and clearing --

const fired = []
setTimeout(() => fired.push('kept'), 40)
const doomed = setTimeout(() => fired.push('cleared'), 60)
clearTimeout(doomed)

let ticks = 0
const interval = setInterval(() => {
  ticks++
  if (ticks === 3) clearInterval(interval)
}, 30)

setTimeout(() => {
  check('a timeout fires', fired.includes('kept'), JSON.stringify(fired))
  check('clearTimeout stops one that had not fired yet', !fired.includes('cleared'), JSON.stringify(fired))
  check('an interval repeats and clearInterval stops it', ticks === 3, `${ticks} tick(s)`)
  halfDone()
}, 800)

// -- foreground pacing --

// the loop the host-side pacing exists for: every hop re-arms, so nothing inside the engine ever
// sees a callback that overruns. unpaced this walks the whole chain in about a millisecond
const HOPS = 25
let left = HOPS
const startedAt = performance.now()
;(function hop() {
  if (left-- > 0) {
    setTimeout(hop, 0)
    return
  }
  const elapsed = performance.now() - startedAt
  check(
    'a self-rearming zero-delay timer is paced rather than spinning the queue',
    elapsed >= HOPS * 3,
    `${HOPS} hops in ${Math.round(elapsed)}ms`,
  )
  halfDone()
})()
