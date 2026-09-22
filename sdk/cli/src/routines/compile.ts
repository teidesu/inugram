import type {
  Argument,
  ArrayExpressionElement,
  ArrowFunctionExpression,
  AssignmentExpression,
  BinaryExpression,
  BindingIdentifier,
  BlockStatement,
  BreakStatement,
  CallExpression,
  CatchClause,
  ConditionalExpression,
  ContinueStatement,
  DoWhileStatement,
  Expression,
  ExpressionStatement,
  ForOfStatement,
  ForStatement,
  IfStatement,
  LabeledStatement,
  LogicalExpression,
  MemberExpression,
  Function as OxcFunction,
  ParenthesizedExpression,
  PrivateInExpression,
  ReturnStatement,
  Statement,
  SwitchStatement,
  TemplateLiteral,
  ThrowStatement,
  TryStatement,
  TSAsExpression,
  TSInstantiationExpression,
  TSNonNullExpression,
  TSSatisfiesExpression,
  TSTypeAssertion,
  UnaryExpression,
  UpdateExpression,
  VariableDeclaration,
  WhileStatement,
} from '@oxc-project/types'
import type { Instruction, Operand, OpName, RoutineProgram, TryRegion } from './ops.js'
import { findUndominatedReads, reuseInputReads } from './flow.js'
import {
  literalOperand,
  MAX_CALL_ARGS,
  MAX_CAPTURES,
  MAX_INSTRUCTIONS,
  MAX_SLOTS,
  MAX_TRIES,
} from './ops.js'

export interface CompileOptions {
  /** 'method' for inu.jvm.routine, 'hook' for inu.xposed.routine */
  mode: 'method' | 'hook'
  /** source text of the whole file, for error locations */
  file: string
}

export class RoutineCompileError extends Error {
  constructor(message: string, public readonly start: number, public readonly end: number) {
    super(message)
    this.name = 'RoutineCompileError'
  }
}

/** A function expression or arrow; arrows cannot access `this`. */
export type RoutineBody = OxcFunction | ArrowFunctionExpression

/** Source offsets shared by all nodes, used for error locations. */
interface Spanned {
  start: number
  end: number
}

/** TypeScript wrappers with no runtime effect. */
type Wrapper
  = | ParenthesizedExpression
    | TSAsExpression
    | TSNonNullExpression
    | TSSatisfiesExpression
    | TSTypeAssertion
    | TSInstantiationExpression

interface Patch {
  index: number
  field: number
}

type Binding
  = | { kind: 'pending' }
    | { kind: 'const', operand: Operand }
    | { kind: 'let', slot: number }
    | { kind: 'param', index: number }
    | { kind: 'ctx' }

type Scope = Map<string, Binding>

/**
 * Read `this` and arguments at their use sites: each read costs a host bridge check,
 * so an early return should skip unused inputs. Only captures are read in the prologue.
 */
interface PrologueEntry {
  key: string
  instruction: Instruction
  capture: string
}

interface RegionEntry {
  finalizer: BlockStatement | null
  /** The try block's scope, used by its finalizer even when inlined at another exit site. */
  scopeDepth: number
  targetDepth: number
  rangeStart: number | null
  ranges: [number, number][]
}

/**
 * A continue target: backward to an existing loop header or forward to a pending one.
 * Null for switches and labelled blocks, which have no continue target.
 */
type ContinuePoint = { kind: 'header', at: number } | { kind: 'patches', patches: Patch[] } | null

interface ControlTarget {
  labels: string[]
  kind: 'loop' | 'switch' | 'block'
  breaks: Patch[]
  continueTo: ContinuePoint
  regionDepth: number
}

interface ChainState {
  exits: Patch[]
}

/** Restores the tuple type lost when spreading it into an array. */
function cloneInstruction(node: Instruction): Instruction {
  const [op, ...fields] = node
  return [op, ...fields]
}

function fail(node: Spanned | null | undefined, message: string): never {
  throw new RoutineCompileError(message, node?.start ?? 0, node?.end ?? 0)
}

const TRANSPARENT = new Set([
  'TSAsExpression',
  'TSNonNullExpression',
  'TSSatisfiesExpression',
  'TSTypeAssertion',
  'TSInstantiationExpression',
  'ParenthesizedExpression',
])

function isWrapper(node: { type: string }): node is Wrapper {
  return TRANSPARENT.has(node.type)
}

/**
 * The loop has removed every wrapper, so the result is `Exclude<T, Wrapper>`.
 * TypeScript needs this cast because a wrapper contains an `Expression`, not a `T`.
 */
function unwrap<T extends { type: string }>(node: T): Exclude<T, Wrapper> {
  let it: { type: string } = node
  while (isWrapper(it)) it = it.expression
  return it as Exclude<T, Wrapper>
}

const BINARY_OPS: Record<string, OpName> = {
  '===': 'eq',
  '!==': 'ne',
  '<': 'lt',
  '<=': 'le',
  '>': 'gt',
  '>=': 'ge',
  '+': 'add',
  '-': 'sub',
  '*': 'mul',
  '/': 'div',
  '%': 'rem',
  '&': 'bitAnd',
  '|': 'bitOr',
  '^': 'bitXor',
  '<<': 'shl',
  '>>': 'shr',
  '>>>': 'ushr',
}

const COMPOUND_OPS: Record<string, OpName> = {
  '+': 'add',
  '-': 'sub',
  '*': 'mul',
  '/': 'div',
  '%': 'rem',
  '&': 'bitAnd',
  '|': 'bitOr',
  '^': 'bitXor',
  '<<': 'shl',
  '>>': 'shr',
  '>>>': 'ushr',
}

const REJECTED_BINARY: Record<string, string> = {
  '==': '`==` is not supported, use `===`',
  '!=': '`!=` is not supported, use `!==`',
  '**': '`**` is not supported in a routine',
  'in': '`in` is not supported in a routine',
}

const LOGICAL_JUMPS: Record<string, OpName> = {
  '&&': 'jumpIfFalsy',
  '||': 'jumpIfTruthy',
  '??': 'jumpIfNotNull',
}

