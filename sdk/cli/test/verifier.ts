import type { RoutineProgram } from '../src/routines/ops.js'
import {
  MAX_CALL_ARGS,
  MAX_CAPTURES,
  MAX_INSTRUCTIONS,
  MAX_SLOTS,
  MAX_TRIES,
  OP_SPECS,
} from '../src/routines/ops.js'

/**
 * A mirror of the load-time checks in `PluginJvmRoutine`'s `init`, hand-kept in step with it the
 * way every other wire in this repo is. It exists so a program the host would refuse fails here,
 * where there is no device to find out on. What an op's fields are it reads from `OP_SPECS`, so an
 * op added there is checked here without being named here.
 */

export function verifyProgram(program: RoutineProgram, hookMode: boolean): void {
  const code = program.code
  const count = code.length
  const captures = program.captures.length
  if (count > MAX_INSTRUCTIONS) throw new Error(`instruction count ${count}`)
  if (program.slots < 0 || program.slots > MAX_SLOTS) throw new Error(`slots ${program.slots}`)
  if (captures > MAX_CAPTURES) throw new Error(`captures ${captures}`)

  const cursors = new Set<number>()

  const readOperand = (value: unknown, at: number, allowCursor: boolean) => {
    if (Array.isArray(value)) {
      if (value.length === 2) {
        if (value[0] !== 'L') throw new Error(`${at}: unknown literal tag`)
        return
      }
      if (value.length !== 1) throw new Error(`${at}: malformed literal ${JSON.stringify(value)}`)
      const scalar = value[0]
      const kind = typeof scalar
      if (scalar !== null && kind !== 'number' && kind !== 'string' && kind !== 'boolean') {
        throw new Error(`${at}: literal is not a scalar`)
      }
      return
    }
    if (!Number.isInteger(value) || (value as number) < 0 || (value as number) >= at) {
      throw new Error(`${at}: operand ${JSON.stringify(value)} is not a lower register`)
    }
    if (!allowCursor && cursors.has(value as number)) throw new Error(`${at}: an iterator escaped its advance`)
  }

  const readTarget = (value: unknown, at: number, backwards: boolean) => {
    if (!Number.isInteger(value)) throw new Error(`${at}: malformed target`)
    const to = value as number
    if (backwards && (to < 0 || to > at)) throw new Error(`${at}: loop target ${to} goes forwards`)
    if (!backwards && (to <= at || to > count)) throw new Error(`${at}: jump target ${to} goes backwards`)
  }

  const readImmediate = (value: unknown, at: number, limit: number) => {
    if (!Number.isInteger(value) || (value as number) < 0 || (value as number) >= limit) {
      throw new Error(`${at}: index ${JSON.stringify(value)} out of range`)
    }
  }

  const readArguments = (value: unknown, at: number) => {
    if (!Array.isArray(value)) throw new Error(`${at}: malformed arguments`)
    if (value.length > MAX_CALL_ARGS) throw new Error(`${at}: too many arguments`)
    for (const item of value) readOperand(item, at, false)
  }

  for (let at = 0; at < count; at++) {
    const [op, ...fields] = code[at]
    const spec = OP_SPECS[op]
    if (spec === undefined) throw new Error(`${at}: unknown instruction ${op}`)
    if (!hookMode && spec.hook) throw new Error(`${at}: ${op} needs hook mode`)

    const optional = spec.fields.filter(it => it.endsWith('?')).length
    if (fields.length > spec.fields.length || fields.length < spec.fields.length - optional) {
      throw new Error(`${at}: malformed ${op}`)
    }

    for (const [index, field] of fields.entries()) {
      switch (spec.fields[index]) {
        case 'operand':
        case 'operand?':
          if (op === 'return' && hookMode) throw new Error(`${at}: a hook routine answers through setReturnValue`)
          readOperand(field, at, op === 'advance')
          break
        case 'capture':
          readImmediate(field, at, captures)
          break
        case 'slot':
          readImmediate(field, at, program.slots)
          break
        case 'target':
          readTarget(field, at, op === 'loop')
          break
        case 'args':
          readArguments(field, at)
          break
      }
    }

    if (op === 'advance' && !cursors.has(fields[0] as number)) {
      throw new Error(`${at}: advance without an iterator`)
    }
    if (op === 'iterate') cursors.add(at)
  }

  if (program.tries.length > MAX_TRIES) throw new Error(`try regions ${program.tries.length}`)
  for (const [start, end, handler] of program.tries) {
    if (!(start >= 0 && start < end && end <= count)) throw new Error(`malformed region [${start},${end}]`)
    if (!(handler >= end && handler < count)) throw new Error(`handler ${handler} runs inside its own region`)
    if (code[handler][0] !== 'catch') throw new Error(`handler ${handler} is not a catch`)
  }

  for (const left of program.tries) {
    for (const right of program.tries) {
      if (left === right) continue
      const disjoint = left[1] <= right[0] || right[1] <= left[0]
      const nested = (left[0] >= right[0] && left[1] <= right[1]) || (right[0] >= left[0] && right[1] <= left[1])
      if (!disjoint && !nested) throw new Error(`regions ${left} and ${right} overlap without nesting`)
    }
  }
}
