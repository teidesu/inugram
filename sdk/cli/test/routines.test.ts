import type { Expression, ObjectExpression } from '@oxc-project/types'
import type { RoutineBody } from '../src/routines/compile.js'
import type { RoutineCall } from '../src/routines/find.js'
import type { Instruction, RoutineProgram, TryRegion } from '../src/routines/ops.js'
import * as v from 'valibot'
import { describe, expect, it } from 'vitest'
import { checkCaptures } from '../src/routines/captures.js'
import { compileRoutine, RoutineCompileError } from '../src/routines/compile.js'
import { dedentRoutineSource, emitRoutineCall } from '../src/routines/emit.js'
import { findRoutineCalls, parseFile } from '../src/routines/find.js'
import { MAX_CAPTURES, MAX_INSTRUCTIONS, MAX_SLOTS, RoutineProgramSchema } from '../src/routines/ops.js'
import { describeIssue } from '../src/utils/schema.js'
import { verifyProgram } from './verifier.js'

type Mode = 'method' | 'hook'

/** Runs the same pre-compile checks as the esbuild hook. */
function bodyOf(call: RoutineCall): RoutineBody {
  const body = call.body
  if (body?.type !== 'FunctionExpression' && body?.type !== 'ArrowFunctionExpression') {
    throw new RoutineCompileError('a routine must be a function expression', call.start, call.end)
  }
  return body
}

function compileBody(text: string, mode: Mode = 'method'): RoutineProgram {
  const wrapped = `inu.${mode === 'hook' ? 'xposed' : 'jvm'}.routine(${text})`
  const parsed = parseFile('routine.ts', wrapped)
  if (parsed.errors.length > 0) throw new RoutineCompileError(parsed.errors[0].message, 0, 1)
  const call = findRoutineCalls(parsed.program)[0]
  return compileRoutine(bodyOf(call), wrapped, { mode, file: 'routine.ts' })
}

/** Checks host verification, wire schema, and deterministic compilation. */
function accepts(text: string, mode: Mode = 'method') {
  const program = compileBody(text, mode)
  verifyProgram(program, mode === 'hook')
  const onTheWire = v.safeParse(RoutineProgramSchema, JSON.parse(JSON.stringify(program)))
  expect(onTheWire.issues?.map(issue => describeIssue(issue)) ?? []).toEqual([])
  expect(JSON.stringify(compileBody(text, mode))).toBe(JSON.stringify(program))
  return program
}

function refuses(text: string, mode: Mode = 'method') {
  expect(() => compileBody(text, mode)).toThrow(RoutineCompileError)
}

/** Reads a named plain property from the emitted program. */
function propertyOf(object: ObjectExpression, name: string): Expression {
  for (const property of object.properties) {
    if (property.type !== 'Property') continue
    if (property.key.type !== 'Identifier' || property.key.name !== name) continue
    return property.value
  }
  throw new Error(`the emitted program has no \`${name}\``)
}

/** Checks captures using the enclosing file, which the compiler alone cannot inspect. */
function captureProblems(file: string): string[] {
  const parsed = parseFile('plugin.ts', file)
  expect(parsed.errors).toEqual([])
  const calls = findRoutineCalls(parsed.program).map(call => ({
    call,
    names: compileRoutine(bodyOf(call), file, { mode: call.mode, file: 'plugin.ts' }).captures,
  }))
  return [...checkCaptures(parsed.program, calls).values()].flat().map(it => it.name)
}

