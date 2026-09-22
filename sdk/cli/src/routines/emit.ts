import type { Instruction, RoutineProgram } from './ops.js'

const IDENTIFIER = /^[a-z_$][\w$]*$/i

function emitKey(key: string): string {
  return IDENTIFIER.test(key) ? key : JSON.stringify(key)
}

function escapeTemplate(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${').replace(/\r/g, '\\r')
}

/**
 * Records the original body in a readable template literal. Escape backslashes, backticks,
 * and `${` so the cooked value matches the source.
 *
 * Add a uniform margin to every line, including the first and blank lines. Verification
 * removes only that margin; trimming original indentation would change multiline literals.
 */
function emitSource(source: string, indent: string): string {
  if (!source.includes('\n')) return `\`${escapeTemplate(source)}\``
  return `\`\n${escapeTemplate(source.split('\n').map(line => indent + line).join('\n'))}\``
}

export function dedentRoutineSource(source: string): string {
  if (!source.startsWith('\n')) return source
  const lines = source.slice(1).split('\n')
  const margin = lines[0].match(/^[\t ]*/)?.[0] ?? ''
  if (!lines.every(line => line.startsWith(margin))) throw new Error('recorded source has an inconsistent margin')
  return lines.map(line => line.slice(margin.length)).join('\n')
}

function emitInstruction(node: Instruction): string {
  return `[${node.map(field => JSON.stringify(field)).join(', ')}]`
}

export function emitProgram(program: RoutineProgram, indent = ''): string {
  const code = program.code.length === 0
    ? '[]'
    : `[\n${program.code.map(node => `    ${emitInstruction(node)},`).join('\n')}\n  ]`
  return [
    '{',
    `  ${emitKey('v')}: ${program.v},`,
    `  ${emitKey('source')}: ${emitSource(program.source, `${indent}  `)},`,
    `  ${emitKey('captures')}: ${JSON.stringify(program.captures)},`,
    `  ${emitKey('slots')}: ${program.slots},`,
    `  ${emitKey('code')}: ${code},`,
    `  ${emitKey('tries')}: ${JSON.stringify(program.tries)},`,
    '}',
  ].join('\n')
}

/** `indent` is the call line's leading whitespace, used to align the recorded body with the output. */
export function emitRoutineCall(callee: string, program: RoutineProgram, indent = ''): string {
  const captures = program.captures.length === 0 ? '' : `, [${program.captures.join(', ')}]`
  return `${callee}(${emitProgram(program, indent)}${captures})`
}
