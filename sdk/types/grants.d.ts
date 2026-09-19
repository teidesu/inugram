// the shape of `grants.json`, the hand-written grant catalogue shipped with these typings.
// `pnpm run generate-grants` renders it into the app's `GrantCatalog.kt` and the cli reads it as it
// is, so both refuse exactly the same manifest.

export type ScopeKind = 'none' | 'list' | 'domain' | 'fsSize' | 'rpcMethod' | 'updateType'
export type GrantTier = 'neutral' | 'caution' | 'dangerous'

export interface CatalogGrant {
  name: string
  scopes: ScopeKind
  /** the closed vocabulary of a `list` grant */
  values?: string[]
  /** scope names a grant accepts on top of the TL vocabulary it draws from */
  extraValues?: string[]
  /** `invokeRpc`/`interceptRpc`: a takeover method is refused as a scope */
  refusesTakeover?: boolean
  tier: GrantTier
  /** how the grant reads when it names no scope at all, when that is worse than [tier] */
  tierWhenUnscoped?: GrantTier
  /** android string resource naming the grant in the permission sheet */
  title: string
  /** android string resource spelling out what the grant allows */
  info?: string
  /** android drawable shown beside it */
  icon: string
  /** english, for the cli alone: the app shows [title] and [info] instead */
  description: string
}

export interface Catalog {
  pluginApi: number
  patterns: { domain: string, fsSize: string }
  takeoverMethods: { prefixes: string[], names: string[] }
  undeliverableUpdates: { names: string[] }
  grants: CatalogGrant[]
}
