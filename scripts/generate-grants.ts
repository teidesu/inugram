import type { Catalog, GrantTier, ScopeKind } from '@inugram/plugin-types/grants'
import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { glob } from 'tinyglobby'
import { rootDir, worktreeDir } from './config.js'
import { step, success, warn } from './lib.js'

// `sdk/types/grants.json` is the hand-written grant catalogue, published to plugin authors as part
// of the types package and read as it is by the cli. this renders it into InuCore's `GrantCatalog`,
// so the app and the cli refuse exactly the same manifest. its shape is `sdk/types/grants.d.ts`.

const catalogFile = join(rootDir, 'sdk/types/grants.json')
const outFile = join(rootDir, 'src/core/src/main/kotlin/desu/inugram/core/plugins/GrantCatalog.kt')
const stringsFile = join(rootDir, 'src/res/values/strings_inu.xml')
const contractFile = join(rootDir, 'sdk/types/common.d.ts')

const LIST_OPEN = 'plus `account.`\\{'
const LIST_CLOSE = '\\}'

const SCOPE_KINDS: ScopeKind[] = ['none', 'list', 'domain', 'fsSize', 'rpcMethod', 'updateType']
const TIERS: GrantTier[] = ['neutral', 'caution', 'dangerous']

export function validateCatalog(catalog: Catalog, where = catalogFile) {
  if (!Number.isInteger(catalog.pluginApi)) throw new Error(`${where}: pluginApi must be an integer`)
  if (catalog.grants.length === 0) throw new Error(`${where}: no grants`)
  const seen = new Set<string>()
  for (const grant of catalog.grants) {
    if (seen.has(grant.name)) throw new Error(`${where}: '${grant.name}' is declared twice`)
    seen.add(grant.name)
    if (!SCOPE_KINDS.includes(grant.scopes)) throw new Error(`${where}: '${grant.name}' has unknown scope kind '${grant.scopes}'`)
    if (!TIERS.includes(grant.tier)) throw new Error(`${where}: '${grant.name}' has unknown tier '${grant.tier}'`)
    if (grant.tierWhenUnscoped && !TIERS.includes(grant.tierWhenUnscoped)) {
      throw new Error(`${where}: '${grant.name}' has unknown tierWhenUnscoped '${grant.tierWhenUnscoped}'`)
    }
    if ((grant.scopes === 'list') !== (grant.values !== undefined)) {
      throw new Error(`${where}: '${grant.name}' must list its values if and only if it is a 'list' grant`)
    }
    if (grant.scopes === 'none' && (grant.tierWhenUnscoped || grant.extraValues)) {
      throw new Error(`${where}: '${grant.name}' takes no scopes, so it has no unscoped form`)
    }
    if (grant.description.trim() === '') throw new Error(`${where}: '${grant.name}' has no description`)
  }
}

/**
 * the `account.`\{…\} run in the contract's "takeover rpc methods are refused" bullet, which is the
 * normative statement of that set. Kotlin keeps the list private, so this is where the two are
 * held together: dropping a name from the catalogue widens the api, and adding one to the doc
 * alone promises a refusal that never happens.
 */
async function checkTakeoverContract(catalog: Catalog) {
  const contract = await fs.readFile(contractFile, 'utf8')
  const open = contract.indexOf(LIST_OPEN)
  if (open < 0) throw new Error(`${contractFile}: the takeover bullet no longer opens with '${LIST_OPEN}'`)
  const close = contract.indexOf(LIST_CLOSE, open)
  if (close < 0) throw new Error(`${contractFile}: the takeover bullet is not closed with '${LIST_CLOSE}'`)
  const documented = contract
    .slice(open + LIST_OPEN.length, close)
    .split('`')
    .filter((_, i) => i % 2 === 1)
    .map(name => `account.${name}`)

  const listed = catalog.takeoverMethods.names
  const missing = documented.filter(name => !listed.includes(name))
  const extra = listed.filter(name => !documented.includes(name))
  if (missing.length > 0 || extra.length > 0) {
    throw new Error(
      `${catalogFile}: takeoverMethods.names disagrees with ${contractFile}`
      + `${missing.length ? `\n  only in the contract: ${missing.join(', ')}` : ''}`
      + `${extra.length ? `\n  only in the catalogue: ${extra.join(', ')}` : ''}`,
    )
  }
}

/**
 * every `title`/`info`/`icon` the catalogue names is an android resource the permission sheet
 * resolves by id, so a typo here is a build failure a long way from its cause. the drawables live
 * partly in stock, so they are only checked once a worktree exists.
 */
