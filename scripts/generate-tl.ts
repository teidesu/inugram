import type { JavaClass, JavaField, ParseWarning } from './tl-parser.js'
import fs from 'node:fs/promises'
import { basename, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { glob } from 'tinyglobby'
import { rootDir, worktreeDir } from './config.js'
import { step, success, warn } from './lib.js'
import { parseJavaFile } from './tl-parser.js'

// generates sdk/types/android.tl.d.ts and InuCore's tl_tables.txt from stock's tgnet sources.
// both are gitignored; see docs at the top of each generated file.

const tgnetDir = join(worktreeDir, 'TMessagesProj/src/main/java/org/telegram/tgnet')
const schemaDir = join(worktreeDir, 'TMessagesProj_AppTests/tlscheme')
const outFile = join(rootDir, 'sdk/types/android.tl.d.ts')
const tablesFile = join(rootDir, 'src/core/src/main/resources/tl_tables.txt')
// shipped with the types package: the cli has no worktree to read the schema out of
const tlNamesFile = join(rootDir, 'sdk/types/tl-names.txt')
// vendored from mtcute (packages/core/scripts/tl/data/int53-overrides.json), refreshed on rebase
const int53OverridesFile = join(rootDir, 'scripts/data/int53-overrides.json')

interface SchemaEntry {
  /** the real wire name, e.g. `account.contentSettings` */
  name: string
  layer: number
  isMethod: boolean
  /** param name -> true when the schema marks it `flags.N?X` (and not a bare bit flag) */
  optional: Map<string, boolean>
}

/**
 * every constructor stock ships a schema for, keyed by constructor id. newer layers win, so a
 * class that hasn't been regenerated in a while still resolves through whichever layer last
 * defined its id.
 */
async function loadSchema(): Promise<Map<number, SchemaEntry>> {
  const files = (await fs.readdir(schemaDir))
    .filter(f => /^\d+\.json$/.test(f))
    .map(f => Number.parseInt(f, 10))
    .sort((a, b) => b - a)

  const out = new Map<number, SchemaEntry>()
  for (const layer of files) {
    const json = JSON.parse(await fs.readFile(join(schemaDir, `${layer}.json`), 'utf8'))
    for (const [list, isMethod] of [[json.constructors, false], [json.methods, true]] as const) {
      for (const item of list ?? []) {
        const id = Number(item.id) | 0
        if (out.has(id)) continue
        const optional = new Map<string, boolean>()
        for (const param of item.params ?? []) {
          // `flags.N?true` is a bare bit: java models it as a primitive boolean that is always
          // present in a snapshot, so it counts as required even though TL calls it optional
          const flagged = /^flags\d*\.\d+\?/.test(param.type)
          optional.set(param.name, flagged && !param.type.endsWith('?true'))
        }
        out.set(id, { name: item.predicate ?? item.method, layer, isMethod, optional })
      }
    }
  }
  return out
}

// mirrors TlJson.EXCLUDED_FIELD_NAMES - TLObject bookkeeping, not wire data
const EXCLUDED_FIELDS = new Set(['networkType', 'disableFree'])
const LAYER_SUFFIX = /_layer\d+$/

/**
 * mirrors desu.inugram.core.plugins.TlNames.classNameToTlName.
 *
 * the wire name is whatever stock's own layer dumps call the constructor, listed in [overrides]
 * wherever that differs from what the java class name reads as. the derivation below is the
 * fallback for everything the dumps don't cover (`//custom` classes, anything predating the oldest
 * dump) - and it can only ever get the namespace approximately right: a leading `foo_` is a
 * namespace when `foo` is one for real (else `TL_user_old` reads as `user.old`), a bare-named class
 * carries no prefix to read, and the container decides nothing, since `TL_stars.transferStarGift`
 * is really `payments.*` and `TL_stories.TL_storyView` is namespaced nowhere.
 */
function classNameToTlName(container: string, className: string, namespaces: ReadonlySet<string>, overrides: ReadonlyMap<string, string>) {
  const stripped = className.replace(LAYER_SUFFIX, '')
  const override = overrides.get(`${container}.${stripped}`)
  if (override !== undefined) return override

  const body = stripped.replace(/^TL_/, '')
  const idx = body.indexOf('_')
  const prefix = idx > 0 ? body.slice(0, idx) : null
  if (prefix && namespaces.has(prefix)) return `${prefix}.${body.slice(idx + 1)}`
  return body
}

function upperFirst(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

interface TlClass extends JavaClass {
  /** wire name, for serializable classes only */
  tlName: string | null
  /** the schema entry this class's constructor id matches, if stock ships one */
  schema: SchemaEntry | null
  /** flag-gated field -> `[flag word, bit]`, for serializable classes */
  flags: Map<string, [string, number]>
  /** long fields crossing as a js number, for serializable classes */
  int53: Set<string>
  /** `[namespace, identifier]` of the emitted interface/alias */
  ref: [string | null, string]
  parent: TlClass | null
  children: TlClass[]
}

async function parseAll(warnings: ParseWarning[]) {
  const schemaFiles = [...new Set([
    join(tgnetDir, 'TLRPC.java'),
    ...await glob('tl/**/*.java', { cwd: tgnetDir, absolute: true }),
  ])]
  // the tgnet root also holds base classes those constructors extend, and a class whose superclass
  // can't be resolved fails the descends-from-TLObject check and vanishes from the typings - which
  // is how every `extends TLMethod<T>` method used to go missing. globbing the root rather than
  // naming TLMethod keeps the next base stock adds from failing the same way silently; the non-TL
  // classes in there (ConnectionsManager, NativeByteBuffer, ...) are dropped by that same check.
  // their warnings are discarded though - "unparsed public member" only means something for a file
  // that is meant to be schema
  const auxFiles = (await glob('*.java', { cwd: tgnetDir, absolute: true }))
    .filter(f => !schemaFiles.includes(f))

  const classes: JavaClass[] = []
  for (const file of schemaFiles) {
    classes.push(...parseJavaFile(await fs.readFile(file, 'utf8'), basename(file, '.java'), basename(file), warnings))
  }
  for (const file of auxFiles) {
    classes.push(...parseJavaFile(await fs.readFile(file, 'utf8'), basename(file, '.java'), basename(file), []))
  }
  return classes
}

function buildGraph(raw: JavaClass[], warnings: ParseWarning[]) {
  const byContainer = new Map<string, Map<string, TlClass>>()
  const all: TlClass[] = []

  for (const cls of raw) {
    if (LAYER_SUFFIX.test(cls.name)) continue
    const entry: TlClass = { ...cls, tlName: null, schema: null, flags: new Map(), int53: new Set(), ref: [null, ''], parent: null, children: [] }
    let bucket = byContainer.get(cls.container)
    if (!bucket) byContainer.set(cls.container, bucket = new Map())
    const existing = bucket.get(cls.name)
    if (existing) {
      warnings.push({ file: `${cls.container}.java`, line: cls.line, message: `duplicate class name ${cls.name} (also at line ${existing.line}), keeping the first` })
      continue
    }
    bucket.set(cls.name, entry)
    all.push(entry)
  }

  // a class that isn't nested in any other - i.e. one that owns its file, like `TLMethod`. the last
  // place an unqualified superclass name can live once its own container and TLRPC have missed
  const topLevel = new Map<string, TlClass>()
  for (const cls of all) {
    if (cls.path.length === 0 && !topLevel.has(cls.name)) topLevel.set(cls.name, cls)
  }

  const resolve = (container: string, name: string): TlClass | null => {
    const dot = name.lastIndexOf('.')
    if (dot >= 0) {
      const owner = name.slice(0, dot).split('.').pop()!
      return byContainer.get(owner)?.get(name.slice(dot + 1)) ?? null
    }
    return byContainer.get(container)?.get(name)
      ?? byContainer.get('TLRPC')?.get(name)
      ?? topLevel.get(name)
      ?? null
  }

  for (const cls of all) {
    if (!cls.superName || cls.superName === 'TLObject') continue
    const parent = resolve(cls.container, cls.superName)
    if (parent) {
      cls.parent = parent
      parent.children.push(cls)
    }
  }

  // a class only belongs in the typings if it descends from TLObject
  const isTlObject = (cls: TlClass): boolean => {
    let cur: TlClass | null = cls
    const seen = new Set<TlClass>()
    while (cur) {
      if (seen.has(cur)) return false
      seen.add(cur)
      if (cur.superName === 'TLObject') return true
      cur = cur.parent
    }
    return false
  }

  const kept = all.filter(isTlObject)
  const keptSet = new Set(kept)
  for (const cls of kept) cls.children = cls.children.filter(c => keptSet.has(c))
  return { kept, keptSet, resolve }
}

/**
 * flag layouts for *every* serializable class, layer variants included. the typings drop those
 * (they collapse onto the current constructor's name), but the bridge can still hand a plugin one
 * read out of local storage, and an unmanaged object there would leak its raw flag word.
 */
function buildFlagTable(raw: JavaClass[]) {
  const byName = new Map<string, JavaClass>()
  for (const cls of raw) {
    if (!byName.has(cls.name)) byName.set(cls.name, cls)
  }

  const fieldNamesOf = (cls: JavaClass) => {
    const names = new Set<string>()
    const seen = new Set<JavaClass>()
    for (let cur: JavaClass | undefined = cls; cur && !seen.has(cur); cur = cur.superName ? byName.get(cur.superName) : undefined) {
      seen.add(cur)
      for (const f of cur.fields) names.add(f.name)
    }
    return names
  }

  const out = new Map<number, Map<string, [string, number]>>()
  for (const cls of raw) {
    if (cls.isAbstract || !cls.constructorHash || !cls.serializeBody) continue
    const layout = parseFlagLayout(cls.serializeBody, fieldNamesOf(cls))
    if (layout.size > 0) out.set(parseConstructorId(cls.constructorHash), layout)
  }
  return out
}

/** an emitted union member: concrete, wire-serializable, reachable through TlJson */
function isSerializable(cls: TlClass) {
  return !cls.isAbstract && cls.constructorHash !== null
}

function isUpdateDescendant(cls: TlClass): boolean {
  for (let cur: TlClass | null = cls; cur; cur = cur.parent) {
    if (cur.container === 'TLRPC' && cur.name === 'Update') return true
  }
  return false
}

interface CtorIdRow {
  name: string
  kind: 'm' | 'u' | 'c'
  ids: number[]
}

/**
 * every constructor id that resolves to each kept serializable class's wire name, legacy/layer
 * variants included - a plugin's grant scope check has to recognize `TL_message_old7` as `message`
 * just as readily as the current constructor, and the bridge can still hand it one loaded from
 * local storage.
 *
 * computed over `raw` rather than the kept graph: `_layerNNN` classes are dropped from the typings
 * before they'd become a TlClass (buildGraph), and `_oldN` classes are kept but under their own
 * distinct tlName (assignTlNames), so neither shows up under its current-constructor's name without
 * walking the raw java inheritance chain by hand.
 */
function buildCtorIdTable(raw: JavaClass[], serializable: TlClass[]): CtorIdRow[] {
  const byContainer = new Map<string, Map<string, JavaClass>>()
  for (const cls of raw) {
    let bucket = byContainer.get(cls.container)
    if (!bucket) byContainer.set(cls.container, bucket = new Map())
    if (!bucket.has(cls.name)) bucket.set(cls.name, cls)
  }
  const topLevel = new Map<string, JavaClass>()
  for (const cls of raw) {
    if (cls.path.length === 0 && !topLevel.has(cls.name)) topLevel.set(cls.name, cls)
  }
  // mirrors buildGraph's resolve(), over the unfiltered raw class index instead of the kept graph
  const resolve = (container: string, name: string): JavaClass | null => {
    const dot = name.lastIndexOf('.')
    if (dot >= 0) {
      const owner = name.slice(0, dot).split('.').pop()!
      return byContainer.get(owner)?.get(name.slice(dot + 1)) ?? null
    }
    return byContainer.get(container)?.get(name)
      ?? byContainer.get('TLRPC')?.get(name)
      ?? topLevel.get(name)
      ?? null
  }

  const byContainerName = new Map<string, TlClass>()
  const ids = new Map<string, Set<number>>()
  for (const cls of serializable) {
    byContainerName.set(`${cls.container}\u0000${cls.name}`, cls)
    let set = ids.get(cls.tlName!)
    if (!set) ids.set(cls.tlName!, set = new Set())
    set.add(parseConstructorId(cls.constructorHash!))
  }

  for (const r of raw) {
    if (r.isAbstract || !r.constructorHash) continue
    const id = parseConstructorId(r.constructorHash)
    const seen = new Set<JavaClass>([r])
    let cur: JavaClass | null = r
    while (cur && cur.superName && cur.superName !== 'TLObject') {
      const parent = resolve(cur.container, cur.superName)
      if (!parent || seen.has(parent)) break
      seen.add(parent)
      const target = byContainerName.get(`${parent.container} ${parent.name}`)
      if (target) ids.get(target.tlName!)!.add(id)
      cur = parent
    }
  }

  return serializable
    .map(cls => ({
      name: cls.tlName!,
      kind: cls.responseType ? 'm' as const : isUpdateDescendant(cls) ? 'u' as const : 'c' as const,
      ids: [...ids.get(cls.tlName!)!].sort((a, b) => (a >>> 0) - (b >>> 0)),
    }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

/** `FLAG_7` is stock's name for `1 << 7`; older constructors write the raw mask instead */
function bitOf(token: string): number | null {
  const named = /^FLAG_(\d+)$/.exec(token)
  if (named) return Number(named[1])
  if (!/^(?:0x[0-9a-f]+|\d+)$/i.test(token)) return null
  const mask = Number(token)
  if (mask <= 0 || (mask & (mask - 1)) !== 0) return null
  return Math.log2(mask)
}

/**
 * field -> which flag word and bit gate it, read out of what the class actually serializes rather
 * than out of the schema: where stock lags or diverges from the published layer, the bytes it
 * writes are what a round-tripped object has to agree with.
 *
 * several fields legitimately share one bit (`codeSettings.token` and `.app_sandbox` are both
 * `flags.8`), so this is many-to-one by design.
 */
function parseFlagLayout(body: string, fieldNames: ReadonlySet<string>) {
  const out = new Map<string, [string, number]>()

  // bare bits, which are the boolean's own value: `flags = setFlag(flags, FLAG_3, spoiler);`
  // the assignment target has to be a real field - a few constructors build a *local* flag word
  // from scratch (`int flags = setFlag(0, FLAG_0, proofread)`), which is stock managing it already
  for (const m of body.matchAll(/(\w+)\s*=\s*setFlag\(\s*\w+\s*,\s*(\w+)\s*,\s*(\w+)\s*\)/g)) {
    const bit = bitOf(m[2])
    if (bit !== null && fieldNames.has(m[1]) && fieldNames.has(m[3])) out.set(m[3], [m[1], bit])
  }

  // the same, in the idiom stock used before setFlag: `flags = push ? (flags | 1) : (flags & ~1);`
  // (a couple of these clear with `& 1` rather than `& ~1`, which is a stock bug but doesn't change
  // which field the bit belongs to)
  for (const m of body.matchAll(/(\w+) = (\w+) \? \(?\1 \| (\w+)\)? :/g)) {
    const bit = bitOf(m[3])
    if (bit !== null && fieldNames.has(m[1]) && fieldNames.has(m[2])) out.set(m[2], [m[1], bit])
  }

  // gated writes: `if (hasFlag(flags, FLAG_0)) { photo.serializeToStream(stream); }`, and the older
  // `if ((flags & 1) > 0) { ... }`
  const re = /if\s*\(\s*(?:hasFlag\(\s*(\w+)\s*,\s*(\w+)\s*\)|\((\w+) & (\w+)\) (?:!=|>) 0)\s*\)\s*\{/g
  for (let m = re.exec(body); m; m = re.exec(body)) {
    const word = m[1] ?? m[3]
    const bit = bitOf(m[2] ?? m[4])
    if (bit === null || !fieldNames.has(word)) continue
    let depth = 1
    let i = m.index + m[0].length
    const start = i
    for (; i < body.length && depth > 0; i++) {
      if (body[i] === '{') depth++
      else if (body[i] === '}') depth--
    }
    for (const id of new Set([...body.slice(start, i - 1).matchAll(/\b\w+\b/g)].map(x => x[0]))) {
      if (fieldNames.has(id)) out.set(id, [word, bit])
    }
  }
  return out
}

function parseConstructorId(hash: string) {
  return (/^0x/i.test(hash) ? Number.parseInt(hash.slice(2), 16) : Number(hash)) | 0
}

/**
 * the wire name of every serializable class, taken from stock's own schema dumps where they cover
 * the constructor id and derived the way TlNames does where they don't (`//custom` classes, and
 * anything predating the oldest dump).
 */
function assignTlNames(kept: TlClass[], schema: Map<number, SchemaEntry>, namespaces: ReadonlySet<string>, warnings: ParseWarning[]) {
  const none = new Map<string, string>()
  const serializable = kept.filter(isSerializable)
    .sort((a, b) => `${a.container}.${a.name}`.localeCompare(`${b.container}.${b.name}`))

  // stock's java names disagree with the schema in both directions: a namespace the prefix rule
  // can't see (`TL_stars.transferStarGift` is `payments.transferStarGift`) and, less often, a member
  // spelled differently from the predicate (`TL_statsGetPollStats` is `stats.getPollStats`). the
  // dump is authoritative for both.
  //
  // except where taking it would fuse two java classes into one name: `TL_message_old7` is
  // `message` on the wire just as `TL_message` is, and a plugin has to be able to tell which one it
  // got. those keep their derived name, which is unique precisely because it embeds the java
  // spelling.
  const taken = new Set<string>()
  for (const cls of serializable) {
    const entry = schema.get(parseConstructorId(cls.constructorHash!))
    if (entry) cls.schema = entry
    if (!entry || entry.name === classNameToTlName(cls.container, cls.name, namespaces, none)) {
      taken.add(classNameToTlName(cls.container, cls.name, namespaces, none))
    }
  }

  const overrides = new Map<string, string>()
  for (const cls of serializable) {
    const derived = classNameToTlName(cls.container, cls.name, namespaces, none)
    if (!cls.schema || cls.schema.name === derived) continue
    if (taken.has(cls.schema.name)) {
      taken.add(derived)
    } else {
      taken.add(cls.schema.name)
      overrides.set(`${cls.container}.${cls.name}`, cls.schema.name)
    }
  }

  const byTlName = new Map<string, TlClass>()
  for (const cls of serializable) {
    cls.tlName = classNameToTlName(cls.container, cls.name, namespaces, overrides)

    const prev = byTlName.get(cls.tlName)
    if (prev) {
      warnings.push({
        file: `${cls.container}.java`,
        line: cls.line,
        message: `'${cls.tlName}' is claimed by both ${prev.container}.${prev.name} and ${cls.container}.${cls.name}; only one is reachable from fromJson`,
      })
    } else {
      byTlName.set(cls.tlName, cls)
    }
  }
  return overrides
}

async function loadInt53Overrides(): Promise<Map<string, Set<string>>> {
  const json = JSON.parse(await fs.readFile(int53OverridesFile, 'utf8'))
  const out = new Map<string, Set<string>>()
  for (const section of [json.class, json.method]) {
    for (const [name, fields] of Object.entries(section ?? {})) {
      if (Array.isArray(fields)) out.set(name, new Set(fields as string[]))
    }
  }
  return out
}

/** a legacy constructor is `<base>_<suffix>`, and no live TL name contains an underscore */
function baseTlName(tlName: string) {
  const at = tlName.indexOf('_')
  return at === -1 ? tlName : tlName.slice(0, at)
}

function isLongType(java: string) {
  return java === 'long' || java === 'Long' || /^(?:ArrayList|List)<Long>$/.test(java)
}

/**
 * the overrides name schema fields, so a field stock doesn't declare (or declares as something other
 * than a long) matches nothing here and is reported rather than silently dropped
 */
function assignInt53(serializable: TlClass[], overrides: Map<string, Set<string>>) {
  const matched = new Set<string>()
  for (const cls of serializable) {
    const base = baseTlName(cls.tlName!)
    const wanted = overrides.get(base)
    if (!wanted) continue
    const visible = inheritedFields(cls)
    for (const name of wanted) {
      const entry = visible.get(name)
      if (!entry || !isLongType(entry.field.type)) continue
      cls.int53.add(name)
      matched.add(`${base}.${name}`)
    }
  }
  const unmatched: string[] = []
  for (const [name, fields] of overrides) {
    for (const field of fields) {
      if (!matched.has(`${name}.${field}`)) unmatched.push(`${name}.${field}`)
    }
  }
  return unmatched
}

type LongMode = 'number' | 'string' | 'mixed'

/**
 * how a long declared on [owner] crosses: the decision is per constructor, so a declaration every
 * constructor below it inherits can be a number for some and a string for others
 */
function makeLongModeResolver() {
  const cache = new Map<TlClass, Map<string, LongMode>>()
  return (owner: TlClass, field: JavaField): LongMode => {
    if (!isLongType(field.type)) return 'string'
    const name = field.name
    let byName = cache.get(owner)
    if (!byName) cache.set(owner, byName = new Map())
    const known = byName.get(name)
    if (known) return known
    const decisions = new Set<boolean>()
    const walk = (cls: TlClass) => {
      if (isSerializable(cls) && inheritedFields(cls).get(name)?.owner === owner) decisions.add(cls.int53.has(name))
      for (const child of cls.children) walk(child)
    }
    walk(owner)
    const mode: LongMode = decisions.size === 2 ? 'mixed' : decisions.has(true) ? 'number' : 'string'
    byName.set(name, mode)
    return mode
  }
}

function assignRefs(kept: TlClass[]) {
  const taken = new Set<string>()
  const claim = (cls: TlClass, ns: string | null, id: string) => {
    // two java classes can want the same identifier without sharing a wire name (case-only
    // differences, `TL_`-stripping); that's cosmetic, so just disambiguate by container.
    let final = id
    if (taken.has(`${ns ?? ''}.${final}`)) final = `${id}$${cls.container}`
    taken.add(`${ns ?? ''}.${final}`)
    cls.ref = [ns, final]
  }

  for (const cls of kept) {
    if (isSerializable(cls)) {
      const tlName = cls.tlName!
      const dot = tlName.indexOf('.')
      if (dot >= 0) claim(cls, tlName.slice(0, dot), `Raw${upperFirst(tlName.slice(dot + 1))}`)
      else claim(cls, null, `Raw${upperFirst(tlName)}`)
    } else {
      // base classes carry no wire name; their java name may still embed a namespace
      const name = cls.name.replace(/^TL_/, '')
      const idx = name.indexOf('_')
      const ns = idx > 0 && name.slice(0, idx) === name.slice(0, idx).toLowerCase() ? name.slice(0, idx) : null
      claim(cls, ns, `Type${upperFirst(ns ? name.slice(idx + 1) : name)}`)
    }
  }
}

const PRIMITIVES: Record<string, string> = {
  'int': 'number',
  'Integer': 'number',
  'short': 'number',
  'Short': 'number',
  'byte': 'number',
  'Byte': 'number',
  'float': 'number',
  'Float': 'number',
  'double': 'number',
  'Double': 'number',
  'boolean': 'boolean',
  'Boolean': 'boolean',
  'String': 'string',
  'CharSequence': 'string',
  'byte[]': 'Uint8Array',
}

interface EmitCtx {
  resolve: (container: string, name: string) => TlClass | null
  keptSet: Set<TlClass>
}

/**
 * always rooted at `tl.`: an unqualified `RawFoo` written inside `namespace account` binds to
 * `tl.account.RawFoo` when that exists, silently referring to the wrong type.
 */
function refToString(cls: TlClass) {
  const [ns, id] = cls.ref
  return ns ? `tl.${ns}.${id}` : `tl.${id}`
}

function baseRef(cls: TlClass) {
  return `tl.$base.${cls.container}.${cls.name}`
}

/** the type a field of this class is read as: the union for a base, the interface for a leaf */
function typeRef(cls: TlClass) {
  return refToString(cls)
}

/**
 * mirrors TlJson.valueToJson - anything it can't map is dropped from the snapshot, so anything
 * this returns `null` for is dropped from the typings too.
 */
function mapType(java: string, cls: TlClass, ctx: EmitCtx, long: LongMode = 'string'): string | null {
  if (java === 'long' || java === 'Long') return long === 'mixed' ? 'number | string' : long
  const prim = PRIMITIVES[java]
  if (prim) return prim

  const generic = /^([\w.$]+)<(.+)>$/.exec(java)
  if (generic) {
    const [, raw, argsRaw] = generic
    const args = splitGenericArgs(argsRaw)
    const rawName = raw.split('.').pop()!
    if ((rawName === 'ArrayList' || rawName === 'List') && args.length === 1) {
      const inner = mapType(args[0], cls, ctx, long)
      return inner && (inner.includes('|') ? `(${inner})[]` : `${inner}[]`)
    }
    if ((rawName === 'HashMap' || rawName === 'Map' || rawName === 'LinkedHashMap') && args.length === 2) {
      if (args[0] !== 'String') return null
      const inner = mapType(args[1], cls, ctx)
      return inner && `Record<string, ${inner}>`
    }
    if (rawName === 'SparseArray' && args.length === 1) {
      const inner = mapType(args[0], cls, ctx)
      return inner && `Record<string, ${inner}>`
    }
    return null
  }

  if (java === 'TLObject') return 'tl.TypeTlObject'
  if (java.endsWith('[]')) return null // java arrays other than byte[] have no json mapping

  const target = ctx.resolve(cls.container, java)
  if (target && ctx.keptSet.has(target)) return typeRef(target)
  return null
}

function splitGenericArgs(s: string) {
  const out: string[] = []
  let depth = 0
  let cur = ''
  for (const ch of s) {
    if (ch === '<') depth++
    if (ch === '>') depth--
    if (ch === ',' && depth === 0) {
      out.push(cur.trim())
      cur = ''
    } else {
      cur += ch
    }
  }
  if (cur.trim()) out.push(cur.trim())
  return out
}

/** every public instance field visible on this class, own first, walking up the chain */
function inheritedFields(cls: TlClass): Map<string, { field: JavaField, owner: TlClass }> {
  const chain: TlClass[] = []
  for (let cur: TlClass | null = cls; cur; cur = cur.parent) chain.unshift(cur)
  const out = new Map<string, { field: JavaField, owner: TlClass }>()
  for (const c of chain) {
    for (const field of c.fields) {
      if (EXCLUDED_FIELDS.has(field.name)) continue
      out.set(field.name, { field, owner: c })
    }
  }
  return out
}

interface NsTree {
  lines: string[]
  children: Map<string, NsTree>
}

function makeTree(): NsTree {
  return { lines: [], children: new Map() }
}

function nsFor(tree: NsTree, ns: string | null) {
  if (!ns) return tree
  let child = tree.children.get(ns)
  if (!child) tree.children.set(ns, child = makeTree())
  return child
}

function renderTree(tree: NsTree, indent: string): string[] {
  const out = [...tree.lines.map(l => (l ? indent + l : ''))]
  for (const [name, child] of [...tree.children].sort(([a], [b]) => a.localeCompare(b))) {
    out.push(`${indent}namespace ${name} {`)
    out.push(...renderTree(child, `${indent}  `))
    out.push(`${indent}}`)
  }
  return out
}

/**
 * what this file describes, so a copy of it away from the repo still says which app it came from.
 * a plugin built against layer N and run on an app at a different layer is the main forward-compat
 * hazard here, and `inu.info().layer` is the runtime half of the same answer.
 */
async function readStamp() {
  const tlrpc = await fs.readFile(join(tgnetDir, 'TLRPC.java'), 'utf8')
  const props = await fs.readFile(join(worktreeDir, 'gradle.properties'), 'utf8')
  return {
    layer: /public static final int LAYER = (\d+)/.exec(tlrpc)?.[1] ?? 'unknown',
    appVersion: /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]?.trim() ?? 'unknown',
  }
}

function makeHeader(stamp: { layer: string, appVersion: string }) {
  return `// GENERATED by \`pnpm run generate-tl\` from worktree/TMessagesProj/src/main/java/org/telegram/tgnet.
// do not edit by hand - \`pnpm run setup\` regenerates it, or run the script after a rebase.
//
// @layer ${stamp.layer}
// @appVersion ${stamp.appVersion}
//
// these two are the file's identity: everything below is the schema of *that* app build. published
// as a versioned sdk package, this is what a plugin author pins against - and what to compare with
// \`inu.info().layer\` when a plugin has to run on an app older than the sdk it was built with.

/**
 * every TL type stock's tgnet layer knows about, shaped the way the plugin bridge actually
 * serializes it. this is *android's* schema, not the wire schema: stock carries \`//custom\` fields
 * that never touch the network (marked below), lags the current layer on some constructors, and
 * omits everything the app doesn't use.
 *
 * how java types land in js:
 * - \`long\` -> \`number\` where telegram guarantees the value fits in 53 bits (user/chat/channel ids,
 *   file sizes), \`string\` otherwise, so the rest of int64 survives a js number. a long accepts
 *   either back; a number past \`Number.MAX_SAFE_INTEGER\` is refused.
 * - \`byte[]\` -> \`Uint8Array\` (a base64 string is also accepted when writing).
 * - \`ArrayList<T>\` -> \`T[]\`; \`HashMap<String, V>\` and \`SparseArray<V>\` -> \`Record<string, V>\`.
 * - fields whose java type has no json mapping (raw arrays, app-internal classes) are absent here
 *   because the bridge drops them from snapshots too.
 *
 * optionality is read off each constructor's \`readParams\`: a field assigned unconditionally is
 * required, a flag-gated one is optional. that makes it accurate for reads and permissive for
 * writes. a few fields are optional here but always present in practice - \`readParams\` assigns
 * them in both arms of an \`if\`, which this doesn't try to detect.
 *
 * \`Raw*\` is one concrete constructor, \`Type*\` the union of a base class's constructors -
 * discriminate on \`_\`. \`$base\` holds the interfaces the \`Raw*\` types extend, one per java class
 * that carries fields of its own - a class that adds nothing is skipped and whoever extended it
 * reaches past to the nearest one that does, so the chain here is shorter than java's. you never
 * need to name any of them.
 */`
}

/** resolves whether either output changed */
export async function generateTl(): Promise<boolean> {
  const warnings: ParseWarning[] = []
  step('parsing tgnet sources')
  const raw = await parseAll(warnings)
  const { kept, keptSet, resolve } = buildGraph(raw, warnings)
  step('joining against tlscheme dumps')
  const schema = await loadSchema()
  const namespaces = new Set<string>()
  for (const entry of schema.values()) {
    const dot = entry.name.indexOf('.')
    if (dot > 0) namespaces.add(entry.name.slice(0, dot))
  }
  const overrides = assignTlNames(kept, schema, namespaces, warnings)

  const flagTable = buildFlagTable(raw)
  const flagWords = new Set<string>()
  for (const layout of flagTable.values()) {
    for (const [word] of layout.values()) flagWords.add(word)
  }
  for (const cls of kept) {
    if (!isSerializable(cls)) continue
    cls.flags = flagTable.get(parseConstructorId(cls.constructorHash!)) ?? new Map()
  }
  const unmatchedInt53 = assignInt53(kept.filter(isSerializable), await loadInt53Overrides())
  const longMode = makeLongModeResolver()
  assignRefs(kept)
  const ctx: EmitCtx = { resolve, keptSet }

  step(`emitting ${kept.length} types`)
  const base = makeTree()
  const main = makeTree()
  const rpcReturns: string[] = []
  const serializable: TlClass[] = []
  const dropped: string[] = []

  // what each class contributes on its own, so the emit below can tell a link that carries fields
  // from one that only forwards its parent's
  const own = new Map<TlClass, { lines: string[], count: number, shadowed: string[] }>()
  for (const cls of kept) {
    const lines: string[] = []
    let count = 0
    for (const field of cls.fields) {
      if (EXCLUDED_FIELDS.has(field.name)) continue
      // the bridge derives flag words from field presence and never exposes them
      if (flagWords.has(field.name)) continue
      const ts = mapType(field.type, cls, ctx, longMode(cls, field))
      if (!ts) {
        dropped.push(`${cls.name}.${field.name}: ${field.type}`)
        continue
      }
      if (field.custom) lines.push('/** app-local, never sent over the wire */')
      lines.push(`${field.name}?: ${ts}`)
      count++
    }
    // java lets a subclass redeclare an inherited field under a different type (`PageBlock.caption`
    // is a PageCaption, `pageBlockBlockquote.caption` a RichText). that's shadowing, not overriding,
    // so the inherited declaration has to be dropped rather than narrowed
    const shadowed = cls.parent
      ? cls.fields.filter((f) => {
          const inherited = inheritedFields(cls.parent!).get(f.name)
          return inherited
            && mapType(inherited.field.type, inherited.owner, ctx, longMode(inherited.owner, inherited.field))
            !== mapType(f.type, cls, ctx, longMode(cls, f))
        }).map(f => f.name)
      : []
    own.set(cls, { lines, count, shadowed })
  }

  /**
   * a java class with no mappable fields of its own adds nothing to an interface that extends it —
   * it either forwards its parent or contributes nothing at all — so it isn't emitted, and whoever
   * would have extended it reaches past to the nearest ancestor that does carry fields. `null` when
   * the whole chain is field-less, which means no `extends` clause at all.
   *
   * `seed` is what the class being emitted shadows itself; a skipped link's shadowed names get
   * added on the way past, or hopping over it would quietly hand back the inherited declaration it
   * was there to suppress.
   */
  function baseExtends(from: TlClass | null, seed: readonly string[] = []): string {
    const omit = new Set<string>(seed)
    for (let cur = from; cur; cur = cur.parent) {
      const info = own.get(cur)
      if (!info) continue
      if (info.count > 0) {
        return ` extends ${omit.size > 0
          ? `Omit<${baseRef(cur)}, ${[...omit].map(n => `'${n}'`).join(' | ')}>`
          : baseRef(cur)}`
      }
      for (const name of info.shadowed) omit.add(name)
    }
    return ''
  }

  for (const cls of kept.slice().sort((a, b) => a.name.localeCompare(b.name))) {
    // field-carrying interface, one per java class that has fields of its own
    const ownFields = own.get(cls)!
    if (ownFields.count > 0) {
      nsFor(base, cls.container).lines.push(
        `interface ${cls.name}${baseExtends(cls.parent, ownFields.shadowed)} {`,
        ...ownFields.lines.map(l => `  ${l}`),
        '}',
        '',
      )
    }

    const [ns, id] = cls.ref
    const target = nsFor(main, ns)
    if (isSerializable(cls)) {
      serializable.push(cls)
      const fields = inheritedFields(cls)
      // the schema knows exactly which params are flag-gated; `readParams` is only consulted for
      // constructors it doesn't cover
      const names = (cls.schema
        ? [...cls.schema.optional].filter(([, opt]) => !opt).map(([name]) => name)
        : [...cls.requiredFields]
      ).filter(name => !cls.flags.has(name) && !flagWords.has(name))
      const exact = (name: string): LongMode => (cls.int53.has(name) ? 'number' : 'string')
      const required: string[] = []
      const declared = new Set<string>()
      for (const name of names) {
        const entry = fields.get(name)
        if (!entry) continue
        const ts = mapType(entry.field.type, entry.owner, ctx, exact(name))
        if (ts) {
          required.push(`${name}: ${ts}`)
          declared.add(name)
        }
      }
      // a base shared by constructors that disagree types the long as either; each one narrows it
      for (const [name, entry] of fields) {
        if (declared.has(name) || flagWords.has(name) || longMode(entry.owner, entry.field) !== 'mixed') continue
        const ts = mapType(entry.field.type, entry.owner, ctx, exact(name))
        if (ts) required.push(`${name}?: ${ts}`)
      }
      target.lines.push(
        `interface ${id}${baseExtends(cls)} {`,
        `  _: '${cls.tlName}'`,
        ...required.map(l => `  ${l}`),
        '}',
        '',
      )
      if (cls.responseType) {
        const resolved = resolve(cls.container, cls.responseType)
        const inner = resolved && keptSet.has(resolved) ? typeRef(resolved) : 'tl.TypeTlObject'
        rpcReturns.push(`'${cls.tlName}': ${cls.responseIsVector ? `${inner}[]` : inner}`)
      }
    } else {
      const descendants: TlClass[] = []
      const walk = (c: TlClass) => {
        if (isSerializable(c)) descendants.push(c)
        for (const child of c.children) walk(child)
      }
      walk(cls)
      const members = descendants.map(d => refToString(d)).sort()
      if (members.length === 0) target.lines.push(`type ${id} = never`, '')
      else target.lines.push(`type ${id} =`, ...members.map(m => `  | ${m}`), '')
    }
  }

  main.lines.push(
    '/** every constructor the bridge can produce, discriminated on `_` */',
    'type TypeTlObject =',
    ...serializable.map(c => refToString(c)).sort().map(m => `  | ${m}`),
    '',
    '/** every constructor `invokeRpc` accepts */',
    'type TypeRpcMethod =',
    ...serializable.filter(c => c.responseType).map(c => refToString(c)).sort().map(m => `  | ${m}`),
    '',
    '/** what each method resolves to */',
    'interface RpcCallReturn {',
    ...rpcReturns.sort().map(l => `  ${l}`),
    '}',
    '',
  )

  const out = [
    makeHeader(await readStamp()),
    '',
    'declare namespace tl {',
    ...renderTree(main, '  '),
    '  namespace $base {',
    ...renderTree(base, '    '),
    '  }',
    '}',
    '',
  ].join('\n')

  const typingsChanged = await writeIfChanged(outFile, out)

  for (const w of warnings) warn(`${w.file}:${w.line} ${w.message}`)
  for (const d of dropped) warn(`no json mapping, field dropped: ${d}`)
  for (const u of unmatchedInt53) warn(`int53 override matches no long field stock declares: ${u}`)

  const words = [...flagWords].sort()
  if (words.length > 2) warn(`more than two flag words in use (${words.join(', ')}); the table format assumes at most two`)

  const ctorIdRows = buildCtorIdTable(raw, serializable)
  step(`ctor id table: ${ctorIdRows.length} names, ${ctorIdRows.reduce((n, r) => n + r.ids.length, 0)} ids`)

  // keyed by id like the flag table, so a layer variant read out of local storage follows the
  // constructor it descends from
  const int53ByName = new Map(serializable.map(cls => [cls.tlName!, cls.int53]))
  const int53ById = new Map<number, Set<string>>()
  for (const row of ctorIdRows) {
    const fields = int53ByName.get(row.name)
    if (!fields || fields.size === 0) continue
    for (const id of row.ids) {
      let set = int53ById.get(id)
      if (!set) int53ById.set(id, set = new Set())
      for (const field of fields) set.add(field)
    }
  }
  step(`int53 table: ${int53ById.size} constructors, ${unmatchedInt53.length} overrides unmatched`)

  const rows = new Map<number, string[]>()
  const tokensOf = (id: number) => {
    let tokens = rows.get(id)
    if (!tokens) rows.set(id, tokens = [])
    return tokens
  }
  for (const row of ctorIdRows) {
    for (const id of row.ids) tokensOf(id).push(`${row.kind}:${row.name}`)
  }
  for (const [id, layout] of flagTable) {
    for (const [name, [word, bit]] of layout) tokensOf(id).push(`f:${name}=${words.indexOf(word) === 0 ? '' : '+'}${bit}`)
  }
  for (const [id, fields] of int53ById) {
    for (const field of [...fields].sort()) tokensOf(id).push(`i:${field}`)
  }
  const tablesChanged = await writeIfChanged(tablesFile, [
    '# GENERATED by `pnpm run generate-tl` from worktree/TMessagesProj/src/main/java/org/telegram/tgnet,',
    '# TMessagesProj_AppTests/tlscheme and scripts/data/int53-overrides.json. read by TlTables.',
    '# do not edit by hand - `pnpm run setup` regenerates it, or run the script after a rebase.',
    `words ${words.join(' ')}`,
    `ns ${[...namespaces].sort().join(' ')}`,
    ...[...overrides].sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `name ${k} ${v}`),
    ...[...rows]
      .sort(([a], [b]) => (a >>> 0) - (b >>> 0))
      .map(([id, tokens]) => `${(id >>> 0).toString(16)} ${tokens.join(' ')}`),
    '',
  ].join('\n'))
  const namesOf = (kind: string) => [...new Set(ctorIdRows.filter(row => row.kind === kind).map(row => row.name))].sort()
  const namesChanged = await writeIfChanged(tlNamesFile, [
    '# GENERATED by `pnpm run generate-tl`: the rpc method and update vocabularies that `inu check`',
    '# validates a manifest against. do not edit by hand.',
    ...namesOf('m').map(name => `m ${name}`),
    ...namesOf('u').map(name => `u ${name}`),
    '',
  ].join('\n'))

  // stock's own layer dumps are an independent statement of the same layout; disagreement is
  // usually stock lagging the schema, but it's worth seeing
  let disagreements = 0
  for (const cls of serializable) {
    if (!cls.schema) continue
    for (const [name, optional] of cls.schema.optional) {
      if (optional && !cls.flags.has(name) && inheritedFields(cls).has(name)) disagreements++
    }
  }
  step(`${disagreements} fields the schema gates but stock doesn't`)

  const unmatched = serializable.filter(c => !c.schema)
  step(`${serializable.length - unmatched.length}/${serializable.length} constructors matched a layer dump; ${overrides.size} need a name override, ${unmatched.length} fall back to the prefix rule`)
  step(`${serializable.length} constructors, ${kept.length - serializable.length} unions, ${rpcReturns.length} methods`)
  return typingsChanged || tablesChanged || namesChanged
}

async function writeIfChanged(path: string, content: string) {
  if (await fs.readFile(path, 'utf8').catch(() => null) === content) return false
  await fs.writeFile(path, content)
  return true
}

// `pnpm run setup` imports this too, but refuses while the stack diverges from `series`: run it by hand after a rebase
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await generateTl()
  success('TL typings and tables generated')
}
