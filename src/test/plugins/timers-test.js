// ==InuPlugin==
// @name         timers test
// @description  asserts timers fire, clear, are paced in the foreground and do not outlive an unload
// ==/InuPlugin==

// both timed halves report from a callback, so "done" is the latch
let halvesLeft = 2
function halfDone() {
  if (--halvesLeft === 0) console.log('timers test done')
}

// the previous run wrote `pending` from onUnload iff it still held a live timer, which would
// have written the same number under `leaked` had it run
const generation = String(Number(localStorage.getItem('generation') ?? '0') + 1)
localStorage.setItem('generation', generation)
const pending = localStorage.getItem('pending')
const leaked = localStorage.getItem('leaked')
localStorage.removeItem('pending')
localStorage.removeItem('leaked')

if (pending === null) {
  console.log('no unload evidence yet: reload this plugin to check that its timers died with it')
} else {
  check('a timer armed by the previous run did not survive its unload', leaked !== pending, `pending=${pending} leaked=${leaked}`)
}

let survivor = false
// long enough that reloading beats it; firing first just records no evidence
setTimeout(() => {
  survivor = true
  localStorage.setItem('leaked', generation)
}, 30_000)

inu.onUnload(() => {
  if (!survivor) localStorage.setItem('pending', generation)
  // the wheel refuses new work once unloading starts, so this registers nothing
  const late = setTimeout(() => localStorage.setItem('leaked', generation), 0)
  check('setTimeout inside onUnload registers nothing', late === 0, `id=${late}`)
})

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

// every hop re-arms, so no callback inside the engine overruns. unpaced this walks the chain in ~1ms
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