const METHOD_BODIES: Record<string, string[]> = {
  'reads and writes': [
    'function () { }',
    'function () { return 1 }',
    'function (a, b) { return a + b }',
    'function (a) { return this.get(a) }',
    'function (a) { this.x = a; return this.x }',
    'function (a) { return this[a]() }',
    'function (a) { return this.items[a] }',
    'function (a) { this.items[a] = 1 }',
    'function (a) { return this.items.length }',
    'function (a) { return a.b.c.d.e() }',
    'function (a) { a.f(1, 2, 3, four, \'five\'); return 0 }',
    'function (a) { return table[a] }',
  ],
  'operators': [
    'function (a) { return -a }',
    'function (a) { return ~a }',
    'function (a) { return !a }',
    'function (a) { return a & 1 | 2 ^ 3 }',
    'function (a) { return a << 1 >> 2 >>> 3 }',
    'function (a) { return a === 1 ? \'x\' : \'y\' }',
    'function (a) { return a && b || c }',
    'function (a) { return a ?? fallback }',
    'function (a) { return a?.b?.c() }',
    'function (a) { return a?.[0]?.d }',
    'function (a) { return a instanceof Thing }',
    'function () { return 9007199254740993n }',
    'function () { return 1.5 / 2 % 3 }',
    // eslint-disable-next-line no-template-curly-in-string
    'function (a) { return `x${a}y${a + 1}z` }',
    'function () { return `plain` }',
  ],
  'construction': [
    'function () { return new Thing(1, \'x\') }',
    'function () { return [1, 2, three] }',
  ],
  'bindings': [
    'function (a) { let x = a; x += 1; x -= 2; x *= 3; return x }',
    'function () { let x = 0; return x++ + ++x }',
    'function () { this.n += 1; return this.n++ }',
    'function () { this.items[0] += 5; return this.items[0]++ }',
    'function (a) { let x; if (a) x = 1; return x }',
    'function (a) { const b = a; { const b = 2; a.use(b) } return b }',
  ],
  'control flow': [
    'function (a) { if (a) return 1; else return 2 }',
    'function (a) { if (a) { return 1 } return 2 }',
    'function (a) { let t = 0; while (a.more()) { t += 1; if (t > 5) break; else continue } return t }',
    'function () { let t = 0; do { t += 1 } while (t < 3); return t }',
    'function () { let t = 0; for (let i = 0; i < 10; i++) { if (i === 3) continue; t += i } return t }',
    'function (a) { let t = 0; for (const x of a.list()) { t += x } return t }',
    'function (a) { let t = 0; for (const x of a.list()) { if (x) continue; break } return t }',
    'function (a) { outer: for (const x of a.l()) { for (const y of x.l()) { if (y) continue outer; break outer } } return 1 }',
    'function (a) { block: { if (a) break block; return 1 } return 2 }',
  ],
  'switch': [
    'function (a) { switch (a) { case 1: return \'a\'; case 2: case 3: return \'b\'; default: return \'c\' } }',
    'function (a) { let r = 0; switch (a) { case 1: r = 1; case 2: r = 2; break; default: r = 3 } return r }',
    'function (a) { switch (a) { case 1: { const n = a.f(); a.g(n); break } default: a.h() } }',
    'function (a) { switch (a) { case 1: break } return 0 }',
  ],
  'try': [
    'function (a) { try { return a.f() } catch (e) { return e } }',
    'function (a) { try { return a.f() } catch { return 0 } }',
    'function (a) { try { a.f() } finally { a.g() } return 1 }',
    'function (a) { try { return a.f() } catch (e) { throw e } finally { a.g() } }',
    'function (a) { let t = 0; for (const x of a.l()) { try { t += x } finally { a.g() } } return t }',
    'function (a) { for (const x of a.l()) { try { if (x) break; if (!x) continue } finally { a.g() } } return 1 }',
    'function (a) { try { try { a.f() } finally { a.g() } } finally { a.h() } }',
    'function () { try { } catch (e) { } finally { return 1 } }',
    'function () { try { return 2 } finally { return 1 } }',
  ],
  'typescript': [
    'function (a: number, b: string) { return (a as any) + b! }',
    'function (this: any, a: number) { return this.f(a) }',
  ],
}

const HOOK_BODIES = [
  'function (ctx) { }',
  'ctx => { ctx.setReturnValue(1) }',
  '(ctx) => ctx.setReturnValue(ctx.returnValue)',
  'ctx => ctx.thisObject.f(ctx.args[0])',
  'function (ctx) { return }',
  'function (ctx) { ctx.setReturnValue(1) }',
  'function (ctx) { ctx.setThrowable(err) }',
  'function (ctx) { if (ctx.args.length === 0) return; ctx.args[0] = ctx.args[1] }',
  'function (ctx) { ctx.setReturnValue(ctx.returnValue) }',
  'function (ctx) { if (ctx.throwable !== null) ctx.setReturnValue(0) }',
  'function (ctx) { ctx.setReturnValue(ctx.thisObject.f(ctx.method)) }',
  'function (ctx) { for (let i = 0; i < ctx.args.length; i++) { ctx.args[i] = null } }',
  'function (ctx) { let n = 0; for (const x of ctx.thisObject.l()) { n += 1 } ctx.setReturnValue(n) }',
]

