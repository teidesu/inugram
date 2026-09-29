import type { Instruction, RoutineProgram, TryRegion } from './ops.js'
import { isRegister, OPS } from './ops.js'

/**
 * Finds register reads reachable before a write. The host verifier cannot detect these:
 * skipped instructions leave `null`, which is a valid value. Reject such compiler output
 * instead of silently changing the program's behavior.
 */

function getOperandFields(node: Instruction): readonly string[] {
  const shape = OPS[node[0] as keyof typeof OPS]
  if (!shape) throw new Error(`routine: unknown instruction '${node[0]}'`)
  return shape.fields
}

function getJumpTarget(node: Instruction): number | null {
  const fields = getOperandFields(node)
  const at = fields.indexOf('target')
  return at < 0 ? null : node[at + 1] as number
}

function listSuccessors(code: Instruction[], at: number): number[] {
  const node = code[at]
  const op = node[0]
  if (op === 'return' || op === 'throw') return []
  const target = getJumpTarget(node)
  if (target === null) return [at + 1]
  // `jump` and `loop` go nowhere else; the conditional jumps and `advance` also fall through
  return op === 'jump' || op === 'loop' ? [target] : [at + 1, target]
}

function registersRead(node: Instruction): number[] {
  const found: number[] = []
  const fields = getOperandFields(node)
  for (let field = 0; field < fields.length; field++) {
    const value = node[field + 1]
    if (value === undefined) continue
    if (fields[field] === 'args') {
      if (!Array.isArray(value)) continue
      for (const item of value) {
        if (isRegister(item)) found.push(item)
      }
    } else if (fields[field].startsWith('operand') && isRegister(value)) {
      found.push(value)
    }
  }
  return found
}

/** Registers written on every path entering each instruction. */
function computeWritten(code: Instruction[], tries: TryRegion[]): Map<number, Uint8Array> {
  const count = code.length
  const incoming = new Map<number, Uint8Array>()
  if (count > 0) incoming.set(0, new Uint8Array(count))

  const meet = (at: number, state: Uint8Array): boolean => {
    const known = incoming.get(at)
    if (known === undefined) {
      incoming.set(at, state.slice())
      return true
    }
    let changed = false
    for (let register = 0; register < count; register++) {
      if (known[register] === 1 && state[register] === 0) {
        known[register] = 0
        changed = true
      }
    }
    return changed
  }

  let settled = false
  while (!settled) {
    settled = true
    for (let at = 0; at < count; at++) {
      const state = incoming.get(at)
      if (state === undefined) continue
      const out = state.slice()
      out[at] = 1
      for (const next of listSuccessors(code, at)) {
        if (next < count && meet(next, out)) settled = false
      }
      // any instruction in a protected range may throw, so its handler is only ever sure of what
      // the range was sure of before it started
      for (const [start, end, handler] of tries) {
        if (at >= start && at < end && meet(handler, state)) settled = false
      }
    }
  }
  return incoming
}

export interface UndominatedRead {
  at: number
  register: number
}

export function findUndominatedReads(code: Instruction[], tries: TryRegion[]): UndominatedRead[] {
  const incoming = computeWritten(code, tries)
  const found: UndominatedRead[] = []
  for (let at = 0; at < code.length; at++) {
    const state = incoming.get(at)
    if (state === undefined) continue
    for (const register of registersRead(code[at])) {
      if (state[register] !== 1) found.push({ at, register })
    }
  }
  return found
}

/**
 * Inputs can throw. Keep their first execution where the body put it, including inside a loop,
 * and reuse only a read that every incoming path has completed. Hook arguments remain live reads.
 */
export function reuseInputReads(program: Pick<RoutineProgram, 'code' | 'tries'>, hookMode: boolean): void {
  const { code, tries } = program
  const incoming = computeWritten(code, tries)
  const previous = new Map<string, number[]>()
  const aliases = new Map<number, number>()
  for (let at = 0; at < code.length; at++) {
    const [op, index] = code[at]
    if (op !== 'this' && (hookMode || op !== 'arg')) continue
    const key = JSON.stringify([op, index])
    const reads = previous.get(key) ?? []
    const written = incoming.get(at)
    const reusable = reads.find(read => written?.[read] === 1)
    if (reusable !== undefined) aliases.set(at, reusable)
    else reads.push(at)
    previous.set(key, reads)
  }
  if (aliases.size === 0) return

  const positions = new Int32Array(code.length + 1)
  let count = 0
  for (let at = 0; at < code.length; at++) {
    positions[at] = count
    if (!aliases.has(at)) count++
  }
  positions[code.length] = count
  const remapOperand = (value: unknown): unknown =>
    isRegister(value) ? positions[aliases.get(value) ?? value] : value

  program.code = code.flatMap((node, at): Instruction[] => {
    if (aliases.has(at)) return []
    const fields = getOperandFields(node)
    const mapped = node.slice(1).map((value, field) => {
      const kind = fields[field]
      if (kind === 'target') return positions[value as number]
      if (kind === 'args') return (value as unknown[]).map(remapOperand)
      if (kind.startsWith('operand')) return remapOperand(value)
      return value
    })
    return [[node[0], ...mapped]]
  })
  program.tries = tries.flatMap(([start, end, handler]): TryRegion[] =>
    positions[start] === positions[end] ? [] : [[positions[start], positions[end], positions[handler]]])
}
