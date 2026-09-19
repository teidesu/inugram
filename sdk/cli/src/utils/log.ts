import pc from 'picocolors'

export { default as color } from 'picocolors'

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
