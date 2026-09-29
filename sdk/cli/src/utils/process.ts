import { Deferred } from '@fuman/utils'

/** Resolves on a shutdown request so watchers can clean up before exit. */
export function untilInterrupted(): Promise<void> {
  const stopped = new Deferred()
  process.once('SIGINT', () => stopped.resolve())
  process.once('SIGTERM', () => stopped.resolve())
  return stopped.promise
}