const OUTSIDE_THE_SUBSET = [
  'function (a) { return a == 1 }',
  'function (a) { for (const k in a) { } }',
  'function () { var x = 1; return x }',
  'function (a) { return f(a) }',
  'function () { return { x: 1 } }',
  'function (a) { return typeof a }',
  'function (a) { return a ** 2 }',
  'function (a) { return +a }',
  'function (a) { const [x] = a; return x }',
  'function (a) { return () => a }',
  'function () { function g() {} return g }',
  'function (a) { return a in b }',
  'function (a) { a.f(...b) }',
  'function (a) { return /x/.test(a) }',
  'function () { return arguments }',
  'function (a) { return await a }',
]

const HOOK_OUTSIDE_THE_SUBSET = [
  'function (ctx) { return 1 }',
  'ctx => this.f()',
  'async ctx => ctx.setReturnValue(1)',
  'function (ctx) { return this.f() }',
  'function (ctx) { const c = ctx; c.setReturnValue(1) }',
  'function (ctx) { ctx.nope() }',
  'function (ctx) { other.f(ctx) }',
  'function (ctx) { ctx.returnValue = 1 }',
]

describe('the routine compiler', () => {
  for (const [group, bodies] of Object.entries(METHOD_BODIES)) {
    describe(group, () => {
      for (const body of bodies) it(body, () => void accepts(body))
    })
  }

  describe('hook mode', () => {
    for (const body of HOOK_BODIES) it(body, () => void accepts(body, 'hook'))
    for (const body of HOOK_OUTSIDE_THE_SUBSET) it(`refuses ${body}`, () => refuses(body, 'hook'))
  })

  describe('outside the subset', () => {
    for (const body of OUTSIDE_THE_SUBSET) it(body, () => refuses(body))
  })

  describe('bindings the lowering could get wrong', () => {
    it('refuses assigning a const or a parameter', () => {
      refuses('function () { const c = 1; c = 2; return c }')
      refuses('function (a) { a = 2; return a }')
    })

    it('refuses a read before its declaration', () => {
      refuses('function () { return x; const x = 1 }')
    })

    it('refuses a lexical declaration a later case can skip', () => {
      refuses('function (a) { switch (a) { case 1: const n = a.f(); case 2: a.g(n) } }')
    })

    it('resolves a finally in the scope its try sits in, not the exit it was inlined at', () => {
      const program = accepts(
        'function (x) { try { const lock = x.acquire(); if (lock) return lock } finally { x.release(lock) } return null }',
      )
      const released = program.code.filter(node => node[0] === 'call' && JSON.stringify(node[2]) === '["release"]')
      expect(released).toHaveLength(3)
      expect(new Set(released.map(node => JSON.stringify(node[3]))).size).toBe(1)
    })

    it('takes back the slot a settled expression no longer needs', () => {
      const chains = Array.from({ length: 60 }, (_, i) => `a?.p${i}`).join(' + ')
      expect(accepts(`function (a) { return ${chains} }`).slots).toBe(1)
      expect(accepts('function (a, b, c) { return a === 1 && b === 2 && c === 3 }').slots).toBe(1)
    })

    it('keeps a temporary its own while an expression nested in it is still using one', () => {
      expect(accepts('function (a) { return a?.[b?.c]?.d }').slots).toBe(2)
    })

    it('gives a shadowing declaration storage of its own', () => {
      const program = accepts('function (a) { let b = 1; { let b = 2; a.use(b) } return b }')
      expect(program.slots).toBe(2)
    })
  })

  describe('where the receiver and the arguments are read', () => {
    const first = (program: RoutineProgram, op: string) => program.code.findIndex(node => node[0] === op)

    it('reads them past a guard that answers without them', () => {
      const program = accepts('function (a, b, c) { if (a !== 1) { return false } return this.f(b, c) }')
      expect(first(program, 'arg')).toBe(0)
      expect(first(program, 'return')).toBeLessThan(first(program, 'this'))
    })

    it('reads a receiver only the taken branch wants inside that branch', () => {
      const program = accepts('function (a) { if (a > 10) { return this.f(a) } return 0 }')
      expect(first(program, 'this')).toBeGreaterThan(first(program, 'jumpIfFalsy'))
    })

    it('reads an argument a short circuit may never reach only past that circuit', () => {
      const program = accepts('function (a, b) { return a === 1 && b === 2 }')
      expect(first(program, 'jumpIfFalsy')).toBeLessThan(program.code.findLastIndex(node => node[0] === 'arg'))
    })

    it('reads a receiver independently on branches that do not dominate each other', () => {
      const program = accepts('function (a) { if (a) { return this.f() } return this.g() }')
      expect(first(program, 'this')).toBeGreaterThan(first(program, 'jumpIfFalsy'))
      expect(program.code.filter(node => node[0] === 'this')).toHaveLength(2)
    })

    it('keeps a receiver inside a loop that may never execute', () => {
      const program = accepts('function (a) { let t = 0; for (const x of a.list()) { t = t + this.w(x) } return t }')
      expect(first(program, 'this')).toBeGreaterThan(first(program, 'advance'))
    })

    it('keeps a finalizer receiver after the protected call', () => {
      const program = accepts('function (a) { try { return a.f() } finally { this.done() } }')
      expect(first(program, 'this')).toBeGreaterThan(first(program, 'call'))
    })

    it('reuses successful input reads without moving them', () => {
      const program = accepts('function (a) { this.f(a); if (a) return this.g(a); return this.h(a) }')
      expect(program.code.filter(node => node[0] === 'this')).toHaveLength(1)
      expect(program.code.filter(node => node[0] === 'arg')).toHaveLength(1)
    })

    it('keeps a throwing input read under the loop body handler', () => {
      const program = accepts('function (a) { try { while (true) { return a } } catch { return 0 } }')
      const input = first(program, 'arg')
      expect(input).toBeGreaterThan(first(program, 'jumpIfFalsy'))
      expect(program.tries.some(([start, end]) => input >= start && input < end)).toBe(true)
    })

    it('retries an input that might have thrown before the handler', () => {
      const program = accepts('function (a) { try { return a } catch { return a } }')
      expect(program.code.filter(node => node[0] === 'arg')).toHaveLength(2)
    })

    it('keeps hook argument reads live across writes', () => {
      const program = accepts('ctx => { const old = ctx.args[0]; ctx.args[0] = 1; ctx.setReturnValue(old + ctx.args[0]) }', 'hook')
      expect(program.code.filter(node => node[0] === 'arg')).toHaveLength(2)
    })
  })

  describe('captures', () => {
    it('takes each free identifier once, in order of first appearance', () => {
      const program = accepts('function (a) { return second.f(first) + second.g(first) }')
      expect(program.captures).toEqual(['second', 'first'])
    })

    it('never captures a member chain, which is a live java read', () => {
      const program = accepts('function () { return Foo.BAR }')
      expect(program.captures).toEqual(['Foo'])
    })

    it('refuses reaching the engine namespace', () => {
      refuses('function () { return inu.jvm.cls(\'x\') }')
    })
  })

  describe('inu.jvm.callSuper', () => {
    it('lowers to its own instruction, in argument order, without capturing `inu`', () => {
      const program = accepts('function (a) { return inu.jvm.callSuper(Base, this, \'draw\', a, 1) }')
      expect(program.captures).toEqual(['Base'])
      const call = program.code.find(node => node[0] === 'callSuper')!
      expect(call[3]).toEqual(['draw'])
      expect(call[4]).toHaveLength(2)
      expect(program.code.some(node => node[0] === 'call')).toBe(false)
    })

    it('takes a hook receiver', () => {
      const program = accepts('ctx => { ctx.setReturnValue(inu.jvm.callSuper(Base, ctx.thisObject, \'size\')) }', 'hook')
      expect(program.code.some(node => node[0] === 'callSuper')).toBe(true)
    })

    it('refuses fewer than a class, a receiver and a name', () => {
      refuses('function () { return inu.jvm.callSuper(Base, this) }')
    })

    it('is an ordinary call on a routine\'s own `inu`', () => {
      const program = accepts('function (inu) { return inu.jvm.callSuper(Base, this, \'draw\') }')
      expect(program.code.some(node => node[0] === 'callSuper')).toBe(false)
    })

    it('still refuses the rest of the engine namespace', () => {
      refuses('function () { return inu.jvm.callSuper.call(Base, this, \'draw\') }')
      refuses('function () { return inu.jvm?.callSuper(Base, this, \'draw\') }')
    })

    it('passes the capture check, since `inu` is never a capture', () => {
      expect(captureProblems('const Base = load()\ninu.jvm.routine(function () { return inu.jvm.callSuper(Base, this, \'draw\') })'))
        .toEqual([])
    })
  })

  describe('inu.jvm.superOf', () => {
    it('lowers to callSuper on the bound class, in argument order', () => {
      const program = accepts('function (a) { return inu.jvm.superOf(this).draw(a, 1) }')
      expect(program.captures).toEqual([])
      const owner = program.code.findIndex(node => node[0] === 'owner')
      const call = program.code.find(node => node[0] === 'callSuper')!
      expect(call[1]).toBe(owner)
      expect(call[3]).toEqual(['draw'])
      expect(call[4]).toHaveLength(2)
    })

    it('takes a computed member', () => {
      const program = accepts('function (name) { return inu.jvm.superOf(this)[name]() }')
      expect(program.code.some(node => node[0] === 'callSuper')).toBe(true)
    })

    it('refuses anything but a call through it on `this`', () => {
      refuses('function () { return inu.jvm.superOf(this) }')
      refuses('function () { const s = inu.jvm.superOf(this); return s.draw() }')
      refuses('function (a) { return inu.jvm.superOf(a).draw() }')
      refuses('function () { return inu.jvm.superOf().draw() }')
      refuses('function () { return inu.jvm.superOf(this)?.draw() }')
      refuses('() => inu.jvm.superOf(this).draw()')
    })

    it('is refused in a hook routine, which no class is bound to', () => {
      refuses('ctx => { ctx.setReturnValue(inu.jvm.superOf(ctx.thisObject).size()) }', 'hook')
    })
  })

  describe('what a capture resolves to in the file around it', () => {
    it('takes a const and an import', () => {
      expect(captureProblems('import { Paint } from \'x\'\nconst size = 1\ninu.jvm.routine(function () { return new Paint(size) })'))
        .toEqual([])
    })

    it('takes a let nothing assigns again, and a parameter', () => {
      expect(captureProblems('let cls = load()\ninu.jvm.routine(function () { return new cls() })')).toEqual([])
      expect(captureProblems('function build(cls) { return inu.jvm.routine(function () { return new cls() }) }'))
        .toEqual([])
    })

    it('refuses a global, which is not in the file at all', () => {
      expect(captureProblems('inu.jvm.routine(function (a) { return Math.max(a, 1) })')).toEqual(['Math'])
      expect(captureProblems('inu.jvm.routine(function (a) { console.log(a) })')).toEqual(['console'])
    })

    it('refuses a function or a class declaration', () => {
      expect(captureProblems('function helper() {}\ninu.jvm.routine(function () { return this.run(helper) })'))
        .toEqual(['helper'])
      expect(captureProblems('class Thing {}\ninu.jvm.routine(function () { return new Thing() })'))
        .toEqual(['Thing'])
    })

    it('refuses a binding assigned after it is declared, which would capture a stale value', () => {
      expect(captureProblems('let token = 1\ninu.jvm.routine(function () { return token })\ntoken = 2'))
        .toEqual(['token'])
      expect(captureProblems('var count = 1\nfunction bump() { count++ }\ninu.jvm.routine(function () { return count })'))
        .toEqual(['count'])
    })

    it('reads the binding the routine actually sees, not one of the same name elsewhere', () => {
      expect(captureProblems('let cls = 1\ncls = 2\nfunction build(cls) { return inu.jvm.routine(function () { return new cls() }) }'))
        .toEqual([])
      expect(captureProblems('const cls = 1\nfunction build() { let cls = load(); cls = other(); return inu.jvm.routine(function () { return new cls() }) }'))
        .toEqual(['cls'])
    })
  })

  describe('an arrow body', () => {
    it('is a hook routine like any other, because `this` is refused there anyway', () => {
      const program = accepts('ctx => { ctx.setReturnValue(1) }', 'hook')
      expect(JSON.stringify(program.code)).toBe(JSON.stringify(compileBody('function (ctx) { ctx.setReturnValue(1) }', 'hook').code))
    })

    it('takes a concise body, which is the expression as a statement', () => {
      const program = accepts('ctx => ctx.setReturnValue(1)', 'hook')
      expect(JSON.stringify(program.code)).toBe(JSON.stringify(compileBody('function (ctx) { ctx.setReturnValue(1) }', 'hook').code))
    })

    it('is a method routine too, so long as it never reads `this`', () => {
      const program = accepts('(a) => a + 1')
      expect(JSON.stringify(program.code)).toBe(JSON.stringify(compileBody('function (a) { return a + 1 }').code))
      accepts('a => { return a + 1 }')
    })

    it('is refused where it reads `this`, which an arrow does not have', () => {
      refuses('() => this.f()')
      refuses('(a) => { return this.f(a) }')
      accepts('function () { return this.f() }')
    })
  })

  describe('finding the calls', () => {
    it('takes the outermost one, since a routine cannot hold another', () => {
      const file = 'inu.jvm.routine(function () { inu.jvm.routine(function () {}) })'
      const calls = findRoutineCalls(parseFile('plugin.ts', file).program)
      expect(calls.length).toBe(1)
      expect(calls[0].start).toBe(0)
      expect(calls[0].end).toBe(file.length)
    })

    it('takes each of several that stand side by side', () => {
      const file = 'inu.jvm.routine(function () {})\ninu.xposed.routine(function (ctx) {})'
      const calls = findRoutineCalls(parseFile('plugin.ts', file).program)
      expect(calls.map(it => it.mode)).toEqual(['method', 'hook'])
    })
  })

  describe('the recorded source', () => {
    it('is the body verbatim, which is what inu verify recompiles', () => {
      const body = 'function (a: number) { return a + 1 }'
      expect(compileBody(body).source).toBe(body)
    })

    it('is written at the depth of the call with a reversible margin', () => {
      const emitted = emitRoutineCall('inu.jvm.routine', compileBody('function (a) {\n  return a\n}'), '    ')
      expect(emitted).toContain('`\n      function (a) {\n        return a\n      }`')
    })

    it('survives the template literal it is emitted into, backticks and all', () => {
      for (const body of [
        // eslint-disable-next-line no-template-curly-in-string
        'function (a) { return `x${a}y` }',
        'function (a) { return a.f(\'\\\\\') }',
        'function (a) {\n  return a\n}',
        'function (a) { return `a\\`b` }',
        'function () {\n  return `a\n    b\n \n\tc`\n}',
        'function () {\r\n\treturn `a\r\n b`\r\n}',
        '(a) => a + 1',
        'a => {\n  return a\n}',
      ]) {
        const emitted = emitRoutineCall('inu.jvm.routine', compileBody(body))
        const parsed = parseFile('built.ts', emitted)
        expect(parsed.errors).toEqual([])
        const call = findRoutineCalls(parsed.program)[0]
        const emittedProgram = call.argument
        if (emittedProgram?.type !== 'ObjectExpression') throw new Error('the emitted call carries no program')
        const recorded = propertyOf(emittedProgram, 'source')
        if (recorded.type !== 'TemplateLiteral') throw new Error('the recorded source is not a template literal')
        const cooked = recorded.quasis[0].value.cooked ?? ''
        const restored = dedentRoutineSource(cooked)
        expect(restored).toBe(body)
        expect(compileBody(restored).code).toEqual(compileBody(body).code)
      }
    })
  })
  describe('what the host would refuse', () => {
    const shaped = (code: Instruction[], tries: TryRegion[] = []): RoutineProgram =>
      ({ v: 1, source: '', captures: ['a'], slots: 1, code, tries })

    const refusedBy = (code: Instruction[], tries: TryRegion[] = [], mode: Mode = 'method') =>
      expect(() => verifyProgram(shaped(code, tries), mode === 'hook')).toThrow()

    const acceptedBy = (code: Instruction[], tries: TryRegion[] = [], mode: Mode = 'method') =>
      expect(() => verifyProgram(shaped(code, tries), mode === 'hook')).not.toThrow()

    it('refuses an instruction it does not know', () => {
      refusedBy([['nope']])
    })

    it('refuses a hook instruction in method mode', () => {
      refusedBy([['argCount']])
      acceptedBy([['argCount']], [], 'hook')
    })

    it('refuses an instruction with the wrong number of fields', () => {
      refusedBy([['this', 1]])
      refusedBy([['setSlot', 0]])
      refusedBy([['throw']])
    })

    it('refuses an index past what the program declared', () => {
      refusedBy([['capture', 7]])
      refusedBy([['getSlot', 9]])
    })

    it('refuses an operand that is not a register below it', () => {
      refusedBy([['arg', 5]])
      refusedBy([['arg', ['X', '1']]])
      refusedBy([['arg', [{}]]])
    })

    it('refuses a jump that goes backwards and a loop that does not', () => {
      refusedBy([['this'], ['jump', 0]])
      refusedBy([['loop', 5]])
      acceptedBy([['this'], ['jump', 2]])
    })

    it('refuses an iterator read by anything but its advance', () => {
      refusedBy([['this'], ['advance', 0, 2]])
      refusedBy([['this'], ['iterate', 0], ['not', 1]])
      acceptedBy([['this'], ['iterate', 0], ['advance', 1, 3], ['this']])
    })

    it('refuses a hook routine that returns a value', () => {
      refusedBy([['this'], ['return', 0]], [], 'hook')
      acceptedBy([['return']], [], 'hook')
      acceptedBy([['this'], ['return', 0]])
    })

    it('refuses a try region whose handler is not a catch, or that overlaps another', () => {
      refusedBy([['this'], ['this']], [[0, 1, 1]])
      refusedBy([['this'], ['this'], ['catch'], ['catch']], [[0, 2, 2], [1, 3, 3]])
    })
  })
  describe('the wire a built routine is read back from', () => {
    const wire = (program: Partial<RoutineProgram>) => v.safeParse(RoutineProgramSchema, {
      v: 1,
      source: 'function () {}',
      captures: [],
      slots: 1,
      code: [],
      tries: [],
      ...program,
    })

    const refusedWire = (program: Partial<RoutineProgram>) =>
      expect(wire(program).issues?.map(issue => describeIssue(issue)) ?? []).not.toEqual([])

    it('takes what the compiler emits', () => {
      expect(wire(compileBody('function (a) { return a.f(1) }')).success).toBe(true)
    })

    it('refuses an instruction it does not know, or one that is not an array', () => {
      refusedWire({ code: [['nope']] })
      refusedWire({ code: [[1]] as unknown as Instruction[] })
      refusedWire({ code: ['this'] as unknown as Instruction[] })
    })

    it('refuses an instruction with the wrong number of fields', () => {
      refusedWire({ code: [['this', 0]] })
      refusedWire({ code: [['setSlot', 0]] })
      refusedWire({ code: [['return', 0, 0]] })
    })

    it('takes a `return` with or without its operand, since only that field is optional', () => {
      expect(wire({ code: [['return']] }).success).toBe(true)
      expect(wire({ code: [['this'], ['return', 0]] }).success).toBe(true)
    })

    it('refuses a field that is not the kind its op names', () => {
      refusedWire({ code: [['getSlot', 'x']] })
      refusedWire({ code: [['arg', -1]] })
      refusedWire({ code: [['arg', [{}]]] })
      refusedWire({ code: [['arg', ['X', '1']]] })
      refusedWire({ code: [['this'], ['call', 0, 0, 0]] })
    })

    it('refuses a program past the limits the host enforces', () => {
      refusedWire({ slots: MAX_SLOTS + 1 })
      refusedWire({ code: Array.from({ length: MAX_INSTRUCTIONS + 1 }, (): Instruction => ['this']) })
      refusedWire({ captures: Array.from<string>({ length: MAX_CAPTURES + 1 }).fill('a') })
    })

    it('refuses a try region that is not three indices', () => {
      refusedWire({ tries: [[0, 1]] as unknown as TryRegion[] })
      refusedWire({ tries: [[0, 1, -1]] })
    })
  })
})
