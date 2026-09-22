import type { Plugin as EsbuildPlugin, Loader, PartialMessage } from 'esbuild'
import type { RoutineBody } from './compile.js'
import type { RoutineCall } from './find.js'
import fs from 'node:fs/promises'
import { messageAt } from '../utils/log.js'
import { checkCaptures } from './captures.js'
import { compileRoutine, isRoutineFunction, RoutineCompileError } from './compile.js'
import { emitRoutineCall } from './emit.js'
import { findRoutineCalls, languageOf, parseFile } from './find.js'

const LOADERS: Record<string, Loader> = { ts: 'ts', tsx: 'tsx', js: 'js', jsx: 'jsx' }

const ROUTINE_CALL = /\binu\s*\.\s*(?:jvm|xposed)\s*\.\s*routine\s*\(/

/** The leading whitespace on the line containing [offset], used when reprinting the call. */
function indentOf(source: string, offset: number): string {
  const line = source.slice(source.lastIndexOf('\n', offset - 1) + 1, offset)
  return line.slice(0, line.length - line.trimStart().length)
}

type BodyCheck = { body: RoutineBody, error?: undefined } | { body?: undefined, error: PartialMessage }

function checkCallShape(call: RoutineCall, file: string, source: string): BodyCheck {
  const what = `${call.callee}: `
  const body = call.body
  if (body === undefined) {
    return { error: messageAt(file, source, `${what}expected a routine body`, call.start, call.end) }
  }
  if (call.arguments.length > 1) {
    return { error: messageAt(file, source, `${what}a routine takes only its body`, call.start, call.end) }
  }
  if (!isRoutineFunction(body)) {
    return { error: messageAt(file, source, `${what}expected a function expression`, body.start, body.end) }
  }
  if (body.async || body.generator) {
    const message = `${what}a routine body is neither async nor a generator`
    return { error: messageAt(file, source, message, body.start, body.end) }
  }
  return { body }
}

/**
 * Compiles each `inu.*.routine(function () {})` in plugin sources.
 * Replaces only the call's span before esbuild bundles the file.
 */
export function compileRoutines(): EsbuildPlugin {
  return {
    name: 'inu-routines',
    setup(build) {
      build.onLoad({ filter: /\.[cm]?[jt]sx?$/ }, async (args) => {
        const source = await fs.readFile(args.path, 'utf8')
        const loader = LOADERS[languageOf(args.path)]
        if (!source.includes('routine')) return { contents: source, loader }

        const parsed = parseFile(args.path, source)
        if (parsed.errors.length > 0) {
          if (!ROUTINE_CALL.test(source)) return { contents: source, loader }
          const first = parsed.errors[0]
          const label = first.labels[0]
          const at = label ?? { start: 0, end: 1 }
          return { errors: [messageAt(args.path, source, `cannot parse: ${first.message}`, at.start, at.end)], loader }
        }

        const calls = findRoutineCalls(parsed.program)
        if (calls.length === 0) return { contents: source, loader }

        const errors: PartialMessage[] = []
        const patches: { start: number, end: number, text: string }[] = []
        const captured: { call: RoutineCall, names: readonly string[] }[] = []
        for (const call of calls) {
          const shape = checkCallShape(call, args.path, source)
          if (shape.error !== undefined) {
            errors.push(shape.error)
            continue
          }
          try {
            const program = compileRoutine(shape.body, source, { mode: call.mode, file: args.path })
            captured.push({ call, names: program.captures })
            const text = emitRoutineCall(call.callee, program, indentOf(source, call.start))
            patches.push({ start: call.start, end: call.end, text })
          } catch (error) {
            if (!(error instanceof RoutineCompileError)) throw error
            errors.push(messageAt(args.path, source, error.message, error.start, error.end))
          }
        }

        for (const [start, problems] of checkCaptures(parsed.program, captured)) {
          const call = calls.find(it => it.start === start)!
          for (const problem of problems) {
            const message = `${call.callee}: ${problem.message}`
            errors.push(messageAt(args.path, source, message, problem.start, problem.end))
          }
        }
        if (errors.length > 0) return { errors, loader }

        patches.sort((left, right) => right.start - left.start)
        let contents = source
        for (const patch of patches) {
          contents = contents.slice(0, patch.start) + patch.text + contents.slice(patch.end)
        }
        return { contents, loader }
      })
    },
  }
}
