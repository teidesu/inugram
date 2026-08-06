// emits the compile-only stock TL type tree the plugin-bridge test harness reflects over
// (`:InuCore:bridgeTest`). shape only - no serialization, no behaviour - but every class name,
// nesting, superclass, constructor id and public field comes out of the same parse
// `generate-tl-typings` uses, so the harness cannot drift from stock the way a hand-written stub
// would. see src/core/src/bridgeTest/README.md.

import type { JavaClass } from './tl-parser.js'
import { promises as fs } from 'node:fs'
import { basename, join } from 'node:path'
import { glob } from 'tinyglobby'
import { rootDir, worktreeDir } from './config.js'
import { parseJavaFile } from './tl-parser.js'

const tgnetDir = join(worktreeDir, 'TMessagesProj/src/main/java/org/telegram/tgnet')
const outDir = join(rootDir, 'src/core/src/bridgeTest/tlstubs/org/telegram/tgnet')

// packages a container file lives in, keyed by its file name
const PACKAGES: Record<string, string> = { TL_legacy_message: 'org.telegram.tgnet.tl.legacy' }

// types stock's TL classes name that are not TL classes themselves. the harness declares each of
// them (android.*, org.telegram.messenger.*) or java does; a field of any other unknown type is
// dropped, since it can only be a `//custom` slot pointing into app code the harness has no reason
// to model.
const EXTERNAL_TYPES = new Set([
  'int',
  'long',
  'boolean',
  'double',
  'float',
  'byte',
  'short',
  'char',
  'void',
  'String',
  'Object',
  'CharSequence',
  'Integer',
  'Boolean',
  'Long',
  'Runnable',
  'ArrayList',
  'HashMap',
  'HashSet',
  'List',
  'Map',
  'Set',
  'SparseArray',
  'Bitmap',
  'Path',
  'Drawable',
  'BitmapDrawable',
  'File',
  'ByteBuffer',
])

const IMPORTS = [
  'java.io.File',
  'java.nio.ByteBuffer',
  'java.util.ArrayList',
  'java.util.HashMap',
  'java.util.HashSet',
  'java.util.List',
  'java.util.Map',
  'java.util.Set',
  'android.graphics.Bitmap',
  'android.graphics.Path',
  'android.graphics.drawable.BitmapDrawable',
  'android.graphics.drawable.Drawable',
  'android.util.SparseArray',
]

const CONTAINER_CONSTANT = /^ {4}public static final (?:int|long|String) \w+\s*=[^;]+;/

interface Container {
  name: string
  pkg: string
  classes: JavaClass[]
  constants: string[]
}

