import { fileURLToPath } from 'node:url'

/** substituted by the vite build; running from the sources there is nothing to substitute */
declare const __INU_VERSION__: string | undefined

/** what the published package says it is */
export const version: string = typeof __INU_VERSION__ === 'string' ? __INU_VERSION__ : 'dev'

/**
 * `templates/` sits beside this module in both layouts: `src/` in the repo, and the build root once
 * published, where the bundle carrying this module lands. `vite.config.ts` fails the build if that
 * stops being true.
 */
export const templatesDir: string = fileURLToPath(new URL('./templates', import.meta.url))
