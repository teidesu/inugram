import type { Instruction, RoutineProgram } from './ops.js'

const IDENTIFIER = /^[a-z_$][\w$]*$/i

function emitKey(key: string): string {
  return IDENTIFIER.test(key) ? key : JSON.stringify(key)
}

function escapeTemplate(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${').replace(/\r/g, '\\r')
}

/**
 * The body as it was written, kept readable: a template literal holds its newlines instead of
 * spelling them, so the half of a published plugin a reader can check is the half they can read.
 * A backslash, a backtick and a `${` are escaped, so the cooked text is the body again.
 *
 * Its lines get a uniform margin, including the first and blank lines. Verification removes only
 * that margin: trimming the original indentation would change multiline literals in the body.
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

/**
 * [indent] is the leading whitespace of the line the call sits on, which is where the bundler will
 * put the object back, and so where the recorded body has to be written to line up with it.
 */
export function emitRoutineCall(callee: string, program: RoutineProgram, indent = ''): string {
  const captures = program.captures.length === 0 ? '' : `, [${program.captures.join(', ')}]`
  return `${callee}(${emitProgram(program, indent)}${captures})`
}