const CTX_PROPERTIES: Record<string, OpName> = {
  method: 'method',
  returnValue: 'result',
  throwable: 'throwable',
}

const CTX_METHODS: Record<string, OpName> = {
  setReturnValue: 'setResult',
  setThrowable: 'setThrowable',
}

function hasOptionalChain(node: Expression): boolean {
  let it: Expression = unwrap(node)
  while (it) {
    if (it.type === 'MemberExpression') {
      if (it.optional) return true
      it = unwrap(it.object)
    } else if (it.type === 'CallExpression') {
      if (it.optional) return true
      it = unwrap(it.callee)
    } else {
      return false
    }
  }
  return false
}

/** Accepts function expressions and arrows. Rejects arrows only if they read `this`. */
export function isRoutineFunction(fn: { type: string } | null | undefined): fn is RoutineBody {
  return fn?.type === 'FunctionExpression' || fn?.type === 'ArrowFunctionExpression'
}

/**
 * A concise arrow body returns its expression in method mode. Hook mode uses the expression
 * as a statement because hooks set results through `ctx` and cannot return a value.
 */
function statementsOf(fn: RoutineBody, mode: CompileOptions['mode']): Statement[] {
  const body = fn.body
  if (body === null) return []
  if (body.type === 'BlockStatement') return body.body
  const at = { start: body.start, end: body.end }
  if (mode === 'hook') {
    const wrapped: ExpressionStatement = { type: 'ExpressionStatement', expression: body, ...at }
    return [wrapped]
  }
  const answered: ReturnStatement = { type: 'ReturnStatement', argument: body, ...at }
  return [answered]
}

function validateRoutineFunction(fn: RoutineBody, options: CompileOptions): BindingIdentifier[] {
  if (!isRoutineFunction(fn)) {
    fail(fn, 'a routine must be a function expression')
  }
  if (fn.async) fail(fn, 'an async function cannot be a routine')
  if (fn.type !== 'ArrowFunctionExpression' && fn.generator) {
    fail(fn, 'a generator function cannot be a routine')
  }
  if (!fn.body || (fn.body.type !== 'BlockStatement' && fn.type !== 'ArrowFunctionExpression')) {
    fail(fn, 'a routine must have a block body')
  }

  const params: BindingIdentifier[] = []
  for (const raw of fn.params) {
    const param = unwrap(raw)
    if (param.type !== 'Identifier') {
      fail(param, `${param.type} parameters are not supported in a routine`)
    }
    if (param.name === 'this') continue
    params.push(param)
  }

  if (options.mode === 'hook' && params.length > 1) {
    fail(params[1], 'a hook routine takes at most one parameter')
  }
  return params
}

class RoutineCompiler {
  private code: Instruction[] = []
  private tries: TryRegion[] = []
  private slots = 0
  private freeTemps: number[] = []
  private scopes: Scope[] = []
  private regions: RegionEntry[] = []
  private targets: ControlTarget[] = []
  private planned = new Map<string, number>()
  private captureCount = 0

  constructor(
    private fn: RoutineBody,
    private source: string,
    private options: CompileOptions,
    readonly plan: PrologueEntry[],
    private planning: boolean,
  ) {
    if (!planning) {
      let register = 0
      for (const entry of plan) {
        this.planned.set(entry.key, register++)
      }
    }
  }

  compile(): void {
    const params = validateRoutineFunction(this.fn, this.options)
    if (!this.planning) {
      for (const entry of this.plan) {
        this.code.push(cloneInstruction(entry.instruction))
      }
    }

    const outer: Scope = new Map()
    for (let i = 0; i < params.length; i++) {
      outer.set(params[i].name, this.options.mode === 'hook' ? { kind: 'ctx' } : { kind: 'param', index: i })
    }
    this.scopes.push(outer)

    this.pushScope()
    this.compileStatements(statementsOf(this.fn, this.options.mode))
    this.popScope()
    this.scopes.pop()
  }

  buildProgram(): RoutineProgram {
    if (this.code.length > MAX_INSTRUCTIONS) {
      fail(this.fn, `a routine may hold at most ${MAX_INSTRUCTIONS} instructions, this one holds ${this.code.length}`)
    }
    if (this.slots > MAX_SLOTS) {
      fail(this.fn, `a routine may hold at most ${MAX_SLOTS} slots, this one needs ${this.slots}`)
    }
    if (this.tries.length > MAX_TRIES) {
      fail(this.fn, `a routine may hold at most ${MAX_TRIES} try regions, this one holds ${this.tries.length}`)
    }

    const captures: string[] = []
    for (const entry of this.plan) {
      if (entry.capture !== null) captures.push(entry.capture)
    }
    if (captures.length > MAX_CAPTURES) {
      fail(this.fn, `a routine may take at most ${MAX_CAPTURES} captures, this one takes ${captures.length}`)
    }

    const lowered = { code: this.code, tries: this.tries }
    reuseInputReads(lowered, this.options.mode === 'hook')
    this.code = lowered.code
    this.tries = lowered.tries
    const tries = [...this.tries].sort((a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2])
    return {
      v: 1,
      source: this.source.slice(this.fn.start, this.fn.end),
      captures,
      slots: this.slots,
      code: this.code,
      tries,
    }
  }

  private here(): number {
    return this.code.length
  }

  private emit(op: OpName, ...fields: unknown[]): number {
    this.code.push([op, ...fields])
    return this.code.length - 1
  }

  private emitJump(op: OpName, ...fields: unknown[]): Patch {
    const index = this.emit(op, ...fields, -1)
    return { index, field: this.code[index].length - 1 }
  }

  private patch(patch: Patch, target: number): void {
    this.code[patch.index][patch.field] = target
  }

  private patchAll(patches: Patch[], target: number): void {
    for (const patch of patches) this.patch(patch, target)
  }

  private allocSlot(node: Spanned): number {
    if (this.slots >= MAX_SLOTS) {
      fail(node, `a routine may hold at most ${MAX_SLOTS} slots`)
    }
    return this.slots++
  }

