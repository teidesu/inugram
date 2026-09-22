import type { PartialMessage } from 'esbuild'
import type * as TypeScript from 'typescript'
import { createRequire } from 'node:module'
import { join, relative } from 'node:path'
import { CliError, messageAt } from './log.js'

export interface TypecheckResult {
  errors: PartialMessage[]
  warnings: PartialMessage[]
}

/** The project's own compiler, so its diagnostics are the ones its editor and `tsc` show. */
function loadTypescript(root: string): typeof TypeScript {
  try {
    return createRequire(join(root, 'package.json'))('typescript') as typeof TypeScript
  } catch {
    throw new CliError(`typescript is not installed in ${root}: install it or pass --no-typecheck`)
  }
}

function toMessage(ts: typeof TypeScript, diagnostic: TypeScript.Diagnostic): PartialMessage {
  const text = `${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')} (TS${diagnostic.code})`
  const file = diagnostic.file
  if (file === undefined || diagnostic.start === undefined) return { text }
  const end = diagnostic.start + (diagnostic.length ?? 1)
  return messageAt(relative(process.cwd(), file.fileName), file.text, text, diagnostic.start, end)
}

/**
 * Typechecks the project the way `tsc --noEmit` would from [root].
 * With [files], keeps only diagnostics in those files, plus the ones that belong to no file.
 */
export function typecheckProject(root: string, files?: ReadonlySet<string>): TypecheckResult {
  const ts = loadTypescript(root)
  const configPath = ts.findConfigFile(root, ts.sys.fileExists)
  if (configPath === undefined) throw new CliError(`no tsconfig.json in ${root}: add one or pass --no-typecheck`)

  const diagnostics: TypeScript.Diagnostic[] = []
  const parsed = ts.getParsedCommandLineOfConfigFile(configPath, { noEmit: true }, {
    ...ts.sys,
    onUnRecoverableConfigFileDiagnostic: diagnostic => diagnostics.push(diagnostic),
  })
  if (parsed !== undefined) {
    const program = ts.createProgram({
      rootNames: parsed.fileNames,
      options: parsed.options,
      projectReferences: parsed.projectReferences,
      configFileParsingDiagnostics: parsed.errors,
    })
    diagnostics.push(...ts.getPreEmitDiagnostics(program))
  }

  const result: TypecheckResult = { errors: [], warnings: [] }
  for (const diagnostic of diagnostics) {
    if (files !== undefined && diagnostic.file !== undefined && !files.has(diagnostic.file.fileName)) continue
    if (diagnostic.category === ts.DiagnosticCategory.Error) result.errors.push(toMessage(ts, diagnostic))
    else if (diagnostic.category === ts.DiagnosticCategory.Warning) result.warnings.push(toMessage(ts, diagnostic))
  }
  return result
}
