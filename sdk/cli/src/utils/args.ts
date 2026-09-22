import type { ArgsDef, CommandDef } from 'citty'
import { defineCommand as defineCittyCommand } from 'citty'
import { RELEASE_APP_ID } from './device.js'
import { CliError, fail } from './log.js'

export const configArgs = {
  config: {
    type: 'string',
    alias: 'c',
    description: 'config to use (default ./inu.config.ts)',
  },
} satisfies ArgsDef

export const deviceArgs = {
  serial: {
    type: 'string',
    alias: 's',
    description: 'adb device',
  },
  app: {
    type: 'string',
    default: RELEASE_APP_ID,
    description: 'inugram package id',
  },
} satisfies ArgsDef

/**
 * Wraps citty's [defineCommand] to print [CliError] messages without stack traces.
 * Citty plugins only provide `setup` and `cleanup`; `runMain` prints a stack trace for all errors.
 */
export function defineCommand<const T extends ArgsDef>(def: CommandDef<T>): CommandDef<T> {
  const run = def.run
  if (run === undefined) return defineCittyCommand(def)
  return defineCittyCommand<T>({
    ...def,
    run: async (ctx) => {
      try {
        await run(ctx)
      } catch (error) {
        if (!(error instanceof CliError)) throw error
        fail(error.message)
        process.exitCode = 1
      }
    },
  })
}
