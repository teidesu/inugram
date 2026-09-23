// the loaders prepend test-prelude.js to every test plugin, but tsc checks each .js as a module
type ErrorWant = string | (abstract new (...args: any[]) => unknown) | null

declare let ran: number
declare function pass(label: string, detail?: unknown): void
declare function fail(label: string, detail?: unknown): void
declare function skip(label: string, why: unknown): void
declare function check(label: string, ok: unknown, detail?: unknown): void
declare function equals(label: string, actual: unknown, expected: unknown): void
declare function checkError(label: string, want: ErrorWant, error: unknown, needle?: string): void
declare function expectThrow(label: string, want: ErrorWant, fn: () => unknown, needle?: string): any
declare function expectDomException(label: string, name: string, fn: () => unknown): any
declare function expectReject(
  label: string,
  want: ErrorWant,
  pending: Promise<unknown> | (() => unknown),
  needle?: string,
): Promise<any>