async function main() {
  const files = [...new Set([
    join(tgnetDir, 'TLRPC.java'),
    ...await glob('tl/**/*.java', { cwd: tgnetDir, absolute: true }),
  ])].sort()

  const containers: Container[] = []
  for (const file of files) {
    const name = basename(file, '.java')
    const src = await fs.readFile(file, 'utf8')
    const classes = parseJavaFile(src, name, basename(file), [])
    if (classes.length === 0) continue
    containers.push({
      name,
      pkg: PACKAGES[name] ?? (file.includes('/tl/') ? 'org.telegram.tgnet.tl' : 'org.telegram.tgnet'),
      classes,
      constants: src.split('\n').filter(line => CONTAINER_CONSTANT.test(line)).map(line => line.trim()),
    })
  }

  // simple name -> containers declaring it, so a bare cross-container reference can be qualified
  const owners = new Map<string, string[]>()
  for (const container of containers) {
    for (const cls of container.classes) {
      if (cls.path.length === 0) continue
      const list = owners.get(cls.name) ?? []
      if (!list.includes(container.name)) list.push(container.name)
      owners.set(cls.name, list)
    }
  }
  const containerNames = new Set(containers.map(c => c.name))

  await fs.rm(outDir, { recursive: true, force: true })
  let dropped = 0
  let emitted = 0

  for (const container of containers) {
    const local = new Set(container.classes.filter(c => c.path.length > 0).map(c => c.name))
    const resolve = (type: string): string | null => resolveType(type, container.name, local, owners, containerNames)

    const lines: string[] = [
      `package ${container.pkg};`,
      '',
      ...IMPORTS.map(i => `import ${i};`),
      ...(container.pkg === 'org.telegram.tgnet' ? [] : ['import org.telegram.tgnet.TLObject;', 'import org.telegram.tgnet.TLRPC;']),
      ...containers
        .filter(c => c.name !== container.name && c.pkg !== container.pkg)
        .map(c => `import ${c.pkg}.${c.name};`),
      '',
      `public class ${container.name} {`,
      ...container.constants.map(c => `    ${c}`),
    ]

    for (const cls of container.classes) {
      if (cls.path.length === 0) continue
      const superName = cls.superName === null ? 'TLObject' : resolve(cls.superName) ?? 'TLObject'
      const modifiers = cls.isAbstract ? 'public static abstract class' : 'public static class'
      lines.push(`    ${modifiers} ${cls.name} extends ${superName} {`)
      if (cls.constructorHash !== null) {
        lines.push(`        public static final int constructor = ${cls.constructorHash};`)
      }
      for (const field of cls.fields) {
        const type = resolve(field.type)
        if (type === null) {
          dropped++
          continue
        }
        const init = field.initializer !== null ? initializerFor(field.initializer, resolve) : null
        lines.push(`        public ${type} ${field.name}${init === null ? '' : ` = ${init}`};`)
        emitted++
      }
      lines.push('    }')
    }
    lines.push('}', '')

    const dir = join(outDir, container.pkg === 'org.telegram.tgnet' ? '.' : container.pkg.slice('org.telegram.tgnet.'.length).replace(/\./g, '/'))
    await fs.mkdir(dir, { recursive: true })
    await fs.writeFile(join(dir, `${container.name}.java`), lines.join('\n'))
  }

  const total = containers.reduce((a, c) => a + c.classes.length - 1, 0)
  console.log(`generate-tl-stubs: ${containers.length} containers, ${total} classes, ${emitted} fields (${dropped} of unmodelled type dropped)`)
}

/** returns the java type text to emit, or null when it names something the harness cannot declare */
function resolveType(
  type: string,
  container: string,
  local: ReadonlySet<string>,
  owners: ReadonlyMap<string, string[]>,
  containerNames: ReadonlySet<string>,
): string | null {
  const trimmed = type.trim()
  if (trimmed.endsWith('[]')) {
    const inner = resolveType(trimmed.slice(0, -2), container, local, owners, containerNames)
    return inner === null ? null : `${inner}[]`
  }
  const generic = /^([\w.$]+)<(.*)>$/.exec(trimmed)
  if (generic !== null) {
    const raw = resolveType(generic[1], container, local, owners, containerNames)
    if (raw === null) return null
    const args: string[] = []
    for (const arg of splitArgs(generic[2])) {
      const resolved = resolveType(arg, container, local, owners, containerNames)
      if (resolved === null) return null
      args.push(resolved)
    }
    return `${raw}<${args.join(', ')}>`
  }
  if (trimmed.includes('.')) {
    const [head] = trimmed.split('.')
    return containerNames.has(head) ? trimmed : null
  }
  if (EXTERNAL_TYPES.has(trimmed)) return trimmed
  if (trimmed === 'TLObject') return trimmed
  if (local.has(trimmed)) return trimmed
  const owner = owners.get(trimmed)
  if (owner === undefined) return null
  // a name several containers declare is ambiguous only if this container declares none of them,
  // which the `local` check above already ruled out; take the first for determinism
  return `${owner[0]}.${trimmed}`
}

function initializerFor(text: string, resolve: (type: string) => string | null): string | null {
  if (text === 'null' || /^-?\d+[LFD]?$/i.test(text) || text === 'true' || text === 'false') return text
  const created = /^new ([\w.$]+)(<[^(]*>)?\(\s*\)$/.exec(text)
  if (created === null) return null
  const type = resolve(created[1])
  if (type === null) return null
  return `new ${type}${created[2] === undefined ? '' : '<>'}()`
}

function splitArgs(text: string): string[] {
  const out: string[] = []
  let depth = 0
  let current = ''
  for (const ch of text) {
    if (ch === '<') depth++
    if (ch === '>') depth--
    if (ch === ',' && depth === 0) {
      out.push(current)
      current = ''
      continue
    }
    current += ch
  }
  if (current.trim() !== '') out.push(current)
  return out
}

await main()
