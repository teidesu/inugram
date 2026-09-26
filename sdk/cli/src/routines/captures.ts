import type {
  AssignmentTarget,
  AssignmentTargetMaybeDefault,
  AssignmentTargetRest,
  BindingPattern,
  BindingRestElement,
  Expression,
  ParamPattern,
  Program,
  Statement,
  VariableDeclaration,
} from '@oxc-project/types'
import type { RoutineCall } from './find.js'
import { Visitor } from 'oxc-parser'

type Kind = 'const' | 'import' | 'let' | 'var' | 'param' | 'function' | 'class'

interface Binding {
  kind: Kind
  assigned: boolean
}

interface Scope {
  bindings: Map<string, Binding>
  functionScope: boolean
}

interface Site {
  start: number
  end: number
}

export interface CaptureProblem {
  name: string
  message: string
  /** The first reference to the capture, for precise error locations. */
  start: number
  end: number
}

/**
 * Nodes that can bind or assign names. Includes `Expression` because assignment targets
 * can be wrapped in casts.
 */
type Bindable
  = | BindingPattern
    | AssignmentTarget
    | AssignmentTargetMaybeDefault
    | AssignmentTargetRest
    | BindingRestElement
    | ParamPattern
    | Expression

/** Finds all names bound by a pattern. */
function collectPatternNames(node: Bindable, into: string[]): void {
  switch (node.type) {
    case 'Identifier':
      into.push(node.name)
      return
    case 'ObjectPattern':
      for (const property of node.properties) {
        collectPatternNames(property.type === 'RestElement' ? property.argument : property.value, into)
      }
      return
    case 'ArrayPattern':
      for (const element of node.elements) {
        if (element !== null) collectPatternNames(element, into)
      }
      return
    case 'AssignmentPattern':
      collectPatternNames(node.left, into)
      return
    case 'RestElement':
      collectPatternNames(node.argument, into)
      return
    case 'TSParameterProperty':
      collectPatternNames(node.parameter, into)
      return
    case 'TSAsExpression':
    case 'TSSatisfiesExpression':
    case 'TSNonNullExpression':
    case 'TSTypeAssertion':
      collectPatternNames(node.expression, into)
  }
}

function unwrapExport(statement: Statement): Statement {
  if (statement.type === 'ExportNamedDeclaration' && statement.declaration !== null) {
    return statement.declaration
  }
  if (statement.type === 'ExportDefaultDeclaration') {
    const declared = statement.declaration
    if (declared.type === 'FunctionDeclaration' || declared.type === 'ClassDeclaration') return declared
  }
  return statement
}

function getDeclarationKind(declaration: VariableDeclaration): Kind {
  if (declaration.kind === 'var') return 'var'
  return declaration.kind === 'const' ? 'const' : 'let'
}

/**
 * Resolves captures in the enclosing file and tracks assignments to their bindings.
 * Captures are snapshots taken at routine construction, so later assignments would leave
 * stale values without a runtime error.
 */
class FileScopes {
  private readonly scopes: Scope[] = []
  private readonly chains = new Map<number, Scope[]>()
  private readonly uses = new Map<number, Map<string, Site>>()
  private readonly open: number[] = []

  constructor(private readonly wanted: Set<number>) {}

  private enter(functionScope: boolean): void {
    this.scopes.push({ bindings: new Map(), functionScope })
  }

  private leave(): void {
    this.scopes.pop()
  }

  private declare(name: string, kind: Kind): void {
    const scope = kind === 'var'
      ? [...this.scopes].reverse().find(it => it.functionScope) ?? this.scopes[this.scopes.length - 1]
      : this.scopes[this.scopes.length - 1]
    if (!scope.bindings.has(name)) scope.bindings.set(name, { kind, assigned: false })
  }

  private declarePattern(node: Bindable, kind: Kind): void {
    const names: string[] = []
    collectPatternNames(node, names)
    for (const name of names) this.declare(name, kind)
  }

  private declareVariables(declaration: VariableDeclaration): void {
    const kind = getDeclarationKind(declaration)
    for (const declarator of declaration.declarations) this.declarePattern(declarator.id, kind)
  }

  /** Collects declarations visible throughout a block, regardless of their position. */
  private declareStatements(statements: readonly Statement[]): void {
    for (const raw of statements) {
      const statement = unwrapExport(raw)
      switch (statement.type) {
        case 'VariableDeclaration':
          if (statement.kind !== 'var') this.declareVariables(statement)
          break
        case 'FunctionDeclaration':
          if (statement.id !== null) this.declare(statement.id.name, 'function')
          break
        case 'ClassDeclaration':
          if (statement.id !== null) this.declare(statement.id.name, 'class')
          break
        case 'ImportDeclaration':
          for (const specifier of statement.specifiers ?? []) this.declare(specifier.local.name, 'import')
          break
      }
    }
  }

  /** Scan for `var` declarations before visiting a function body, including those in nested blocks. */
  private hoistVars(statements: readonly Statement[]): void {
    const walk = (statement: Statement): void => {
      const it = unwrapExport(statement)
      switch (it.type) {
        case 'VariableDeclaration':
          if (it.kind === 'var') this.declareVariables(it)
          return
        case 'BlockStatement':
          it.body.forEach(walk)
          return
        case 'IfStatement':
          walk(it.consequent)
          if (it.alternate !== null) walk(it.alternate)
          return
        case 'ForStatement':
          if (it.init !== null && it.init.type === 'VariableDeclaration') walk(it.init)
          walk(it.body)
          return
        case 'ForInStatement':
        case 'ForOfStatement':
          if (it.left.type === 'VariableDeclaration') walk(it.left)
          walk(it.body)
          return
        case 'WhileStatement':
        case 'DoWhileStatement':
        case 'LabeledStatement':
          walk(it.body)
          return
        case 'SwitchStatement':
          for (const branch of it.cases) branch.consequent.forEach(walk)
          return
        case 'TryStatement':
          walk(it.block)
          if (it.handler !== null) walk(it.handler.body)
          if (it.finalizer !== null) walk(it.finalizer)
      }
    }
    statements.forEach(walk)
  }

