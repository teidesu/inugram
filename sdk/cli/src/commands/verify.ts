import type { Argument, Expression, SpreadElement } from '@oxc-project/types'
import type { PartialMessage } from 'esbuild'
import fs from 'node:fs/promises'
import { relative, resolve } from 'node:path'
import * as v from 'valibot'
import { compileRoutine, RoutineCompileError } from '../routines/compile.js'
import { dedentRoutineSource } from '../routines/emit.js'
import { findRoutineCalls, parseFile } from '../routines/find.js'
import { RoutineProgramSchema } from '../routines/ops.js'
import { defineCommand } from '../utils/args.js'
import { CliError, fail, messageAt, printMessages, success } from '../utils/log.js'
import { describeIssue } from '../utils/schema.js'

interface Verdict {
  problem: string | null
  /** the span of the routine call in the built file, which is what an error points at */
  start: number
  end: number
}

/**
 * Compare parsed routine values, ignoring formatting changes from the bundler.
 * Compiled routines contain only literals, so no evaluation is needed.
 */
function readLiteral(node: Expression | SpreadElement | null): unknown {
  if (node === null) throw new CliError('not a literal')
  switch (node.type) {
    case 'ParenthesizedExpression':
      return readLiteral(node.expression)
    case 'Literal': {
      const value = node.value
      if (value === null) return null
      // a bigint crosses as `['L', '123']` and a regex is refused, so neither is ever emitted
      if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') return value
      throw new CliError('not a literal')
    }
    case 'TemplateLiteral': {
      if (node.expressions.length > 0) throw new CliError('not a literal')
      return node.quasis[0].value.cooked ?? ''
    }
    case 'ArrayExpression':
      return node.elements.map(element => readLiteral(element))
    case 'ObjectExpression': {
      const object: Record<string, unknown> = {}
      for (const property of node.properties) {
        if (property.type !== 'Property' || property.computed) throw new CliError('not a literal')
        const key = property.key
        const name = key.type === 'Identifier' ? key.name : key.type === 'Literal' ? key.value : null
        if (typeof name !== 'string') throw new CliError('not a literal')
        object[name] = readLiteral(property.value)
      }
      return object
    }
    case 'UnaryExpression': {
      if (node.operator !== '-') throw new CliError('not a literal')
      const value = readLiteral(node.argument)
      if (typeof value !== 'number') throw new CliError('not a literal')
      return -value
    }
    default:
      throw new CliError('not a literal')
  }
}

/**
 * Esbuild can rename or inline captures. Check the number of positional values,
 * not their surrounding binding names.
 */
function countCaptures(node: Argument | undefined): number | null {
  if (node === undefined) return 0
  if (node.type !== 'ArrayExpression') return null
  for (const element of node.elements) {
    if (element === null || element.type === 'SpreadElement') return null
  }
  return node.elements.length
}

/**
 * Recompile the recorded source and compare the output. The compiler is a pure function
 * of the parsed body, so this check requires no execution.
 */
export function verifyFile(file: string, source: string): Verdict[] {
  const parsed = parseFile(file, source)
  if (parsed.errors.length > 0) {
    const at = parsed.errors[0].labels[0]
    return [{
      problem: `cannot parse: ${parsed.errors[0].message}`,
      start: at?.start ?? 0,
      end: at?.end ?? 1,
    }]
  }

  return findRoutineCalls(parsed.program).map((call): Verdict => {
    const where = { start: call.start, end: call.end }
    if (call.arguments.length > 2) return { ...where, problem: 'has unexpected routine arguments' }
    let literal: unknown
    try {
      literal = readLiteral(call.body ?? null)
    } catch (error) {
      if (error instanceof CliError) return { ...where, problem: 'is not a compiled routine' }
      throw error
    }
    const parsedProgram = v.safeParse(RoutineProgramSchema, literal)
    if (!parsedProgram.success) {
      const issues = parsedProgram.issues.map(issue => describeIssue(issue)).join('\n')
      return { ...where, problem: `is not a compiled routine:\n${issues}` }
    }
    const built = parsedProgram.output

    let recordedSource: string
    try {
      recordedSource = dedentRoutineSource(built.source)
    } catch {
      return { ...where, problem: 'recorded source has an inconsistent margin' }
    }
    if (recordedSource === '') {
      return { ...where, problem: 'no source code available' }
    }

    const body = parseFile('routine.ts', `(${recordedSource})`)
    if (body.errors.length > 0) {
      return { ...where, problem: 'recorded source code does not parse' }
    }

    const statement = body.program.body[0]
    let expression = statement?.type === 'ExpressionStatement' ? statement.expression : null
    while (expression?.type === 'ParenthesizedExpression') expression = expression.expression
    if (expression?.type !== 'FunctionExpression' && expression?.type !== 'ArrowFunctionExpression') {
      return { ...where, problem: 'recorded source code is not a function' }
    }

    try {
      const program = compileRoutine(expression, `(${recordedSource})`, { mode: call.mode })
      if (
        JSON.stringify([program.v, program.captures, program.slots, program.code, program.tries])
        !== JSON.stringify([built.v, built.captures, built.slots, built.code, built.tries])
      ) {
        return { ...where, problem: 'does not match its recorded source' }
      }
      const passed = countCaptures(call.arguments[1])
      if (passed === null || passed !== program.captures.length) {
        return { ...where, problem: 'is handed a capture count its source does not declare' }
      }
      return { ...where, problem: null }
    } catch (error) {
      if (error instanceof RoutineCompileError) {
        return { ...where, problem: `recorded source does not compile: ${error.message}` }
      }
      if (error instanceof CliError) return { ...where, problem: 'is not a compiled routine' }
      throw error
    }
  })
}

export const verifyCmd = defineCommand({
  meta: {
    name: 'verify',
    description: 'check that every compiled routine in a built plugin matches its recorded source',
  },
  args: {
    files: {
      type: 'positional',
      required: true,
      description: 'built plugin files to verify',
    },
  },
  run: async ({ rawArgs }) => {
    const files = rawArgs.filter(argument => !argument.startsWith('-'))
    if (files.length === 0) throw new CliError('verify: name at least one built plugin')

    let checked = 0
    const problems: PartialMessage[] = []
    for (const name of files) {
      const file = resolve(process.cwd(), name)
      const source = await fs.readFile(file, 'utf8')
      const where = relative(process.cwd(), file)
      for (const verdict of verifyFile(where, source)) {
        checked += 1
        if (verdict.problem === null) continue
        problems.push(messageAt(where, source, verdict.problem, verdict.start, verdict.end))
      }
    }

    if (problems.length > 0) {
      fail(`${problems.length} routine${problems.length === 1 ? '' : 's'} did not check out`)
      await printMessages(problems, 'error')
      process.exitCode = 1
      return
    }
    success(`${checked} routine${checked === 1 ? '' : 's'} match their recorded source`)
  },
})
