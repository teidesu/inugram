import * as v from 'valibot'

/** a register (the index of the instruction that wrote it) or a literal */
export type Operand = number | [unknown] | ['L', string]

export type Instruction = [string, ...unknown[]]

export const MAX_INSTRUCTIONS = 1024
export const MAX_SLOTS = 256
export const MAX_CAPTURES = 256
export const MAX_TRIES = 64
export const MAX_CALL_ARGS = 256

const IndexSchema = v.pipe(v.number(), v.integer(), v.minValue(0))

/** what a literal may hold: a bigint crosses as `['L', '123']`, and nothing else crosses at all */
const ScalarSchema = v.union([v.null(), v.boolean(), v.number(), v.string()])

const OperandSchema = v.union([
  IndexSchema,
  v.strictTuple([ScalarSchema]),
  v.strictTuple([v.literal('L'), v.string()]),
], 'is not a register or a literal')

const FIELD_SCHEMAS: Record<FieldKind, v.GenericSchema> = {
  'operand': OperandSchema,
  'operand?': OperandSchema,
  'slot': IndexSchema,
  'capture': IndexSchema,
  'target': IndexSchema,
  'args': v.pipe(v.array(OperandSchema), v.maxLength(MAX_CALL_ARGS)),
}

/**
 * An instruction is named by its first element and shaped by `OPS`, so this is the one place that
 * says what the wire holds. What it cannot say is anything that needs the rest of the program: a
 * register below this one, a jump that goes forwards, a handler that is a `catch`. Those are the
 * host's checks, mirrored for the tests in `test/verifier.ts`.
 */
const InstructionSchema = v.pipe(
  v.custom<Instruction>(
    it => Array.isArray(it) && typeof it[0] === 'string',
    'is not an array naming an instruction',
  ),
  v.rawCheck(({ dataset, addIssue }) => {
    if (!dataset.typed) return
    const [name, ...fields] = dataset.value
    const spec = OP_SPECS[name]
    if (spec === undefined) {
      addIssue({ message: `\`${name}\` is not an instruction` })
      return
    }
    const least = spec.fields.filter(kind => !kind.endsWith('?')).length
    if (fields.length < least || fields.length > spec.fields.length) {
      const wanted = least === spec.fields.length ? `${least}` : `${least} to ${spec.fields.length}`
      addIssue({ message: `\`${name}\` takes ${wanted} fields, not ${fields.length}` })
      return
    }
    for (const [at, field] of fields.entries()) {
      const kind = spec.fields[at]
      if (!v.is(FIELD_SCHEMAS[kind], field)) {
        addIssue({ message: `\`${name}\` field ${at + 1} is not a ${kind.replace('?', '')}` })
      }
    }
  }),
)

/** `[start, end, handler]`: instructions in `[start, end)` are covered by `handler` */
const TryRegionSchema = v.strictTuple([IndexSchema, IndexSchema, IndexSchema])
export type TryRegion = v.InferInput<typeof TryRegionSchema>

export const RoutineProgramSchema = v.object({
  v: v.literal(1),
  /** the function expression this was compiled from, for `inu verify` and for reading */
  source: v.string(),
  /** capture names, in the order the captures array passes them */
  captures: v.pipe(v.array(v.string()), v.maxLength(MAX_CAPTURES)),
  slots: v.pipe(IndexSchema, v.maxValue(MAX_SLOTS)),
  code: v.pipe(v.array(InstructionSchema), v.maxLength(MAX_INSTRUCTIONS)),
  tries: v.pipe(v.array(TryRegionSchema), v.maxLength(MAX_TRIES)),
})
export type RoutineProgram = v.InferInput<typeof RoutineProgramSchema>

/**
 * Every op, with the shape of its fields. `operand` reads a register or a literal, `slot` and
 * `capture` are raw indices, `target` is an instruction index, and `args` is a trailing operand
 * list. `hook` ops are refused in method mode.
 */
export const OPS = {
  this: { fields: [], hook: false },
  arg: { fields: ['operand'], hook: false },
  capture: { fields: ['capture'], hook: false },

  argCount: { fields: [], hook: true },
  setArg: { fields: ['operand', 'operand'], hook: true },
  method: { fields: [], hook: true },
  result: { fields: [], hook: true },
  throwable: { fields: [], hook: true },
  setResult: { fields: ['operand'], hook: true },
  setThrowable: { fields: ['operand'], hook: true },

  getSlot: { fields: ['slot'], hook: false },
  setSlot: { fields: ['slot', 'operand'], hook: false },

  jump: { fields: ['target'], hook: false },
  jumpIfFalsy: { fields: ['operand', 'target'], hook: false },
  jumpIfTruthy: { fields: ['operand', 'target'], hook: false },
  jumpIfNull: { fields: ['operand', 'target'], hook: false },
  jumpIfNotNull: { fields: ['operand', 'target'], hook: false },
  loop: { fields: ['target'], hook: false },
  return: { fields: ['operand?'], hook: false },
  throw: { fields: ['operand'], hook: false },
  catch: { fields: [], hook: false },

  get: { fields: ['operand', 'operand'], hook: false },
  set: { fields: ['operand', 'operand', 'operand'], hook: false },
  call: { fields: ['operand', 'operand', 'args'], hook: false },
  new: { fields: ['operand', 'args'], hook: false },
  array: { fields: ['args'], hook: false },

  iterate: { fields: ['operand'], hook: false },
  advance: { fields: ['operand', 'target'], hook: false },

  eq: { fields: ['operand', 'operand'], hook: false },
  ne: { fields: ['operand', 'operand'], hook: false },
  lt: { fields: ['operand', 'operand'], hook: false },
  le: { fields: ['operand', 'operand'], hook: false },
  gt: { fields: ['operand', 'operand'], hook: false },
  ge: { fields: ['operand', 'operand'], hook: false },
  instanceOf: { fields: ['operand', 'operand'], hook: false },

  add: { fields: ['operand', 'operand'], hook: false },
  sub: { fields: ['operand', 'operand'], hook: false },
  mul: { fields: ['operand', 'operand'], hook: false },
  div: { fields: ['operand', 'operand'], hook: false },
  rem: { fields: ['operand', 'operand'], hook: false },
  neg: { fields: ['operand'], hook: false },

  bitAnd: { fields: ['operand', 'operand'], hook: false },
  bitOr: { fields: ['operand', 'operand'], hook: false },
  bitXor: { fields: ['operand', 'operand'], hook: false },
  bitNot: { fields: ['operand'], hook: false },
  shl: { fields: ['operand', 'operand'], hook: false },
  shr: { fields: ['operand', 'operand'], hook: false },
  ushr: { fields: ['operand', 'operand'], hook: false },

  not: { fields: ['operand'], hook: false },
} as const

export type OpName = keyof typeof OPS
export type FieldKind = (typeof OPS)[OpName]['fields'][number]

export interface OpSpec {
  readonly fields: readonly FieldKind[]
  readonly hook: boolean
}

/** [OPS] keyed by a name that came off the wire, which may not be an instruction at all */
export const OP_SPECS: Record<string, OpSpec | undefined> = OPS

/** a js value the routine can hold as a constant, as opposed to one it has to capture */
export function literalOperand(value: unknown): Operand {
  if (typeof value === 'bigint') return ['L', value.toString()]
  return [value]
}

export function isRegister(operand: unknown): operand is number {
  return typeof operand === 'number'
}