  /**
   * A temporary slot for `&&`, `?:`, or `?.`. Every path writes it before the join reads it.
   * Release it after that read so later expressions reuse it and limit per-run allocations.
   */
  private allocTemp(node: Spanned): number {
    return this.freeTemps.pop() ?? this.allocSlot(node)
  }

  private releaseTemp(slot: number): void {
    this.freeTemps.push(slot)
  }

  private requestPrologue(key: string, build: (captureIndex: number) => Instruction, capture: string): number {
    const known = this.planned.get(key)
    if (known !== undefined) return known

    if (!this.planning) {
      throw new Error(`routine prologue is missing \`${key}\``)
    }
    const register = this.plan.length
    this.plan.push({ key, instruction: build(this.captureCount), capture })
    this.captureCount++
    this.planned.set(key, register)
    return register
  }

  private argRegister(index: number): number {
    return this.emit('arg', literalOperand(index))
  }

  private thisRegister(): number {
    return this.emit('this')
  }

  private captureRegister(name: string, node: Spanned): number {
    if (name === 'inu') {
      fail(node, '`inu` is not available inside a routine')
    }
    if (name === 'arguments') {
      fail(node, '`arguments` is not available inside a routine')
    }
    return this.requestPrologue(`capture:${name}`, index => ['capture', index], name)
  }

  private pushScope(): void {
    this.scopes.push(new Map())
  }

  private popScope(): void {
    this.scopes.pop()
  }

  private lookup(name: string): Binding | null {
    for (let i = this.scopes.length - 1; i >= 0; i--) {
      const binding = this.scopes[i].get(name)
      if (binding) return binding
    }
    return null
  }

  private declare(name: string, binding: Binding): void {
    this.scopes[this.scopes.length - 1].set(name, binding)
  }

  private declarePending(statements: readonly Statement[]): void {
    for (const statement of statements) {
      if (statement?.type !== 'VariableDeclaration') continue
      if (statement.kind !== 'let' && statement.kind !== 'const') continue
      for (const declarator of statement.declarations) {
        const id = unwrap(declarator.id)
        if (id.type === 'Identifier') this.declare(id.name, { kind: 'pending' })
      }
    }
  }

  private closeRange(entry: RegionEntry): void {
    if (entry.rangeStart === null) return
    const end = this.here()
    if (end > entry.rangeStart) entry.ranges.push([entry.rangeStart, end])
    entry.rangeStart = null
  }

  private openRange(entry: RegionEntry): void {
    entry.rangeStart = this.here()
  }

  private pushRegion(finalizer: BlockStatement | null): RegionEntry {
    const entry: RegionEntry = {
      finalizer,
      scopeDepth: this.scopes.length,
      targetDepth: this.targets.length,
      rangeStart: this.here(),
      ranges: [],
    }
    this.regions.push(entry)
    return entry
  }

  private commitRegion(entry: RegionEntry, handler: number): void {
    for (const [start, end] of entry.ranges) this.tries.push([start, end, handler])
  }

  private emitExit(depth: number, terminator: () => void): void {
    const stack = this.regions
    for (let i = stack.length - 1; i >= depth; i--) {
      const entry = stack[i]
      this.closeRange(entry)
      if (entry.finalizer) {
        const removed = stack.splice(i)
        const scopes = this.scopes
        const targets = this.targets
        this.scopes = scopes.slice(0, entry.scopeDepth)
        this.targets = targets.slice(0, entry.targetDepth)
        this.compileStatement(entry.finalizer)
        this.scopes = scopes
        this.targets = targets
        stack.push(...removed)
      }
    }
    terminator()
    for (let i = depth; i < stack.length; i++) this.openRange(stack[i])
  }

  private findTarget(label: string | null, kind: 'break' | 'continue', node: Spanned): ControlTarget {
    for (let i = this.targets.length - 1; i >= 0; i--) {
      const target = this.targets[i]
      if (label !== null && !target.labels.includes(label)) continue
      if (kind === 'continue' && target.kind !== 'loop') {
        if (label !== null) fail(node, `\`${label}\` does not label a loop`)
        continue
      }
      if (kind === 'break' && label === null && target.kind === 'block') continue
      return target
    }
    if (label !== null) fail(node, `unknown label \`${label}\``)
    fail(node, `\`${kind}\` outside of a loop`)
  }

  private compileStatements(statements: readonly Statement[]): void {
    this.declarePending(statements)
    for (const statement of statements) this.compileStatement(statement)
  }

  private compileStatement(node: Statement): void {
    switch (node.type) {
      case 'BlockStatement':
        this.pushScope()
        this.compileStatements(node.body)
        this.popScope()
        return
      case 'EmptyStatement':
      case 'TSTypeAliasDeclaration':
      case 'TSInterfaceDeclaration':
        return
      case 'ExpressionStatement':
        this.compileExpression(node.expression)
        return
      case 'VariableDeclaration':
        this.compileVariableDeclaration(node)
        return
      case 'IfStatement':
        this.compileIfStatement(node)
        return
      case 'WhileStatement':
        this.compileWhileStatement(node, [])
        return
      case 'DoWhileStatement':
        this.compileDoWhileStatement(node, [])
        return
      case 'ForStatement':
        this.compileForStatement(node, [])
        return
      case 'ForOfStatement':
        this.compileForOfStatement(node, [])
        return
      case 'SwitchStatement':
        this.compileSwitchStatement(node)
        return
      case 'LabeledStatement':
        this.compileLabeledStatement(node)
        return
      case 'BreakStatement':
      case 'ContinueStatement':
        this.compileBreakOrContinue(node)
        return
      case 'ReturnStatement':
        this.compileReturnStatement(node)
        return
      case 'ThrowStatement':
        this.compileThrowStatement(node)
        return
      case 'TryStatement':
        this.compileTryStatement(node)
        return
      case 'ForInStatement':
        return fail(node, '`for in` is not supported in a routine')
      case 'FunctionDeclaration':
        return fail(node, 'function declarations are not supported in a routine')
      case 'ClassDeclaration':
        return fail(node, 'class declarations are not supported in a routine')
      case 'WithStatement':
        return fail(node, '`with` is not supported in a routine')
      case 'DebuggerStatement':
        return fail(node, '`debugger` is not supported in a routine')
      default:
        return fail(node, `${node.type} is not supported in a routine`)
    }
  }