async function checkResources(catalog: Catalog) {
  const strings = await fs.readFile(stringsFile, 'utf8')
  const declared = new Set([...strings.matchAll(/<string name="([^"]+)"/g)].map(m => m[1]))
  for (const grant of catalog.grants) {
    for (const key of [grant.title, grant.info]) {
      if (key && !declared.has(key)) throw new Error(`${catalogFile}: '${grant.name}' names R.string.${key}, which ${stringsFile} does not declare`)
    }
  }

  if (!existsSync(worktreeDir)) {
    warn('no worktree yet, so the catalogue\'s @icon drawables went unchecked')
    return
  }
  const patterns = [
    join(rootDir, 'src/res/drawable*/*.xml'),
    join(worktreeDir, 'TMessagesProj/src/main/res/drawable*/*.{xml,png,webp}'),
  ]
  const drawables = new Set((await glob(patterns)).map(file => file.replace(/^.*\//, '').replace(/\.[^.]+$/, '')))
  for (const grant of catalog.grants) {
    if (!drawables.has(grant.icon)) {
      throw new Error(`${catalogFile}: '${grant.name}' names R.drawable.${grant.icon}, which no resource directory declares`)
    }
  }
}

/** a kotlin string literal. the catalogue holds regexes, so the escaping has to be real */
function kt(value: string): string {
  const escaped = value
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\$/g, '\\$')
    .replace(/\n/g, '\\n')
  return `"${escaped}"`
}

function ktSet(values: string[], indent = ''): string {
  if (values.length === 0) return 'emptySet()'
  const inline = `setOf(${values.map(kt).join(', ')})`
  if (inline.length + indent.length <= 110) return inline
  return `setOf(\n${values.map(value => `${indent}    ${kt(value)},`).join('\n')}\n${indent})`
}

function ktTier(tier: GrantTier | undefined): string {
  return tier ? `GrantTier.${tier.toUpperCase()}` : 'null'
}

function render(catalog: Catalog): string {
  const entries = catalog.grants.map((grant) => {
    const fields = [
      `name = ${kt(grant.name)}`,
      `scopes = ScopeKind.${grant.scopes.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase()}`,
      `values = ${ktSet(grant.values ?? [], '            ')}`,
      `extraValues = ${ktSet(grant.extraValues ?? [], '            ')}`,
      `refusesTakeover = ${grant.refusesTakeover === true}`,
      `tier = ${ktTier(grant.tier)}`,
      `tierWhenUnscoped = ${ktTier(grant.tierWhenUnscoped)}`,
    ]
    return `        Entry(\n${fields.map(f => `            ${f},`).join('\n')}\n        ),`
  })

  return `package desu.inugram.core.plugins

// GENERATED by \`pnpm run generate-grants\` from sdk/types/grants.json.
// do not edit by hand - \`pnpm run setup\` regenerates it.

/** what a grant's scopes are drawn from, and so what validates one */
enum class ScopeKind {
    NONE,
    LIST,
    DOMAIN,
    FS_SIZE,
    RPC_METHOD,
    UPDATE_TYPE,
}

/** ordered by severity: a permission list takes the max */
enum class GrantTier {
    NEUTRAL,
    CAUTION,
    DANGEROUS,
}

/**
 * Every grant a manifest may ask for, and what it accepts. The catalogue is published to plugin
 * authors as \`@inugram/plugin-types/grants.json\`, so \`inu check\` refuses what the app refuses.
 *
 * What is deliberately *not* here: the android resources naming each grant in the permission
 * sheet. Those are compile-time ints, so \`PluginInfoActivity\` keeps its own table, pinned against
 * this one by \`GrantCatalogTest\`.
 */
object GrantCatalog {
    /** the \`@plugin-api\` level this app implements */
    const val PLUGIN_API = ${catalog.pluginApi}

    class Entry(
        val name: String,
        val scopes: ScopeKind,
        /** the closed vocabulary of a [ScopeKind.LIST] grant, empty for every other kind */
        val values: Set<String>,
        /** scope names accepted on top of the TL vocabulary the grant draws from */
        val extraValues: Set<String>,
        /** a takeover method is refused as a scope of this grant */
        val refusesTakeover: Boolean,
        val tier: GrantTier,
        /** how the grant reads when it names no scope at all, when that is worse than [tier] */
        val tierWhenUnscoped: GrantTier?,
    )

    private val ENTRIES: List<Entry> = listOf(
${entries.join('\n')}
    )

    private val BY_NAME: Map<String, Entry> = ENTRIES.associateBy { it.name }

    private val DOMAIN = Regex(${kt(catalog.patterns.domain)})
    private val FS_SIZE = Regex(${kt(catalog.patterns.fsSize)}, RegexOption.IGNORE_CASE)

    private val TAKEOVER_METHODS: Set<String> = ${ktSet(catalog.takeoverMethods.names, '    ')}

    /** never delivered without \`unsafe.disableApiFiltering\`, so a grant naming one narrows to nothing */
    val UNDELIVERABLE_UPDATES: Set<String> = ${ktSet(catalog.undeliverableUpdates.names, '    ')}

    val names: Set<String> get() = BY_NAME.keys

    fun entryOf(name: String): Entry? = BY_NAME[name]

    fun isDomain(scope: String): Boolean = DOMAIN.matches(scope)

    fun fsSizeMatch(scope: String): MatchResult? = FS_SIZE.matchEntire(scope.trim())

    /**
     * refused in \`invokeRpc\`/\`interceptRpc\` under every grant but \`unsafe.disableApiFiltering\`,
     * because holding one is a complete account takeover
     */
    fun isTakeoverMethod(name: String): Boolean =
        ${catalog.takeoverMethods.prefixes.map(prefix => `name.startsWith(${kt(prefix)}) || `).join('')}name in TAKEOVER_METHODS

    fun tierOf(name: String, scopes: List<String>?): GrantTier {
        val entry = BY_NAME[name] ?: return GrantTier.NEUTRAL
        val unscoped = entry.tierWhenUnscoped
        if (scopes == null && unscoped != null && unscoped > entry.tier) return unscoped
        return entry.tier
    }
}
`
}

export async function generateGrants(): Promise<boolean> {
  const catalog = JSON.parse(await fs.readFile(catalogFile, 'utf8')) as Catalog
  validateCatalog(catalog)
  await checkTakeoverContract(catalog)
  await checkResources(catalog)
  const body = render(catalog)
  if (await fs.readFile(outFile, 'utf8').catch(() => null) === body) return false
  step(`Generating ${outFile}`)
  await fs.writeFile(outFile, body)
  success('grant catalogue generated')
  return true
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await generateGrants()
}
