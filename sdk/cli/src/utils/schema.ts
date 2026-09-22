import * as v from 'valibot'

/** A validation issue with its source path, printed under a `... is not valid:` heading. */
export function describeIssue(issue: v.BaseIssue<unknown>, prefix?: string): string {
  const path = [prefix, v.getDotPath(issue)].filter(Boolean).join('.')
  return `  ${path || '<root>'}: ${issue.message}`
}

export const oneLineString = v.pipe(
  v.string(),
  v.regex(/^[^\r\n]*$/, 'Must not contain line breaks'),
)

// eslint-disable-next-line ts/no-unsafe-function-type
export const customFn = <T extends Function>() => v.custom<T>(it => typeof it === 'function')
