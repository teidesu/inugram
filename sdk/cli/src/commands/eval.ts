import type { Message } from 'esbuild'
import type { DevEval } from '../utils/device.js'
import { readFile } from 'node:fs/promises'
import process from 'node:process'
import { text } from 'node:stream/consumers'
import * as esbuild from 'esbuild'
import { parseSync } from 'oxc-parser'
import { configArgs, defineCommand, deviceArgs } from '../utils/args.js'
import { Device } from '../utils/device.js'
import { CliError, fail, printMessages, success } from '../utils/log.js'
import { loadProject } from '../utils/project.js'
import { LogChannels } from './dev.js'

/** what the app accepts, below the 60s after which the system declares a broadcast an ANR */
const MAX_TIMEOUT_SECONDS = 50

/** like a repl, statements answer with their last expression unless they `return` */
function wrapStatements(source: string): string {
  const wrapped = `(async () => {${source}\n})()`
  const { program, errors } = parseSync('eval.ts', wrapped, { lang: 'ts', preserveParens: false })
  const call = program.body[0]
  if (errors.length > 0 || call?.type !== 'ExpressionStatement' || call.expression.type !== 'CallExpression') return wrapped
  const arrow = call.expression.callee
  if (arrow.type !== 'ArrowFunctionExpression' || arrow.body.type !== 'BlockStatement') return wrapped
  const last = arrow.body.body.at(-1)
  if (last?.type !== 'ExpressionStatement') return wrapped
  const { start, end } = last.expression
  return `${wrapped.slice(0, start)}return (${wrapped.slice(start, end)});${wrapped.slice(last.end)}`
}

async function compileSnippet(source: string): Promise<string> {
  const transform = (code: string) => esbuild.transform(code, { loader: 'ts', target: 'esnext', sourcefile: '<eval>', logLevel: 'silent' })
  try {
    return (await transform(`(async () => (${source.trimEnd().replace(/;+$/, '')}\n))()`)).code
  } catch {}
  try {
    return (await transform(wrapStatements(source))).code
  } catch (error) {
    await printMessages((error as { errors?: Message[] }).errors ?? [], 'error')
    throw new CliError('the code does not parse')
  }
}

/** null when there is neither, which only a reset accepts */
async function readSource(code: string | undefined, file: string | undefined): Promise<string | null> {
  if (code !== undefined && file !== undefined) throw new CliError('pass the code, or a file with -f, not both')
  if (file !== undefined) {
    return readFile(file, 'utf8').catch((error: Error) => {
      throw new CliError(`cannot read ${file}: ${error.message}`)
    })
  }
  if (code === undefined) return null
  return code === '-' ? text(process.stdin) : code
}

export const evalCmd = defineCommand({
  meta: { name: 'eval', description: 'run code inside a running dev plugin, or a scratch one, and print the result' },
  args: {
    ...configArgs,
    ...deviceArgs,
    name: {
      type: 'positional',
      required: false,
      description: 'the plugin to run the code in, left out with --scratch',
    },
    code: {
      type: 'positional',
      required: false,
      description: 'an expression or statements, answering with the last expression; - reads stdin',
    },
    file: {
      type: 'string',
      alias: 'f',
      description: 'read the code from a file',
    },
    scratch: {
      type: 'boolean',
      alias: 'x',
      default: false,
      description: 'run in a scratch plugin with every grant, which keeps its state between runs',
    },
    reset: {
      type: 'boolean',
      default: false,
      description: 'restart the scratch plugin and wipe its storage first; implies --scratch',
    },
    timeout: {
      type: 'string',
      default: '10',
      description: `seconds to wait for the result, at most ${MAX_TIMEOUT_SECONDS}`,
    },
    logs: {
      type: 'boolean',
      default: true,
      description: 'print what the plugin logged meanwhile',
      negativeDescription: 'do not print the plugin log',
    },
  },
  run: async ({ args }) => {
    const seconds = Number(args.timeout)
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
      throw new CliError(`--timeout must be a whole number of seconds from 1 to ${MAX_TIMEOUT_SECONDS}, got '${args.timeout}'`)
    }
    const scratch = args.scratch || args.reset
    // without a plugin name the code is the only positional
    if (scratch && args.code !== undefined) throw new CliError('--scratch takes no plugin name')
    if (!scratch && args.name === undefined) throw new CliError('pass the plugin to run the code in, or --scratch')
    const source = await readSource(scratch ? args.name : args.code, args.file)
    if (source === null && !args.reset) throw new CliError('pass the code, or a file with -f')
    const project = scratch ? null : await loadProject({ config: args.config, _: [args.name!] })
    const plugin = project?.plugins[0] ?? null
    const code = await compileSnippet(source ?? '')

    const device = new Device(args)
    const pid = await device.requireRunning()
    const logsSince = await device.getLogTime()
    const channels = new LogChannels(project)
    let result: DevEval
    try {
      result = await device.evaluate(plugin?.outFile ?? null, code, seconds, args.reset)
      channels.addPlugin(result.plugin, plugin?.slug ?? 'scratch')
    } finally {
      for (const { level, tag, message } of args.logs ? await device.dumpLogs(logsSince, pid) : []) {
        channels.print(level, tag, message)
      }
    }

    if (source === null) {
      success('scratch plugin reset')
    } else if (result.fulfilled) {
      console.log(result.text)
    } else {
      fail(result.text)
      process.exitCode = 1
    }
  },
})