  private compileVariableDeclaration(node: VariableDeclaration): void {
    if (node.kind !== 'let' && node.kind !== 'const') {
      fail(node, `\`${node.kind}\` is not supported in a routine, use \`let\` or \`const\``)
    }
    for (const declarator of node.declarations) {
      const id = unwrap(declarator.id)
      if (id.type !== 'Identifier') {
        fail(id, 'destructuring is not supported in a routine')
      }
      if (node.kind === 'const') {
        if (!declarator.init) fail(declarator, `\`${id.name}\` is a const without an initializer`)
        const operand = this.compileExpression(declarator.init)
        this.declare(id.name, { kind: 'const', operand })
      } else {
        const value = declarator.init ? this.compileExpression(declarator.init) : literalOperand(null)
        const slot = this.allocSlot(declarator)
        this.emit('setSlot', slot, value)
        this.declare(id.name, { kind: 'let', slot })
      }
    }
  }

  private compileIfStatement(node: IfStatement): void {
    const test = this.compileExpression(node.test)
    const overThen = this.emitJump('jumpIfFalsy', test)
    this.compileStatement(node.consequent)
    if (!node.alternate) {
      this.patch(overThen, this.here())
      return
    }
    const overElse = this.emitJump('jump')
    this.patch(overThen, this.here())
    this.compileStatement(node.alternate)
    this.patch(overElse, this.here())
  }

  private pushLoopTarget(labels: string[], continueHeader: number | null): ControlTarget {
    const continueTo: ContinuePoint = continueHeader === null
      ? { kind: 'patches', patches: [] }
      : { kind: 'header', at: continueHeader }
    const target: ControlTarget = { labels, kind: 'loop', breaks: [], continueTo, regionDepth: this.regions.length }
    this.targets.push(target)
    return target
  }

  private finishTarget(target: ControlTarget): void {
    this.targets.pop()
    this.patchAll(target.breaks, this.here())
  }

  private compileWhileStatement(node: WhileStatement, labels: string[]): void {
    const header = this.here()
    const test = this.compileExpression(node.test)
    const exit = this.emitJump('jumpIfFalsy', test)
    const target = this.pushLoopTarget(labels, header)
    this.compileStatement(node.body)
    this.emit('loop', header)
    this.patch(exit, this.here())
    this.finishTarget(target)
  }

  private compileDoWhileStatement(node: DoWhileStatement, labels: string[]): void {
    const header = this.here()
    const target = this.pushLoopTarget(labels, null)
    this.compileStatement(node.body)
    if (target.continueTo?.kind === 'patches') this.patchAll(target.continueTo.patches, this.here())
    const test = this.compileExpression(node.test)
    const exit = this.emitJump('jumpIfFalsy', test)
    this.emit('loop', header)
    this.patch(exit, this.here())
    this.finishTarget(target)
  }

  private compileForStatement(node: ForStatement, labels: string[]): void {
    this.pushScope()
    if (node.init) {
      if (node.init.type === 'VariableDeclaration') {
        this.declarePending([node.init])
        this.compileVariableDeclaration(node.init)
      } else {
        this.compileExpression(node.init)
      }
    }

    const header = this.here()
    const exit = node.test ? this.emitJump('jumpIfFalsy', this.compileExpression(node.test)) : null
    const target = this.pushLoopTarget(labels, null)
    this.compileStatement(node.body)
    if (target.continueTo?.kind === 'patches') this.patchAll(target.continueTo.patches, this.here())
    if (node.update) this.compileExpression(node.update)
    this.emit('loop', header)
    if (exit) this.patch(exit, this.here())
    this.finishTarget(target)
    this.popScope()
  }

  private compileForOfStatement(node: ForOfStatement, labels: string[]): void {
    if (node.await) fail(node, '`for await` is not supported in a routine')
    const left = node.left
    if (left.type !== 'VariableDeclaration') {
      fail(left, 'a `for of` loop must declare its variable with `const` or `let`')
    }
    if (left.kind !== 'let' && left.kind !== 'const') {
      fail(left, `\`${left.kind}\` is not supported in a routine, use \`let\` or \`const\``)
    }
    if (left.declarations.length !== 1) {
      fail(left, 'a `for of` loop declares exactly one variable')
    }
    const id = unwrap(left.declarations[0].id)
    if (id.type !== 'Identifier') {
      fail(id, 'destructuring is not supported in a routine')
    }

    const iterable = this.compileExpression(node.right)
    const cursor = this.emit('iterate', iterable)
    const header = this.here()
    const exit = this.emitJump('advance', cursor)

    this.pushScope()
    if (left.kind === 'const') {
      this.declare(id.name, { kind: 'const', operand: header })
    } else {
      const slot = this.allocSlot(left)
      this.emit('setSlot', slot, header)
      this.declare(id.name, { kind: 'let', slot })
    }

    const target = this.pushLoopTarget(labels, header)
    this.compileStatement(node.body)
    this.emit('loop', header)
    this.patch(exit, this.here())
    this.finishTarget(target)
    this.popScope()
  }

  private compileSwitchStatement(node: SwitchStatement): void {
    const discriminant = this.compileExpression(node.discriminant)
    const target: ControlTarget = {
      labels: [],
      kind: 'switch',
      breaks: [],
      continueTo: null,
      regionDepth: this.regions.length,
    }
    this.targets.push(target)
    this.pushScope()

    for (const branch of node.cases) {
      for (const statement of branch.consequent) {
        if (statement?.type !== 'VariableDeclaration') continue
        fail(
          statement,
          `a \`${statement.kind}\` directly in a case is visible to the cases after it, which may skip it; wrap the case body in a block`,
        )
      }
    }

    const tests: (Patch | null)[] = []
    for (const branch of node.cases) {
      if (!branch.test) {
        tests.push(null)
        continue
      }
      const value = this.compileExpression(branch.test)
      const matched = this.emit('eq', discriminant, value)
      tests.push(this.emitJump('jumpIfTruthy', matched))
    }
    const fallback = this.emitJump('jump')

    let defaultAt = -1
    for (let i = 0; i < node.cases.length; i++) {
      const at = this.here()
      const test = tests[i]
      if (test) this.patch(test, at)
      else defaultAt = at
      for (const statement of node.cases[i].consequent) this.compileStatement(statement)
    }

    this.popScope()
    this.patch(fallback, defaultAt >= 0 ? defaultAt : this.here())
    this.finishTarget(target)
  }

