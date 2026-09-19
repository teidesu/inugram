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
 * citty's own [defineCommand], plus the error reporting every command here wants. There is no hook
 * for this in citty: a plugin only gets `setup` and `cleanup`, and `runMain` prints whatever
 * reaches it with a stack trace, while a [CliError] is a message for the user rather than a bug.
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
