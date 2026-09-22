import type { CatalogGrant, Vocabulary } from './catalog.js'
import * as v from 'valibot'
import { oneLineString } from './schema.js'

export const ManifestSchema = v.object({
  /** shown everywhere the plugin is named; the only required field */
  name: v.pipe(oneLineString, v.minLength(1)),
  /**
   * what decides whether a later file is an *update* of an installed plugin or a plugin of its own,
   * compared verbatim. Canonically a reverse domain name, `com.github.you.my-plugin`.
   *
   * Left out, one is derived from [author] and [name], which ties the plugin's identity to both:
   * rename either half and the next build installs beside the old plugin instead of over it.
   */
  id: v.optional(v.pipe(
    v.string(),
    // the app compares it verbatim, so it may hold nothing invisible: `PluginManifest.readDeclaredId`
    // eslint-disable-next-line no-control-regex
    v.regex(/^[^\s\u0000-\u001F\u007F-\u009F]+$/, 'Must be a single token with nothing invisible in it'),
  )),
  /** shown next to the name, and what an absent [id] is derived from together with it */
  author: v.optional(oneLineString),
  version: v.optional(oneLineString),
  /** one string, or a language map whose `en` (or first entry) is the untagged description */
  description: v.optional(v.union([
    oneLineString,
    v.record(oneLineString, oneLineString),
  ])),
  /**
   * `inu://{name}` for one of the app's own glyphs, `tg://emoji?id={id}` for a custom emoji, or
   * `tg://addstickers?set={slug}` for a sticker. Anything else draws the placeholder.
   */
  icon: v.optional(v.custom<string>((it) => {
    if (typeof it !== 'string') return false
    if (/[\r\n]/.test(it)) return false
    if (it.startsWith('inu://')) return true
    return it.startsWith('tg://addstickers?') || it.startsWith('tg://emoji?')
  }, 'Must be inu://{name}, tg://emoji?id={id} or tg://addstickers?set={slug}')),
  /** `@grant` tokens, e.g. `account.read(peers)`. `inu check` validates them */
  grants: v.optional(v.array(oneLineString)),
  /** defaults to the api level of the installed `@inugram/plugin-types` */
  pluginApi: v.optional(v.number()),
  platform: v.optional(oneLineString),
  /** any further directives, verbatim; `inu.info().header` hands them to the plugin */
  extra: v.optional(v.record(
    oneLineString,
    v.union([oneLineString, v.array(oneLineString)]),
  )),
})
export type Manifest = v.InferInput<typeof ManifestSchema>

const BLOCK_OPEN = '// ==InuPlugin=='
const BLOCK_CLOSE = '// ==/InuPlugin=='
const VALUE_COLUMN = 14

function directive(key: string, value: string): string {
  const padded = key.length + 1 >= VALUE_COLUMN ? `@${key} ` : `@${key}`.padEnd(VALUE_COLUMN)
  return `// ${padded}${value}`.trimEnd()
}

/**
 * one lowercase run of letters and digits per word, joined by dashes. `PluginManifest.slug` derives
 * the id of a plugin that declares none the same way, so a plugin built before the cli started
 * writing `@id` keeps matching the plugin built after.
 */
export function slugify(value: string): string {
  return value.toLowerCase().replace(/[^\p{L}\p{Nd}]+/gu, '-').replace(/^-|-$/g, '')
}

/** what the app would derive for a manifest that declares no [Manifest.id], and null where it would too */
export function resolveManifestId(manifest: Manifest): string | null {
  if (manifest.id !== undefined) return manifest.id
  if (manifest.author === undefined) return null
  const author = slugify(manifest.author)
  const name = slugify(manifest.name)
  if (author === '' || name === '') return null
  return `${author}.${name}`
}

export function renderManifestHeader(manifest: Manifest, defaultPluginApi: number): string {
  const {
    name,
    author,
    version,
    description,
    icon,
    grants = [],
    pluginApi = defaultPluginApi,
    platform = 'android',
    extra = {},
  } = manifest
  const lines = [
    BLOCK_OPEN,
    directive('name', name),
  ]
  const id = resolveManifestId(manifest)
  if (id !== null) lines.push(directive('id', id))
  if (author) lines.push(directive('author', author))
  if (version) lines.push(directive('version', version))

  if (description) {
    if (typeof description === 'string') {
      lines.push(directive('description', description))
    } else {
      const entries = Object.entries(description)
      const untagged = entries.find(([lang]) => lang === 'en') ?? entries[0]
      if (untagged !== undefined) lines.push(directive('description', untagged[1]))
      for (const [lang, text] of entries) {
        if (lang !== untagged?.[0]) lines.push(directive(`description:${lang}`, text))
      }
    }
  }

  if (icon) lines.push(directive('icon', icon))
  for (const grant of grants) lines.push(directive('grant', grant))

  lines.push(directive('plugin-api', String(pluginApi)))
  lines.push(directive('platform', platform))

  for (const [key, value] of Object.entries(extra)) {
    for (const one of Array.isArray(value) ? value : [value]) lines.push(directive(key, one))
  }

  lines.push(BLOCK_CLOSE)
  return `${lines.join('\n')}\n`
}

export interface Grant {
  name: string
  scopes: string[]
}