  private compileLabeledStatement(node: LabeledStatement): void {
    const labels: string[] = []
    let body: Statement = node
    while (body.type === 'LabeledStatement') {
      const label = body.label.name
      for (const target of this.targets) {
        if (target.labels.includes(label)) fail(body, `label \`${label}\` is already in use`)
      }
      if (labels.includes(label)) fail(body, `label \`${label}\` is already in use`)
      labels.push(label)
      body = body.body
    }
    switch (body.type) {
      case 'WhileStatement':
        this.compileWhileStatement(body, labels)
        return
      case 'DoWhileStatement':
        this.compileDoWhileStatement(body, labels)
        return
      case 'ForStatement':
        this.compileForStatement(body, labels)
        return
      case 'ForOfStatement':
        this.compileForOfStatement(body, labels)
        return
      default: {
        const target: ControlTarget = {
          labels,
          kind: 'block',
          breaks: [],
          continueTo: null,
          regionDepth: this.regions.length,
        }
        this.targets.push(target)
        this.compileStatement(body)
        this.finishTarget(target)
      }
    }
  }

  private compileBreakOrContinue(node: BreakStatement | ContinueStatement): void {
    const kind = node.type === 'BreakStatement' ? 'break' : 'continue'
    const label = node.label ? node.label.name : null
    const target = this.findTarget(label, kind, node)
    this.emitExit(target.regionDepth, () => {
      const continueTo = target.continueTo
      if (kind === 'break' || continueTo === null) {
        target.breaks.push(this.emitJump('jump'))
      } else if (continueTo.kind === 'header') {
        this.emit('loop', continueTo.at)
      } else {
        continueTo.patches.push(this.emitJump('jump'))
      }
    })
  }

  private compileReturnStatement(node: ReturnStatement): void {
    if (!node.argument) {
      this.emitExit(0, () => this.emit('return'))
      return
    }
    if (this.options.mode === 'hook') {
      fail(node, 'a hook routine cannot return a value, use `ctx.setReturnValue(...)`')
    }
    const value = this.compileExpression(node.argument)
    this.emitExit(0, () => this.emit('return', value))
  }

  private compileThrowStatement(node: ThrowStatement): void {
    const value = this.compileExpression(node.argument)
    this.emit('throw', value)
  }

  private compileTryStatement(node: TryStatement): void {
    if (node.finalizer !== null) {
      const guarded: Statement = node.handler !== null ? { ...node, finalizer: null } : node.block
      this.compileTryFinally(guarded, node.finalizer)
      return
    }
    if (node.handler === null) fail(node, 'a `try` needs a `catch` or a `finally`')
    this.compileTryCatch(node, node.handler)
  }

  private compileTryCatch(node: TryStatement, handler: CatchClause): void {
    const entry = this.pushRegion(null)
    this.compileStatement(node.block)
    this.closeRange(entry)
    this.regions.pop()
    if (entry.ranges.length === 0) return

    const skip = this.emitJump('jump')
    const catchAt = this.emit('catch')
    this.commitRegion(entry, catchAt)

    this.pushScope()
    if (handler.param) {
      const param = handler.param
      if (param.type !== 'Identifier') {
        fail(param, 'destructuring is not supported in a routine')
      }
      this.declare(param.name, { kind: 'const', operand: catchAt })
    }
    this.compileStatement(handler.body)
    this.popScope()
    this.patch(skip, this.here())
  }

  private compileTryFinally(guarded: Statement, finalizer: BlockStatement): void {
    const entry = this.pushRegion(finalizer)
    this.compileStatement(guarded)
    this.closeRange(entry)
    this.regions.pop()

    if (entry.ranges.length === 0) {
      this.compileStatement(finalizer)
      return
    }

    this.compileStatement(finalizer)
    const skip = this.emitJump('jump')
    const catchAt = this.emit('catch')
    this.commitRegion(entry, catchAt)
    this.compileStatement(finalizer)
    this.emit('throw', catchAt)
    this.patch(skip, this.here())
  }

  private compileExpression(node: Expression): Operand {
    const it = unwrap(node)
    const kind: string = it.type
    switch (it.type) {
      case 'Literal':
        if ('regex' in it) fail(it, 'regular expressions are not supported in a routine')
        return literalOperand(it.value)
      case 'Identifier':
        return this.resolveValue(it.name, it)
      case 'ThisExpression':
        if (this.options.mode === 'hook') {
          fail(it, '`this` is not available in a hook routine, use `ctx.thisObject`')
        }
        if (this.fn.type === 'ArrowFunctionExpression') {
          fail(it, '`this` is the receiver, which an arrow does not have: write the body as a function expression')
        }
        return this.thisRegister()
      case 'TemplateLiteral':
        return this.compileTemplateLiteral(it)
      case 'ArrayExpression':
        return this.emit('array', this.compileArguments(it.elements, it))
      case 'UnaryExpression':
        return this.compileUnaryExpression(it)
      case 'UpdateExpression':
        return this.compileUpdateExpression(it)
      case 'BinaryExpression':
        return this.compileBinaryExpression(it)
      case 'LogicalExpression':
        return this.compileLogicalExpression(it)
      case 'ConditionalExpression':
        return this.compileConditionalExpression(it)
      case 'AssignmentExpression':
        return this.compileAssignmentExpression(it)
      case 'SequenceExpression': {
        let value: Operand = literalOperand(null)
        for (const part of it.expressions) value = this.compileExpression(part)
        return value
      }
      case 'NewExpression': {
        if (it.callee.type === 'Super') fail(it, '`super` is not available in a routine')
        const callee = this.compileExpression(it.callee)
        return this.emit('new', callee, this.compileArguments(it.arguments, it))
      }
      case 'ChainExpression':
        return this.compileChainRoot(unwrap(it.expression))
      case 'MemberExpression':
      case 'CallExpression':
        if (hasOptionalChain(it)) return this.compileChainRoot(it)
        return this.compileMemberOrCall(it, null)
      case 'ObjectExpression':
        return fail(it, 'object literals are not supported in a routine')
      case 'FunctionExpression':
      case 'ArrowFunctionExpression':
        return fail(it, 'a routine cannot hold another function')
      case 'TaggedTemplateExpression':
        return fail(it, 'tagged templates are not supported in a routine')
      case 'AwaitExpression':
        return fail(it, '`await` is not supported in a routine')
      case 'YieldExpression':
        return fail(it, '`yield` is not supported in a routine')
      case 'Super':
        return fail(it, '`super` is not available in a routine')
      default:
        return fail(it, `${kind} is not supported in a routine`)
    }
  }

