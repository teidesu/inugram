import { fileURLToPath } from 'node:url'
import { expect, it } from 'vitest'
import { compileRoutine, isRoutineFunction } from '../src/routines/compile.js'
import { findRoutineCalls, parseFile } from '../src/routines/find.js'
import { verifyProgram } from './verifier.js'

const cases = [
  {
    name: 'finally_break_uses_its_own_loop',
    source: `function (trace) {
      let n = 0
      while (true) {
        try { while (true) { return 99 } }
        finally { trace.append('f'); n++; break }
      }
      return n
    }`,
    expected: 1,
    trace: 'f',
  },
  {
    name: 'finally_continue_uses_its_own_loop',
    source: `function (trace) {
      for (let i = 0; i < 2; i++) {
        try { while (true) { return 99 } }
        finally { trace.append('f'); continue }
      }
      return 1
    }`,
    expected: 1,
    trace: 'ff',
  },
  {
    name: 'finally_break_ignores_the_inner_switch',
    source: `function (trace) {
      while (true) {
        try { switch (1) { case 1: return 99 } }
        finally { trace.append('f'); break }
      }
      return 1
    }`,
    expected: 1,
    trace: 'f',
  },
  {
    name: 'nested_finalizers_preserve_order',
    source: `function (trace) {
      try {
        while (true) {
          try { return 1 } finally { trace.append('inner') }
        }
      } finally { trace.append('outer') }
    }`,
    expected: 1,
    trace: 'innerouter',
  },
  {
    name: 'labeled_continue_runs_both_finalizers_once',
    source: `function (trace) {
      outer: for (let i = 0; i < 2; i++) {
        try {
          while (true) {
            try { continue outer } finally { trace.append('inner') }
          }
        } finally { trace.append('outer') }
      }
      return 1
    }`,
    expected: 1,
    trace: 'innerouterinnerouter',
  },
  {
    name: 'loop_argument_failure_reaches_its_handler',
    source: `function (trace, value) {
      try { while (true) { return value } }
      catch { trace.append('caught'); return 7 }
    }`,
    oversizedArgument: true,
    expected: 7,
    trace: 'caught',
  },
  {
    name: 'skipped_loop_does_not_validate_its_argument',
    source: `function (trace, value) {
      while (false) { trace.append(value) }
      return 7
    }`,
    oversizedArgument: true,
    expected: 7,
    trace: '',
  },
  {
    name: 'skipped_loop_does_not_validate_its_receiver',
    source: `function () {
      while (false) { this.toString() }
      return 7
    }`,
    oversizedReceiver: true,
    expected: 7,
    trace: '',
  },
  {
    name: 'branch_receivers_stay_after_the_guard',
    source: `function (trace, value) {
      if (value) return 7
      if (trace.length() === 0) return this.toString()
      return this.hashCode()
    }`,
    oversizedReceiver: true,
    expected: 7,
    trace: '',
  },
  {
    name: 'failed_argument_is_retried_in_the_handler',
    source: `function (trace, value) {
      try { return value }
      catch {
        try { return value }
        catch { trace.append('twice'); return 7 }
      }
    }`,
    oversizedArgument: true,
    expected: 7,
    trace: 'twice',
  },
  {
    name: 'finally_receiver_failure_keeps_its_handler',
    source: `function (trace) {
      try { trace.append('body'); return 7 }
      finally {
        try { this.toString() }
        catch { trace.append('caught') }
      }
    }`,
    oversizedReceiver: true,
    expected: 7,
    trace: 'bodycaught',
  },
  {
    name: 'successful_input_can_be_reused_across_a_try',
    source: `function (trace, value) {
      const first = value
      try { return value + first }
      catch { return 99 }
    }`,
    expected: 6,
    trace: '',
  },
]

it('pins compiler output consumed by PluginJvmRoutineTest on device', async () => {
  const fixtures = cases.map(({ source, ...test }) => {
    const wrapped = `inu.jvm.routine(${source})`
    const parsed = parseFile('fixture.ts', wrapped)
    expect(parsed.errors).toEqual([])
    const call = findRoutineCalls(parsed.program)[0]
    if (!isRoutineFunction(call.body)) throw new Error('fixture must be a routine body')
    const program = compileRoutine(call.body, wrapped, { mode: 'method' })
    verifyProgram(program, false)
    return { ...test, program }
  })
  await expect(`${JSON.stringify(fixtures, null, 2)}\n`).toMatchFileSnapshot(
    fileURLToPath(new URL('../../../src/test/assets/routines.json', import.meta.url)),
  )
})
