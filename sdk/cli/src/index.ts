import type { InuCliConfig } from './utils/config.js'

export type { Catalog, CatalogGrant, GrantTier, ScopeKind, Vocabulary } from './utils/catalog.js'
export type { InuCliConfig, PluginConfig, ResolvedCliConfig, ResolvedPluginConfig } from './utils/config.js'
export { collectManifestWarnings, createManifestSchema, ManifestSchema, parseGrant, validateGrants } from './utils/manifest.js'
export type { Manifest } from './utils/manifest.js'

/** typings helper for `inu.config.ts` */
export function defineConfig(config: InuCliConfig): InuCliConfig {
  return config
}