  private resolveValue(name: string, node: Spanned): Operand {
    const binding = this.lookup(name)
    if (binding) {
      switch (binding.kind) {
        case 'pending':
          fail(node, `\`${name}\` is used before its declaration`)
          break
        case 'const':
          return binding.operand
        case 'param':
          return this.argRegister(binding.index)
        case 'let':
          return this.emit('getSlot', binding.slot)
        case 'ctx':
          fail(node, `\`${name}\` is the hook context and cannot be used on its own`)
      }
    }
    if (name === 'undefined') return literalOperand(null)
    return this.captureRegister(name, node)
  }

  private isCtx(node: Expression): boolean {
    const it = unwrap(node)
    return it.type === 'Identifier' && this.lookup(it.name)?.kind === 'ctx'
  }

  private isCtxArgs(node: Expression): boolean {
    const it = unwrap(node)
    return it.type === 'MemberExpression'
      && !it.computed
      && !it.optional
      && it.property.type === 'Identifier'
      && it.property.name === 'args'
      && this.isCtx(it.object)
  }

  private compileTemplateLiteral(node: TemplateLiteral): Operand {
    if (node.expressions.length === 0) {
      return literalOperand(node.quasis[0].value.cooked ?? node.quasis[0].value.raw)
    }
    let value: Operand = literalOperand(node.quasis[0].value.cooked ?? node.quasis[0].value.raw)
    for (let i = 0; i < node.expressions.length; i++) {
      value = this.emit('add', value, this.compileExpression(node.expressions[i]))
      const tail = node.quasis[i + 1]
      const text = tail?.value.cooked ?? tail?.value.raw ?? ''
      if (text !== '') value = this.emit('add', value, literalOperand(text))
    }
    return value
  }

  private compileArguments(nodes: readonly (Argument | ArrayExpressionElement)[], owner: Spanned): Operand[] {
    if (nodes.length > MAX_CALL_ARGS) {
      fail(owner, `a routine may pass at most ${MAX_CALL_ARGS} arguments`)
    }
    const operands: Operand[] = []
    for (const raw of nodes) {
      if (!raw) fail(owner, 'array holes are not supported in a routine')
      const node = unwrap(raw)
      if (node.type === 'SpreadElement') fail(node, 'spread is not supported in a routine')
      operands.push(this.compileExpression(node))
    }
    return operands
  }

  private compileUnaryExpression(node: UnaryExpression): Operand {
    const argument = unwrap(node.argument)
    switch (node.operator) {
      case '-':
        if (argument.type === 'Literal' && (typeof argument.value === 'number' || typeof argument.value === 'bigint')) {
          return literalOperand(-argument.value)
        }
        return this.emit('neg', this.compileExpression(argument))
      case '!':
        return this.emit('not', this.compileExpression(argument))
      case '~':
        return this.emit('bitNot', this.compileExpression(argument))
      default:
        return fail(node, `\`${node.operator}\` is not supported in a routine`)
    }
  }

  private compileBinaryExpression(node: BinaryExpression | PrivateInExpression): Operand {
    if (node.left.type === 'PrivateIdentifier') fail(node, REJECTED_BINARY.in)
    if (node.operator === 'instanceof') {
      const value = this.compileExpression(node.left)
      return this.emit('instanceOf', value, this.compileExpression(node.right))
    }
    const rejected = REJECTED_BINARY[node.operator]
    if (rejected) fail(node, rejected)
    const op = BINARY_OPS[node.operator]
    if (!op) fail(node, `\`${node.operator}\` is not supported in a routine`)
    const left = this.compileExpression(node.left)
    return this.emit(op, left, this.compileExpression(node.right))
  }

  private compileLogicalExpression(node: LogicalExpression): Operand {
    const jump = LOGICAL_JUMPS[node.operator]
    if (!jump) fail(node, `\`${node.operator}\` is not supported in a routine`)
    const left = this.compileExpression(node.left)
    const slot = this.allocTemp(node)
    this.emit('setSlot', slot, left)
    const skip = this.emitJump(jump, left)
    const right = this.compileExpression(node.right)
    this.emit('setSlot', slot, right)
    this.patch(skip, this.here())
    this.releaseTemp(slot)
    return this.emit('getSlot', slot)
  }

  private compileConditionalExpression(node: ConditionalExpression): Operand {
    const test = this.compileExpression(node.test)
    const slot = this.allocTemp(node)
    const toAlternate = this.emitJump('jumpIfFalsy', test)
    this.emit('setSlot', slot, this.compileExpression(node.consequent))
    const toEnd = this.emitJump('jump')
    this.patch(toAlternate, this.here())
    this.emit('setSlot', slot, this.compileExpression(node.alternate))
    this.patch(toEnd, this.here())
    this.releaseTemp(slot)
    return this.emit('getSlot', slot)
  }

  private compileChainRoot(node: Expression): Operand {
    const slot = this.allocTemp(node)
    this.emit('setSlot', slot, literalOperand(null))
    const chain: ChainState = { exits: [] }
    const value = node.type === 'MemberExpression' || node.type === 'CallExpression'
      ? this.compileMemberOrCall(node, chain)
      : this.compileExpression(node)
    this.emit('setSlot', slot, value)
    this.patchAll(chain.exits, this.here())
    this.releaseTemp(slot)
    return this.emit('getSlot', slot)
  }