/** mirrors `PluginPermissions.parseGrant`: anything it answers null for is not a grant at all */
export function parseGrant(token: string): Grant | null {
  const t = token.trim()
  if (t === '') return null
  const open = t.indexOf('(')
  if (open < 0) return { name: t, scopes: [] }
  const name = t.slice(0, open).trim()
  if (name === '') return null
  if (!t.endsWith(')')) return null
  const inner = t.slice(open + 1, t.length - 1)
  if (inner.split(',').every(part => part.trim() === '')) return null
  return { name, scopes: inner.split(',').map(s => s.trim()).filter(s => s !== '') }
}

/** mirrors `PluginPermissions.isMalformed`: a bad scope must not read as an unscoped grant */
export function isMalformed(token: string): boolean {
  const t = token.trim()
  if (t === '') return false
  const open = t.indexOf('(')
  if (open < 0) return false
  if (t.slice(0, open).trim() === '') return true
  if (!t.endsWith(')')) return true
  return t.slice(open + 1, t.length - 1).split(',').every(part => part.trim() === '')
}

function isTakeoverMethod(name: string, vocabulary: Vocabulary): boolean {
  const { prefixes, names } = vocabulary.catalog.takeoverMethods
  return prefixes.some(prefix => name.startsWith(prefix)) || names.includes(name)
}

function validateScope(
  entry: CatalogGrant,
  scope: string,
  bypassesFilter: boolean,
  vocabulary: Vocabulary,
): string | null {
  const name = entry.name
  switch (entry.scopes) {
    case 'none':
      return null
    case 'list':
      return entry.values?.includes(scope) ? null : `unknown ${name} scope '${scope}'`
    case 'domain':
      return vocabulary.domain.test(scope) ? null : `'${scope}' is not a domain in @grant ${name}`
    case 'fsSize':
      return vocabulary.fsSize.test(scope.trim()) ? null : `invalid ${name} scope '${scope}' (expected e.g. '200mb')`
    case 'rpcMethod':
      if (!vocabulary.rpcMethods.has(scope)) return `unknown rpc method '${scope}' in @grant ${name}`
      if (entry.refusesTakeover && isTakeoverMethod(scope, vocabulary) && !bypassesFilter) {
        return `'${scope}' is a takeover method and cannot be granted`
      }
      return null
    case 'updateType':
      if (vocabulary.catalog.undeliverableUpdates.names.includes(scope) && !bypassesFilter) {
        return `'${scope}' is never delivered to plugins and cannot be granted`
      }
      if (vocabulary.updateTypes.has(scope) || entry.extraValues?.includes(scope)) return null
      return `unknown update type '${scope}' in @grant ${name}`
  }
}

/** mirrors `GrantValidator.validateGrants`: these are the problems that refuse an install */
export function validateGrants(tokens: string[], vocabulary: Vocabulary): string[] {
  const problems: string[] = []
  const bypassesFilter = tokens.some(token => parseGrant(token)?.name === 'unsafe.disableApiFiltering')

  for (const token of tokens) {
    if (isMalformed(token)) {
      problems.push(`malformed grant '${token.trim()}'`)
      continue
    }
    const grant = parseGrant(token)
    if (!grant) continue
    const entry = vocabulary.byName.get(grant.name)
    if (!entry) continue
    if (entry.scopes === 'none') {
      if (grant.scopes.length > 0) problems.push(`grant '${grant.name}' takes no scopes`)
      continue
    }
    for (const scope of grant.scopes) {
      const problem = validateScope(entry, scope, bypassesFilter, vocabulary)
      if (problem !== null) problems.push(problem)
    }
  }
  return problems
}

/**
 * [ManifestSchema] judged the way the app would judge it: every grant against the catalogue the
 * project installed, and the api level against what that catalogue offers.
 */
export function createManifestSchema(vocabulary: Vocabulary) {
  return v.pipe(
    ManifestSchema,
    v.rawCheck<Manifest>(({ dataset, addIssue }) => {
      if (!dataset.typed) return
      const { grants = [], pluginApi } = dataset.value

      for (const problem of validateGrants(grants, vocabulary)) addIssue({ message: problem })
      if (pluginApi !== undefined && pluginApi > vocabulary.catalog.pluginApi) {
        addIssue({ message: `@plugin-api ${pluginApi} is newer than the installed types (${vocabulary.catalog.pluginApi}); the app would refuse to run it` })
      }
    }),
  )
}

/**
 * what the app installs anyway, though the plugin will not do what the manifest says. These are not
 * part of [createManifestSchema] because valibot has no issue that does not refuse the input.
 */
export function collectManifestWarnings(manifest: Manifest, vocabulary: Vocabulary): string[] {
  const warnings: string[] = []

  if (resolveManifestId(manifest) === null) {
    warnings.push('no id and no author: a plugin with neither can never be updated in place, only installed again')
  }
  if (manifest.version === undefined) {
    warnings.push('no version: the update sheet has nothing to show the user')
  }
  // the app ignores an unknown grant name rather than refusing, so a typo here costs the plugin
  // the whole api it meant to ask for, silently, at runtime
  for (const token of manifest.grants ?? []) {
    const grant = parseGrant(token)
    if (grant && !vocabulary.byName.has(grant.name)) {
      warnings.push(`'${grant.name}' is not a grant this api level knows; it will be ignored`)
    }
  }
  return warnings
}
