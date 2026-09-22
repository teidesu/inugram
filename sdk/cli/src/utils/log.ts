import type { Message, PartialMessage } from 'esbuild'
import process from 'node:process'
import * as esbuild from 'esbuild'
import pc from 'picocolors'

export { default as color } from 'picocolors'

/** an offset range in a file, as the span esbuild squiggles under */
export function messageAt(file: string, source: string, text: string, start: number, end: number): PartialMessage {
  const before = source.slice(0, start)
  const lineStart = before.lastIndexOf('\n') + 1
  const lineBreak = source.indexOf('\n', lineStart)
  const lineEnd = lineBreak === -1 ? source.length : lineBreak
  return {
    text,
    location: {
      file,
      line: before.split('\n').length,
      column: start - lineStart,
      length: Math.max(1, Math.min(end, lineEnd) - start),
      lineText: source.slice(lineStart, lineEnd),
    },
  }
}

/**
 * esbuild's own renderer: the source line, the column, and a squiggle under the span. It is what
 * builds already print, so a problem the cli found reads the same as one the bundler found.
 */
export async function renderMessages(
  messages: (Message | PartialMessage)[],
  kind: 'error' | 'warning',
): Promise<string[]> {
  if (messages.length === 0) return []
  return esbuild.formatMessages(messages, {
    kind,
    color: pc.isColorSupported,
    terminalWidth: process.stdout.columns ?? 100,
  })
}

export function step(message: string) {
  console.log(`${pc.blue('==>')} ${message}`)
}

export function success(message: string) {
  console.log(`${pc.green('ok')} ${message}`)
}

export function warn(message: string) {
  console.log(`${pc.yellow('warn')} ${message}`)
}

export function fail(message: string) {
  console.log(`${pc.red('fail')} ${message}`)
}

/** what the cli throws for a problem the user can fix: printed without a stack trace */
export class CliError extends Error {}
