(emit) => {
  /* eslint-disable no-use-before-define */
  const handleMarker = Symbol.for('inu.tl.handle')
  const MAX_DEPTH = 2
  const MAX_ITEMS = 100
  const BREAK_LENGTH = 80
  const GROUP_MIN_ITEMS = 7
  const GROUP_MAX_CELL = 16
  const IDENTIFIER = /^[A-Z_$][\w$]*$/i
  const SPECIFIER = /%[sdifjoOc%]/g
  // eslint-disable-next-line no-control-regex
  const ESCAPED = /[\\'\x00-\x1F\x7F]/g
  const ESCAPES = { '\\': '\\\\', "'": "\\'", '\n': '\\n', '\r': '\\r', '\t': '\\t' }

  const escapeChar = char => ESCAPES[char] ?? `\\x${char.charCodeAt(0).toString(16).padStart(2, '0')}`
  const quote = string => `'${string.replace(ESCAPED, escapeChar)}'`
  const formatKey = key => (typeof key === 'symbol' ? `[${key.toString()}]` : IDENTIFIER.test(key) ? key : quote(key))
  const formatMore = count => `... ${count} more item${count === 1 ? '' : 's'}`
  const formatHoles = count => `<${count} empty item${count === 1 ? '' : 's'}>`
  const isIndex = key => typeof key === 'string' && String(Number(key) >>> 0) === key

  const formatPrimitive = (value) => {
    switch (typeof value) {
      case 'string':
        return quote(value)
      case 'number':
        return Object.is(value, -0) ? '-0' : String(value)
      case 'bigint':
        return `${value}n`
      case 'symbol':
        return value.toString()
      default:
        return String(value)
    }
  }

  const readConstructorName = (value) => {
    const proto = Object.getPrototypeOf(value)
    if (proto === null) return null
    const ctor = proto.constructor
    return typeof ctor === 'function' && typeof ctor.name === 'string' && ctor.name !== '' ? ctor.name : 'Object'
  }

  const readPrefix = (value, plain) => {
    const name = readConstructorName(value)
    const tag = value[Symbol.toStringTag]
    let prefix = name === null ? `[${plain}: null prototype]` : name
    if (typeof tag === 'string' && tag !== '' && tag !== name) prefix += ` [${tag}]`
    return prefix === plain ? '' : `${prefix} `
  }

  const groupCells = (cells, indent) => {
    if (cells.length < GROUP_MIN_ITEMS) return cells
    let width = 0
    for (const cell of cells) {
      if (cell.length > GROUP_MAX_CELL || cell.includes('\n')) return cells
      width = Math.max(width, cell.length)
    }
    const columns = Math.max(1, Math.floor((BREAK_LENGTH - indent.length - 2) / (width + 1)))
    const rows = []
    for (let i = 0; i < cells.length; i += columns) {
      rows.push(cells.slice(i, i + columns).map(cell => cell.padEnd(width)).join(' ').trimEnd())
    }
    return rows
  }

  const joinEntries = (prefix, open, close, items, extras, indent, groupable) => {
    const entries = [...items, ...extras]
    if (entries.length === 0) return `${prefix}${open}${close}`
    const start = `${prefix}${open}`
    const line = `${start} ${entries.join(', ')} ${close}`
    if (indent.length + line.length <= BREAK_LENGTH && !line.includes('\n')) return line
    const cells = entries.map((entry, i) => (i === entries.length - 1 ? entry : `${entry},`))
    const itemCells = cells.slice(0, items.length)
    const rows = [...(groupable ? groupCells(itemCells, indent) : itemCells), ...cells.slice(items.length)]
    const inner = `${indent}  `
    return `${start}\n${inner}${rows.join(`\n${inner}`)}\n${indent}${close}`
  }

  const formatList = (prefix, length, readItem, extras, indent) => {
    const items = []
    const shown = Math.min(length, MAX_ITEMS)
    let holes = 0
    for (let i = 0; i < shown; i++) {
      const item = readItem(i)
      if (item === null) {
        holes++
        continue
      }
      if (holes > 0) items.push(formatHoles(holes))
      holes = 0
      items.push(item)
    }
    if (holes > 0) items.push(formatHoles(holes))
    const more = length > shown ? [formatMore(length - shown)] : []
    return joinEntries(prefix, '[', ']', items, [...more, ...extras], indent, true)
  }

  const formatCollection = (value, name, size, formatEntry, indent) => {
    const items = []
    for (const entry of value) {
      if (items.length === MAX_ITEMS) break
      items.push(formatEntry(entry))
    }
    const extras = size > items.length ? [formatMore(size - items.length)] : []
    return joinEntries(`${readConstructorName(value) ?? name}(${size}) `, '{', '}', items, extras, indent, false)
  }

  const formatDescriptor = (descriptor, child) => {
    if ('value' in descriptor) return child(descriptor.value)
    if (descriptor.get !== undefined && descriptor.set !== undefined) return '[Getter/Setter]'
    return descriptor.get !== undefined ? '[Getter]' : '[Setter]'
  }

  const readProperties = (value, child, skipKey) => {
    const entries = []
    for (const key of Reflect.ownKeys(value)) {
      if (skipKey !== undefined && skipKey(key)) continue
      const descriptor = Reflect.getOwnPropertyDescriptor(value, key)
      if (descriptor === undefined || !descriptor.enumerable) continue
      entries.push(`${formatKey(key)}: ${formatDescriptor(descriptor, child)}`)
    }
    return entries
  }

  const formatFunction = (value) => {
    const name = typeof value.name === 'string' && value.name !== '' ? value.name : null
    if (Function.prototype.toString.call(value).startsWith('class')) {
      return name === null ? '[class (anonymous)]' : `[class ${name}]`
    }
    const kind = readConstructorName(value) ?? 'Function'
    return name === null ? `[${kind} (anonymous)]` : `[${kind}: ${name}]`
  }

  const formatError = (error, indent) => {
    const head = String(error)
    const stack = typeof error.stack === 'string' ? error.stack.trimEnd() : ''
    const text = stack === '' ? head : stack.startsWith(head) ? stack : `${head}\n${stack}`
    return text.replaceAll('\n', `\n${indent}`)
  }

  // a view is a proxy whose `ownKeys` trap is one crossing while each descriptor read is a field
  // read, and a dispatch view caches nothing: `Object.keys` would read every field twice
  const formatTlView = (view, wire, depth, seen, indent) => {
    const inner = `${indent}  `
    try {
      if (wire[1] === 'V') {
        if (depth > MAX_DEPTH) return '[Array]'
        return formatList('', view.length, i => formatValue(view[i], depth + 1, seen, inner), [], indent)
      }
      const name = view._
      if (depth > MAX_DEPTH) return `[${name}]`
      const entries = []
      for (const key of Reflect.ownKeys(view)) {
        if (key !== '_') entries.push(`${formatKey(key)}: ${formatValue(view[key], depth + 1, seen, inner)}`)
      }
      return joinEntries(`${name} `, '{', '}', entries, [], indent, false)
    } catch (error) {
      return `[TL view: ${error?.message ?? error}]`
    }
  }

  const formatObject = (value, depth, seen, indent) => {
    const inner = `${indent}  `
    const child = item => formatValue(item, depth + 1, seen, inner)
    if (Array.isArray(value)) {
      const readItem = i => (Object.hasOwn(value, i) ? child(value[i]) : null)
      return formatList(readPrefix(value, 'Array'), value.length, readItem, readProperties(value, child, isIndex), indent)
    }
    if (ArrayBuffer.isView(value) && !(value instanceof DataView)) {
      const prefix = `${readConstructorName(value) ?? 'TypedArray'}(${value.length}) `
      return formatList(prefix, value.length, i => formatPrimitive(value[i]), [], indent)
    }
    if (value instanceof ArrayBuffer) return `ArrayBuffer { byteLength: ${value.byteLength} }`
    if (value instanceof Map) {
      return formatCollection(value, 'Map', value.size, ([key, item]) => `${child(key)} => ${child(item)}`, indent)
    }
    if (value instanceof Set) return formatCollection(value, 'Set', value.size, child, indent)
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? 'Invalid Date' : value.toISOString()
    if (value instanceof RegExp) return RegExp.prototype.toString.call(value)
    // eslint-disable-next-line unicorn/no-instanceof-builtins
    if (value instanceof Number || value instanceof String || value instanceof Boolean) {
      return `[${readConstructorName(value) ?? 'Object'}: ${formatPrimitive(value.valueOf())}]`
    }
    return joinEntries(readPrefix(value, 'Object'), '{', '}', readProperties(value, child), [], indent, false)
  }

  const formatValue = (value, depth, seen, indent) => {
    if (typeof value === 'function') return formatFunction(value)
    if (value === null || typeof value !== 'object') return formatPrimitive(value)
    if (seen.includes(value)) return '[Circular]'
    const wire = value[handleMarker]
    if (typeof wire === 'string') return formatTlView(value, wire, depth, seen, indent)
    if (value instanceof Error) return formatError(value, indent)
    if (depth > MAX_DEPTH) return `[${Array.isArray(value) ? 'Array' : readConstructorName(value) ?? 'Object'}]`
    seen.push(value)
    try {
      return formatObject(value, depth, seen, indent)
    } finally {
      seen.pop()
    }
  }

  const inspect = (value) => {
    try {
      return formatValue(value, 0, [], '')
    } catch {
      return '[unprintable value]'
    }
  }

  const formatArgument = value => (typeof value === 'string' ? value : inspect(value))

  const formatNumber = (value, parse) => {
    if (typeof value === 'bigint') return `${value}n`
    try {
      return formatPrimitive(parse(value))
    } catch {
      return 'NaN'
    }
  }

  const formatJson = (value) => {
    try {
      return String(JSON.stringify(value))
    } catch {
      return '[unserializable value]'
    }
  }

  const formatSpecifier = (specifier, value) => {
    switch (specifier) {
      case '%s':
        return formatArgument(value)
      case '%d':
        return formatNumber(value, Number)
      case '%i':
        return formatNumber(value, Number.parseInt)
      case '%f':
        return formatNumber(value, Number.parseFloat)
      case '%j':
        return formatJson(value)
      case '%c':
        return ''
      default:
        return inspect(value)
    }
  }

  const formatLine = (args) => {
    let index = 0
    const parts = []
    if (typeof args[0] === 'string' && args.length > 1 && args[0].includes('%')) {
      index = 1
      parts.push(args[0].replace(SPECIFIER, (specifier) => {
        if (specifier === '%%') return '%'
        if (index >= args.length) return specifier
        return formatSpecifier(specifier, args[index++])
      }))
    }
    for (; index < args.length; index++) parts.push(formatArgument(args[index]))
    return parts.join(' ')
  }

  const console = {}
  for (const [name, level] of [['log', 0], ['info', 1], ['warn', 2], ['error', 3], ['debug', 4]]) {
    console[name] = (...args) => emit(level, formatLine(args))
  }
  return console
}
