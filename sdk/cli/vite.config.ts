import type { BuildHookContext } from '@fuman/build'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { fumanBuild } from '@fuman/build/vite'
import { nodeExternals } from 'rollup-plugin-node-externals'
import { defineConfig } from 'vite'
import dts from 'vite-plugin-dts'

const workspaceRoot = join(import.meta.dirname, '../..')
const ownPackageJson = createRequire(import.meta.url)('./package.json') as { version: string }
/** the release the sdk is published as, set by `scripts/ci/publish-sdk.ts`; never written to the tree */
const version = process.env.INU_SDK_VERSION ?? ownPackageJson.version

/**
 * `meta.ts` finds `templates/` beside itself, which holds only while rollup keeps it in a root
 * chunk. Fail here rather than at someone's `inu init` if it ever moves under `chunks/`.
 */
async function assertTemplatesBesideTheBundle(ctx: BuildHookContext) {
  const chunks = await fs.readdir(join(ctx.outDir, 'chunks')).catch(() => [])
  for (const name of chunks) {
    const body = await fs.readFile(join(ctx.outDir, 'chunks', name), 'utf8')
    if (body.includes('"./templates"')) {
      throw new Error(`meta.ts was bundled into chunks/${name}, where './templates' no longer resolves`)
    }
  }
}

export default defineConfig({
  define: {
    __INU_VERSION__: JSON.stringify(version),
  },
  build: {
    target: 'node20',
    lib: { entry: {}, formats: ['es'] },
  },
  plugins: [
    nodeExternals(),
    await fumanBuild({
      root: workspaceRoot,
      packageRoot: import.meta.dirname,
      autoSideEffectsFalse: true,
      insertTypesEntry: true,
      finalizePackageJson: (ctx) => {
        ctx.packageJson.version = version
      },
      typesEntryRoot: join(import.meta.dirname, 'src'),
      // `inu init` copies these, so they ship beside the bundle rather than being bundled into it
      finalize: async (ctx) => {
        await fs.cp(join(ctx.packageDir, 'src/templates'), join(ctx.outDir, 'templates'), { recursive: true })
        await assertTemplatesBesideTheBundle(ctx)
      },
    }),
    dts({ entryRoot: 'src', exclude: ['vite.config.ts', 'src/templates/**', 'test/**'] }),
  ],
})
