import { fileURLToPath } from 'node:url'

/** Replaced by Vite during builds; unset when running from source. */
declare const __INU_VERSION__: string | undefined

/** The published package version. */
export const version: string = typeof __INU_VERSION__ === 'string' ? __INU_VERSION__ : 'dev'

/**
 * `templates/` is beside this module in both `src/` and the published build.
 * `vite.config.ts` checks that the bundle containing this module stays at the build root.
 */
export const templatesDir: string = fileURLToPath(new URL('./templates', import.meta.url))