  private compileChainOperand(node: Expression, chain: ChainState | null): Operand {
    const it = unwrap(node)
    if (chain !== null && (it.type === 'MemberExpression' || it.type === 'CallExpression')) {
      return this.compileMemberOrCall(it, chain)
    }
    return this.compileExpression(it)
  }

  private compileMemberKey(node: MemberExpression): Operand {
    if (!node.computed) {
      const property = unwrap(node.property)
      if (property.type !== 'Identifier') {
        fail(property, `${property.type} members are not supported in a routine`)
      }
      return literalOperand(property.name)
    }
    return this.compileExpression(node.property)
  }

  private compileMemberOrCall(node: MemberExpression | CallExpression, chain: ChainState | null): Operand {
    if (node.type === 'MemberExpression') return this.compileMember(node, chain)
    return this.compileCall(node, chain)
  }

  private compileMember(node: MemberExpression, chain: ChainState | null): Operand {
    if (this.options.mode === 'hook') {
      const hooked = this.compileHookRead(node)
      if (hooked !== null) return hooked
    }
    const object = this.compileChainOperand(node.object, chain)
    if (node.optional) {
      if (chain === null) fail(node, 'an optional member must be part of a chain')
      chain.exits.push(this.emitJump('jumpIfNull', object))
    }
    return this.emit('get', object, this.compileMemberKey(node))
  }

  private compileCall(node: CallExpression, chain: ChainState | null): Operand {
    if (node.optional) {
      fail(node, 'an optional call `?.()` is not supported in a routine')
    }
    const callee = unwrap(node.callee)
    if (callee.type !== 'MemberExpression') {
      fail(node, 'a routine can only call a member, such as `object.method(...)`')
    }
    if (this.options.mode === 'hook') {
      const hooked = this.compileHookCall(node, callee)
      if (hooked !== null) return hooked
    }
    if (this.isInuJvm(callee, 'callSuper')) {
      if (node.arguments.length < 3) {
        fail(node, '`inu.jvm.callSuper` takes a class, a receiver and a method name')
      }
      const [cls, receiver, name, ...args] = this.compileArguments(node.arguments, node)
      return this.emit('callSuper', cls, receiver, name, args)
    }
    const superReceiver = this.getSuperOfReceiver(callee.object)
    if (superReceiver !== null) {
      if (callee.optional) fail(callee, '`inu.jvm.superOf(this)` is never null')
      const owner = this.emit('owner')
      const receiver = this.compileExpression(superReceiver)
      const key = this.compileMemberKey(callee)
      return this.emit('callSuper', owner, receiver, key, this.compileArguments(node.arguments, node))
    }
    if (this.isInuJvm(callee, 'superOf')) {
      fail(node, '`inu.jvm.superOf(this)` is only called through, as `inu.jvm.superOf(this).method(...)`')
    }
    const target = this.compileChainOperand(callee.object, chain)
    if (callee.optional) {
      if (chain === null) fail(callee, 'an optional member must be part of a chain')
      chain.exits.push(this.emitJump('jumpIfNull', target))
    }
    const key = this.compileMemberKey(callee)
    return this.emit('call', target, key, this.compileArguments(node.arguments, node))
  }

  /** `inu` is the engine's global here, never a capture, unless the routine declares its own */
  private isInuJvm(callee: MemberExpression, member: string): boolean {
    const isProperty = (node: MemberExpression, name: string) =>
      !node.computed && !node.optional && node.property.type === 'Identifier' && node.property.name === name
    if (!isProperty(callee, member)) return false
    const namespace = unwrap(callee.object)
    if (namespace.type !== 'MemberExpression' || !isProperty(namespace, 'jvm')) return false
    const root = unwrap(namespace.object)
    return root.type === 'Identifier' && root.name === 'inu' && this.lookup('inu') === null
  }

  /** the `this` of `inu.jvm.superOf(this)`, whose class is the one the host binds the routine to as a defineClass body */
  private getSuperOfReceiver(node: MemberExpression['object']): Expression | null {
    const call = unwrap(node)
    if (call.type !== 'CallExpression') return null
    const callee = unwrap(call.callee)
    if (callee.type !== 'MemberExpression' || !this.isInuJvm(callee, 'superOf')) return null
    if (this.options.mode === 'hook') fail(call, '`inu.jvm.superOf` needs a defineClass body, which a hook routine is not')
    if (call.optional) fail(call, '`inu.jvm.superOf` is never null')
    const [receiver] = call.arguments
    if (call.arguments.length !== 1 || receiver.type === 'SpreadElement' || unwrap(receiver).type !== 'ThisExpression') {
      fail(call, '`inu.jvm.superOf` takes `this`')
    }
    return receiver
  }

  private compileHookRead(node: MemberExpression): Operand | null {
    if (this.isCtx(node.object)) {
      if (node.optional) fail(node, 'the hook context is never null')
      if (node.computed) fail(node, 'the hook context takes a named property')
      const name = unwrap(node.property).name as string
      if (name === 'thisObject') return this.thisRegister()
      if (name === 'args') fail(node, '`args` can only be indexed or measured with `.length`')
      const op = CTX_PROPERTIES[name]
      if (!op) fail(node, `\`${name}\` is not a hook context property`)
      return this.emit(op)
    }
    if (this.isCtxArgs(node.object)) {
      if (node.optional) fail(node, 'the hook arguments are never null')
      if (node.computed) return this.emit('arg', this.compileExpression(node.property))
      const name = unwrap(node.property).name as string
      if (name !== 'length') fail(node, `\`args.${name}\` is not available in a routine`)
      return this.emit('argCount')
    }
    return null
  }

