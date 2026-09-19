import { Deferred } from '@fuman/utils'

/** resolves when the process is asked to stop, so a watch can tear its contexts down first */
export function untilInterrupted(): Promise<void> {
  const stopped = new Deferred()
  process.once('SIGINT', () => stopped.resolve())
  process.once('SIGTERM', () => stopped.resolve())
  return stopped.promise
}
