import type { Argument, CallExpression, Program } from '@oxc-project/types'
import { parseSync, Visitor } from 'oxc-parser'

export interface RoutineCall {
  /** `inu.jvm.routine` or `inu.xposed.routine`, as written */
  callee: string
  mode: 'method' | 'hook'
  /** the whole call expression, which the compiled call replaces */
  start: number
  end: number
  body: Argument | undefined
  /** The first argument, before validating its type. */
  argument: Argument | undefined
  arguments: Argument[]
}

const NAMESPACES: Record<string, 'method' | 'hook'> = { jvm: 'method', xposed: 'hook' }

export function languageOf(file: string): 'ts' | 'tsx' | 'js' | 'jsx' {
  if (file.endsWith('.tsx')) return 'tsx'
  if (file.endsWith('.jsx')) return 'jsx'
  if (file.endsWith('.ts') || file.endsWith('.mts') || file.endsWith('.cts')) return 'ts'
  return 'js'
}

export function parseFile(file: string, source: string) {
  return parseSync(file, source, { lang: languageOf(file), sourceType: 'module' })
}

function namespaceOf(node: CallExpression): 'method' | 'hook' | null {
  const callee = node.callee
  if (callee.type !== 'MemberExpression' || callee.computed) return null
  if (callee.property.type !== 'Identifier' || callee.property.name !== 'routine') return null
  const owner = callee.object
  if (owner.type !== 'MemberExpression' || owner.computed) return null
  if (owner.object.type !== 'Identifier' || owner.object.name !== 'inu') return null
  if (owner.property.type !== 'Identifier') return null
  return NAMESPACES[owner.property.name] ?? null
}

export function findRoutineCalls(program: Program): RoutineCall[] {
  const found: RoutineCall[] = []
  let depth = 0

  new Visitor({
    CallExpression(node) {
      const mode = namespaceOf(node)
      if (mode === null) return
      if (depth === 0) {
        found.push({
          callee: `inu.${mode === 'hook' ? 'xposed' : 'jvm'}.routine`,
          mode,
          start: node.start,
          end: node.end,
          body: node.arguments[0],
          argument: node.arguments[0],
          arguments: node.arguments,
        })
      }
      depth++
    },
    'CallExpression:exit': (node) => {
      if (namespaceOf(node) !== null) depth--
    },
  }).visit(program)

  return found
}