  private compileHookCall(node: CallExpression, callee: MemberExpression): Operand | null {
    if (!this.isCtx(callee.object)) {
      if (this.isCtxArgs(callee.object) || this.isCtxArgs(callee)) {
        fail(callee, 'the hook arguments have no methods')
      }
      return null
    }
    if (callee.computed) fail(callee, 'the hook context takes a named method')
    const name = unwrap(callee.property).name as string
    const op = CTX_METHODS[name]
    if (!op) fail(callee, `\`${name}\` is not a hook context method`)
    if (node.arguments.length !== 1) {
      fail(node, `\`${name}\` takes exactly one argument`)
    }
    const [argument] = node.arguments
    if (argument.type === 'SpreadElement') fail(argument, 'spread is not supported in a routine')
    return this.emit(op, this.compileExpression(argument))
  }

  private combineOperator(node: AssignmentExpression): OpName | null {
    const operator = node.operator as string
    if (operator === '=') return null
    if (operator === '&&=' || operator === '||=' || operator === '??=') {
      fail(node, `\`${operator}\` is not supported in a routine`)
    }
    const op = COMPOUND_OPS[operator.slice(0, -1)]
    if (!op) fail(node, `\`${operator}\` is not supported in a routine`)
    return op
  }

  private compileAssignmentExpression(node: AssignmentExpression): Operand {
    const combine = this.combineOperator(node)
    const target = unwrap(node.left)

    if (target.type === 'Identifier') {
      const binding = this.lookup(target.name)
      if (!binding) fail(target, `\`${target.name}\` is a capture and cannot be assigned`)
      if (binding.kind === 'pending') fail(target, `\`${target.name}\` is used before its declaration`)
      if (binding.kind !== 'let') {
        fail(target, `\`${target.name}\` is not a \`let\` binding and cannot be assigned`)
      }
      if (combine === null) {
        const right = this.compileExpression(node.right)
        this.emit('setSlot', binding.slot, right)
        return right
      }
      const current = this.emit('getSlot', binding.slot)
      const value = this.emit(combine, current, this.compileExpression(node.right))
      this.emit('setSlot', binding.slot, value)
      return value
    }

    if (target.type !== 'MemberExpression') {
      fail(target, `${target.type} is not an assignment target in a routine`)
    }
    if (hasOptionalChain(target)) {
      fail(target, 'an optional chain is not an assignment target')
    }

    if (this.options.mode === 'hook') {
      const hooked = this.compileHookWrite(target, combine, node)
      if (hooked !== null) return hooked
    }

    const object = this.compileExpression(target.object)
    const key = this.compileMemberKey(target)
    if (combine === null) {
      const right = this.compileExpression(node.right)
      this.emit('set', object, key, right)
      return right
    }
    const current = this.emit('get', object, key)
    const value = this.emit(combine, current, this.compileExpression(node.right))
    this.emit('set', object, key, value)
    return value
  }

  private compileHookWrite(target: MemberExpression, combine: OpName | null, node: AssignmentExpression): Operand | null {
    if (this.isCtx(target.object)) {
      fail(target, 'the hook context is read-only, use `ctx.setReturnValue(...)` or `ctx.setThrowable(...)`')
    }
    if (!this.isCtxArgs(target.object)) return null
    if (!target.computed) {
      fail(target, 'only `ctx.args[i]` can be assigned')
    }
    const index = this.compileExpression(target.property)
    if (combine === null) {
      const right = this.compileExpression(node.right)
      this.emit('setArg', index, right)
      return right
    }
    const current = this.emit('arg', index)
    const value = this.emit(combine, current, this.compileExpression(node.right))
    this.emit('setArg', index, value)
    return value
  }

  private compileUpdateExpression(node: UpdateExpression): Operand {
    const op: OpName = node.operator === '++' ? 'add' : 'sub'
    const target = unwrap(node.argument)
    const one = literalOperand(1)

    if (target.type === 'Identifier') {
      const binding = this.lookup(target.name)
      if (!binding) fail(target, `\`${target.name}\` is a capture and cannot be assigned`)
      if (binding.kind === 'pending') fail(target, `\`${target.name}\` is used before its declaration`)
      if (binding.kind !== 'let') {
        fail(target, `\`${target.name}\` is not a \`let\` binding and cannot be assigned`)
      }
      const old = this.emit('getSlot', binding.slot)
      const next = this.emit(op, old, one)
      this.emit('setSlot', binding.slot, next)
      return node.prefix ? next : old
    }

    if (hasOptionalChain(target)) {
      fail(target, 'an optional chain is not an assignment target')
    }

    if (this.options.mode === 'hook') {
      if (this.isCtx(target.object)) {
        fail(target, 'the hook context is read-only, use `ctx.setReturnValue(...)` or `ctx.setThrowable(...)`')
      }
      if (this.isCtxArgs(target.object)) {
        if (!target.computed) fail(target, 'only `ctx.args[i]` can be assigned')
        const index = this.compileExpression(target.property)
        const old = this.emit('arg', index)
        const next = this.emit(op, old, one)
        this.emit('setArg', index, next)
        return node.prefix ? next : old
      }
    }

    const object = this.compileExpression(target.object)
    const key = this.compileMemberKey(target)
    const old = this.emit('get', object, key)
    const next = this.emit(op, old, one)
    this.emit('set', object, key, next)
    return node.prefix ? next : old
  }
}

/** `source` is the full file text; the body's `start`/`end` are offsets into it. */
export function compileRoutine(fn: RoutineBody, source: string, options: CompileOptions): RoutineProgram {
  const plan: PrologueEntry[] = []
  new RoutineCompiler(fn, source, options, plan, true).compile()
  const compiler = new RoutineCompiler(fn, source, options, plan, false)
  compiler.compile()
  const program = compiler.buildProgram()
  assertWrittenRegisters(program)
  return program
}

/**
 * Reuse a register only if every incoming path has written it. The host reads skipped
 * registers as `null`, so it cannot catch this compiler bug. Do not hoist input reads
 * to fix it: they can throw, and moving them may change the exception handler.
 */
function assertWrittenRegisters(program: RoutineProgram): void {
  const first = findUndominatedReads(program.code, program.tries)[0]
  if (first === undefined) return
  const { at, register } = first
  throw new Error(`routine ${program.code[at][0]} at ${at} reads register ${register}, which a path can skip`)
}
