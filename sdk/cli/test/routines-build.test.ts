import fs from 'node:fs/promises'
import os from 'node:os'
import { join } from 'node:path'
import { build } from 'esbuild'
import { expect, it } from 'vitest'
import { verifyFile } from '../src/commands/verify.js'
import { compileRoutine, isRoutineFunction } from '../src/routines/compile.js'
import { emitRoutineCall } from '../src/routines/emit.js'
import { findRoutineCalls, parseFile } from '../src/routines/find.js'
import { compileRoutines } from '../src/routines/plugin.js'

it.each([false, true])('verifies bundled captures after renaming/inlining (minifyIdentifiers=%s)', async (minifyIdentifiers) => {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'inu-routines-'))
  try {
    await fs.writeFile(join(root, 'a.ts'), 'const VALUE = 1; export const first = inu.jvm.routine(() => VALUE)')
    await fs.writeFile(join(root, 'b.ts'), 'const VALUE = 2; export const second = inu.jvm.routine(() => VALUE)')
    await fs.writeFile(join(root, 'entry.ts'), `
      import { first } from './a.js'
      import { second } from './b.js'
      globalThis.routines = [first, second, inu.jvm.routine(function () {
        return \`a
    b
 \n\tc\`
      })]
    `)
    const result = await build({
      entryPoints: [join(root, 'entry.ts')],
      bundle: true,
      format: 'iife',
      target: 'es2022',
      write: false,
      minifyIdentifiers,
      plugins: [compileRoutines()],
    })
    const source = result.outputFiles[0].text
    const verdicts = verifyFile('built.js', source)
    expect(verdicts).toHaveLength(3)
    expect(verdicts.map(it => it.problem)).toEqual([null, null, null])
    expect(verifyFile('built.js', source.replace('["capture", 0]', '["capture", 1]')).some(it => it.problem !== null)).toBe(true)
  } finally {
    await fs.rm(root, { recursive: true, force: true })
  }
})

it('checks capture count, including holes and spreads, without trusting binding names', () => {
  const source = 'inu.jvm.routine(() => CAPTURE)'
  const call = findRoutineCalls(parseFile('routine.ts', source).program)[0]
  if (!isRoutineFunction(call.body)) throw new Error('expected a routine')
  const program = compileRoutine(call.body, source, { mode: 'method', file: 'routine.ts' })
  const emitted = emitRoutineCall('inu.jvm.routine', program)
  for (const passed of ['[renamed]', '[42]', '[object.member]']) {
    expect(verifyFile('built.js', emitted.replace(', [CAPTURE])', `, ${passed})`))[0].problem).toBeNull()
  }
  for (const passed of ['[]', '[a, b]', '[,]', '[...captures]', 'captures']) {
    expect(verifyFile('built.js', emitted.replace(', [CAPTURE])', `, ${passed})`))[0].problem).not.toBeNull()
  }
  expect(verifyFile('built.js', emitted.replace(', [CAPTURE])', ', [CAPTURE], extra)'))[0].problem).not.toBeNull()
  expect(verifyFile('built.js', 'inu.jvm.routine(dynamicProgram)')[0].problem).not.toBeNull()
})
