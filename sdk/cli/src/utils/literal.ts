import type { Expression, SpreadElement } from '@oxc-project/types'
import { CliError } from './log.js'

export class NotALiteralError extends CliError {
  constructor(readonly start: number | null) {
    super('not a literal')
  }
}

function refuse(node: { start: number } | null): never {
  throw new NotALiteralError(node?.start ?? null)
}

/** Reads a JSON-like literal without evaluating anything, so the source never has to run. */
export function readLiteral(node: Expression | SpreadElement | null): unknown {
  if (node === null) refuse(null)
  switch (node.type) {
    case 'ParenthesizedExpression':
    case 'TSSatisfiesExpression':
    case 'TSAsExpression':
      return readLiteral(node.expression)
    case 'Literal': {
      const value = node.value
      if (value === null) return null
      // a bigint crosses as `['L', '123']` and a regex is refused, so neither is ever emitted
      if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') return value
      return refuse(node)
    }
    case 'TemplateLiteral': {
      if (node.expressions.length > 0) refuse(node)
      return node.quasis[0].value.cooked ?? ''
    }
    case 'ArrayExpression':
      return node.elements.map(element => readLiteral(element))
    case 'ObjectExpression': {
      const object: Record<string, unknown> = {}
      for (const property of node.properties) {
        if (property.type !== 'Property' || property.computed) refuse(property)
        const key = property.key
        const name = key.type === 'Identifier' ? key.name : key.type === 'Literal' ? key.value : null
        if (typeof name !== 'string') refuse(key)
        object[name] = readLiteral(property.value)
      }
      return object
    }
    case 'UnaryExpression': {
      if (node.operator !== '-') refuse(node)
      const value = readLiteral(node.argument)
      if (typeof value !== 'number') refuse(node)
      return -value
    }
    default:
      refuse(node)
  }
}
