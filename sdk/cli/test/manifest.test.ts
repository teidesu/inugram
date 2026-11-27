import fs from 'node:fs/promises'
import { join } from 'node:path'
import { expect, it } from 'vitest'
import { buildOnce } from '../src/commands/build.js'
import { loadConfig } from '../src/utils/config.js'

it('builds an entry whose inline manifest overrides the config layers, keeping the export out of the script', async () => {
  // inside the package, so the project resolves the installed `@inugram/plugin-types`
  const root = await fs.mkdtemp(join(import.meta.dirname, '../.tmp-manifest-'))
  try {
    await fs.writeFile(join(root, 'inu.config.ts'), `export default {
      manifest: { author: 'someone', version: '1.0.0', grants: ['fetch'], extra: { a: '1', b: '1' } },
      plugins: {
        dog: { entry: 'dog.ts', manifest: { version: '2.0.0' } },
        cat: 'cat.ts',
      },
    }`)
    await fs.writeFile(join(root, 'dog.ts'), `
      export const manifest = {
        name: 'Dog',
        grants: ['clipboard.write'],
        extra: { b: '2' },
      } satisfies inu.Manifest
      console.log('woof')
    `)
    await fs.writeFile(join(root, 'cat.ts'), 'const NAME = \'Cat\'\nexport const manifest = { name: NAME }')

    const config = await loadConfig(root)
    const [dog, cat] = config.plugins
    expect(dog.manifestIssues).toEqual([])
    expect(dog.manifest).toEqual({
      name: 'Dog',
      author: 'someone',
      version: '2.0.0',
      grants: ['clipboard.write'],
      extra: { a: '1', b: '2' },
    })
    expect(cat.manifestIssues).toEqual([expect.stringMatching(/^cat\.ts:2:\d+: the manifest must be a literal/)])

    const outcome = await buildOnce(config, dog)
    expect(outcome.ok).toBe(true)
    const script = await fs.readFile(dog.outFile, 'utf8')
    expect(script).toContain('// @version      2.0.0\n// @grant        clipboard.write\n')
    expect(script).not.toContain('@grant        fetch')
    expect(script).not.toMatch(/\bexport\b|Dog"/)
    expect(script).toContain('console.log("woof")')
  } finally {
    await fs.rm(root, { recursive: true, force: true })
  }
})