  private resolve(name: string): Binding | null {
    for (let i = this.scopes.length - 1; i >= 0; i--) {
      const binding = this.scopes[i].bindings.get(name)
      if (binding) return binding
    }
    return null
  }

  private markAssigned(node: Bindable): void {
    const names: string[] = []
    collectPatternNames(node, names)
    for (const name of names) {
      const binding = this.resolve(name)
      if (binding) binding.assigned = true
    }
  }

  private enterFunction(params: readonly ParamPattern[], body: Statement | null): void {
    this.enter(true)
    for (const param of params) this.declarePattern(param, 'param')
    if (body !== null && body.type === 'BlockStatement') this.hoistVars(body.body)
  }

  read(program: Program): void {
    const openBlock = (body: readonly Statement[]) => {
      this.enter(false)
      this.declareStatements(body)
    }
    const openLoop = (head: VariableDeclaration | AssignmentTarget | null) => {
      this.enter(false)
      if (head === null) return
      if (head.type === 'VariableDeclaration') {
        if (head.kind !== 'var') this.declareVariables(head)
      } else {
        this.markAssigned(head)
      }
    }

    new Visitor({
      'Program': (node) => {
        this.enter(true)
        this.hoistVars(node.body)
        this.declareStatements(node.body)
      },
      'Program:exit': () => this.leave(),

      'FunctionDeclaration': node => this.enterFunction(node.params, node.body),
      'FunctionDeclaration:exit': () => this.leave(),
      'FunctionExpression': node => this.enterFunction(node.params, node.body),
      'FunctionExpression:exit': () => this.leave(),
      'ArrowFunctionExpression': node => this.enterFunction(node.params, node.body.type === 'BlockStatement' ? node.body : null),
      'ArrowFunctionExpression:exit': () => this.leave(),

      'BlockStatement': node => openBlock(node.body),
      'BlockStatement:exit': () => this.leave(),
      'StaticBlock': node => openBlock(node.body),
      'StaticBlock:exit': () => this.leave(),

      'ForStatement': node => openLoop(node.init !== null && node.init.type === 'VariableDeclaration' ? node.init : null),
      'ForStatement:exit': () => this.leave(),
      'ForInStatement': node => openLoop(node.left),
      'ForInStatement:exit': () => this.leave(),
      'ForOfStatement': node => openLoop(node.left),
      'ForOfStatement:exit': () => this.leave(),

      'CatchClause': (node) => {
        this.enter(false)
        if (node.param !== null) this.declarePattern(node.param, 'let')
      },
      'CatchClause:exit': () => this.leave(),

      'AssignmentExpression': node => this.markAssigned(node.left),
      'UpdateExpression': (node) => {
        if (node.argument.type === 'Identifier') this.markAssigned(node.argument)
      },

      'CallExpression': (node) => {
        if (!this.wanted.has(node.start)) return
        this.chains.set(node.start, this.scopes.slice())
        this.uses.set(node.start, new Map())
        this.open.push(node.start)
      },
      'CallExpression:exit': (node) => {
        if (this.wanted.has(node.start)) this.open.pop()
      },
      'Identifier': (node) => {
        const seen = this.uses.get(this.open[this.open.length - 1] ?? -1)
        if (seen !== undefined && !seen.has(node.name)) {
          seen.set(node.name, { start: node.start, end: node.end })
        }
      },
    }).visit(program)
  }

  /** The name's first source occurrence, or none for compiler-generated names. */
  findFirstUse(start: number, name: string): Site | null {
    return this.uses.get(start)?.get(name) ?? null
  }

  describe(start: number, name: string): string | null {
    const chain = this.chains.get(start)
    if (chain === undefined) return null
    for (let i = chain.length - 1; i >= 0; i--) {
      const binding = chain[i].bindings.get(name)
      if (binding === undefined) continue
      if (binding.kind === 'function' || binding.kind === 'class') {
        return `\`${name}\` is a ${binding.kind} declaration, which a routine cannot capture`
      }
      if (binding.assigned) {
        return `\`${name}\` is assigned after it is declared, and a capture is taken by value when the routine is built, so the routine would run with a stale one`
      }
      return null
    }
    return `\`${name}\` is not declared in this file, so it is a global, which a routine cannot capture`
  }
}

/**
 * Resolves [names] in the enclosing scope of each routine in [calls].
 * The compiler cannot do this itself because it only receives the routine body.
 */
export function checkCaptures(
  program: Program,
  calls: { call: RoutineCall, names: readonly string[] }[],
): Map<number, CaptureProblem[]> {
  const scopes = new FileScopes(new Set(calls.map(it => it.call.start)))
  scopes.read(program)

  const problems = new Map<number, CaptureProblem[]>()
  for (const { call, names } of calls) {
    const found: CaptureProblem[] = []
    for (const name of names) {
      const message = scopes.describe(call.start, name)
      if (message === null) continue
      const at = scopes.findFirstUse(call.start, name) ?? { start: call.start, end: call.end }
      found.push({ name, message, start: at.start, end: at.end })
    }
    if (found.length > 0) problems.set(call.start, found)
  }
  return problems
}
