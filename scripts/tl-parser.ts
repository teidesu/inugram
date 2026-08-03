// minimal java source scanner, just enough for the TL_* container files: it finds nested class
// declarations, their public instance fields, and the bodies of readParams/deserializeResponse.
// nothing here is a general java parser - it relies on the tgnet files being machine-generated
// and uniformly formatted.

export interface JavaField {
  name: string
  type: string
  custom: boolean
  line: number
}

export interface JavaClass {
  /** container file's top-level class, e.g. `TLRPC` or `TL_stories` */
  container: string
  /** enclosing class chain below the container, innermost last */
  path: string[]
  name: string
  superName: string | null
  isAbstract: boolean
  /** the `public static final int constructor` value, present iff the class is wire-serializable */
  constructorHash: string | null
  fields: JavaField[]
  /** field names assigned unconditionally in this class's own `readParams` */
  requiredFields: Set<string>
  /** this class's own `serializeToStream` body, which spells out the flag layout */
  serializeBody: string | null
  /** type named by this class's own `deserializeResponse`, if any */
  responseType: string | null
  /** true when `deserializeResponse` returns a vector of [responseType] */
  responseIsVector: boolean
  line: number
}

export interface ParseWarning {
  file: string
  line: number
  message: string
}

const CLASS_DECL = /\b(?:class|interface|enum)\s+(\w+)(?:\s*<[^>]*>)?(?:\s+extends\s+([\w.$]+)(?:\s*<\s*([\w.$]+)\s*>)?)?/
const ANNOTATION = /(?:@\w+(?:\([^)]*\))?\s+)*/.source
const FIELD_DECL = new RegExp(`^\\s*${ANNOTATION}public\\s+${ANNOTATION}(?:final\\s+${ANNOTATION})?([\\w.$]+(?:<.*>)?(?:\\[\\])*)\\s+(\\w+(?:\\s*,\\s*\\w+)*)$`)
// `final` is optional: a dozen classes across TL_stories/TL_stats/TL_stars write a bare
// `public static int constructor`, and reading that as a field rather than a constructor id makes
// the class look abstract - it silently loses its Raw type and its flag layout
const CONSTRUCTOR_DECL = /^public (?:static final|final static|static) int constructor = (0[xX][0-9a-fA-F]+|-?\d+)/
const RESPONSE_VECTOR = /return\s+Vector\.(?:TLDeserialize|deserialize)\s*\([^;]*?(?<!::)\b([\w.$]+)::TLdeserialize/
const RESPONSE_SCALAR = /return\s+([\w.$]+)\.TLdeserialize\s*\(/
const STATEMENT_KEYWORDS = /^(?:if|else|for|while|do|switch|return|case|final|int|long|short|byte|float|double|boolean|char|String|var|new)$/

interface Cursor {
  src: string
  i: number
  line: number
}

function isTrivia(src: string, i: number) {
  const ch = src[i]
  return ch === '"' || ch === '\'' || (ch === '/' && (src[i + 1] === '/' || src[i + 1] === '*'))
}

/** advances past a comment or string literal at the cursor, returning the line comment's text if it was one */
function skipTrivia(c: Cursor): string | null {
  const { src } = c
  const ch = src[c.i]
  const next = src[c.i + 1]

  if (ch === '/' && next === '/') {
    const end = src.indexOf('\n', c.i)
    const stop = end < 0 ? src.length : end
    const text = src.slice(c.i, stop)
    c.i = stop
    return text
  }
  if (ch === '/' && next === '*') {
    const end = src.indexOf('*/', c.i + 2)
    const stop = end < 0 ? src.length : end + 2
    for (let j = c.i; j < stop; j++) {
      if (src[j] === '\n') c.line++
    }
    c.i = stop
    return null
  }
  c.i++
  while (c.i < src.length) {
    if (src[c.i] === '\\') {
      c.i += 2
      continue
    }
    if (src[c.i] === ch) {
      c.i++
      break
    }
    if (src[c.i] === '\n') c.line++
    c.i++
  }
  return null
}

/** consumes a balanced `{...}` block; the cursor must sit on the opening brace */
function readBlock(c: Cursor): string {
  const start = c.i
  let depth = 0
  while (c.i < c.src.length) {
    if (isTrivia(c.src, c.i)) {
      skipTrivia(c)
      continue
    }
    const ch = c.src[c.i]
    if (ch === '\n') c.line++
    if (ch === '{') {
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0) {
        c.i++
        return c.src.slice(start, c.i)
      }
    }
    c.i++
  }
  return c.src.slice(start)
}

/** field names assigned outside of any conditional in a `readParams` body */
function parseRequiredFields(body: string): Set<string> {
  const out = new Set<string>()
  const c: Cursor = { src: body, i: 0, line: 0 }
  let depth = 0
  let stmt = ''
  while (c.i < body.length) {
    if (isTrivia(body, c.i)) {
      skipTrivia(c)
      continue
    }
    const ch = body[c.i]
    if (ch === '{') {
      depth++
      stmt = ''
    } else if (ch === '}') {
      depth--
      stmt = ''
    } else if (ch === ';') {
      if (depth === 1) {
        const m = /^\s*(\w+)\s*=[^=]/.exec(stmt)
        if (m && !STATEMENT_KEYWORDS.test(m[1])) out.add(m[1])
      }
      stmt = ''
    } else {
      stmt += ch
    }
    c.i++
  }
  return out
}

export function parseJavaFile(src: string, containerName: string, file: string, warnings: ParseWarning[]): JavaClass[] {
  const out: JavaClass[] = []
  const c: Cursor = { src, i: 0, line: 1 }
  const stack: JavaClass[] = []
  let buffer = ''
  let bufferLine = 1
  let comment: string | null = null

  const reset = () => {
    buffer = ''
    bufferLine = c.line
    comment = null
  }

  while (c.i < src.length) {
    if (isTrivia(src, c.i)) {
      const text = skipTrivia(c)
      if (text !== null) comment = text
      continue
    }
    const ch = src[c.i]
    if (ch === '\n') c.line++

    if (ch === '{') {
      const isClassDecl = !buffer.includes('(') && CLASS_DECL.test(buffer)
      if (isClassDecl) {
        const decl = CLASS_DECL.exec(buffer)!
        const cls: JavaClass = {
          container: containerName,
          path: stack.map(s => s.name),
          name: decl[1],
          superName: decl[2] ?? null,
          isAbstract: /\babstract\b/.test(buffer),
          constructorHash: null,
          fields: [],
          requiredFields: new Set(),
          serializeBody: null,
          // `extends TLMethod<T>` names the response in the declaration; the body only has
          // `deserializeResponseT`, which the deserializeResponse probe below doesn't match
          responseType: decl[2] === 'TLMethod' ? decl[3] ?? null : null,
          responseIsVector: false,
          line: bufferLine,
        }
        stack.push(cls)
        out.push(cls)
        c.i++
        reset()
        continue
      }

      const sig = buffer.replace(/\s+/g, ' ').trim()
      const owner = stack[stack.length - 1]
      const body = readBlock(c)
      if (owner) {
        if (/\breadParams\s*\(\s*InputSerializedData\b/.test(sig)) {
          owner.requiredFields = parseRequiredFields(body)
        } else if (/\bserializeToStream\s*\(\s*OutputSerializedData\b/.test(sig)) {
          owner.serializeBody = body
        } else if (/\bdeserializeResponse\s*\(\s*InputSerializedData\b/.test(sig)) {
          const vec = RESPONSE_VECTOR.exec(body)
          if (vec) {
            owner.responseType = vec[1]
            owner.responseIsVector = true
          } else {
            const scalar = RESPONSE_SCALAR.exec(body)
            if (scalar) owner.responseType = scalar[1]
          }
        }
      }
      reset()
      continue
    }

    if (ch === '}') {
      stack.pop()
      c.i++
      reset()
      continue
    }

    if (ch === ';') {
      const owner = stack[stack.length - 1]
      if (owner) {
        const eol = src.indexOf('\n', c.i)
        const rest = src.slice(c.i + 1, eol < 0 ? src.length : eol)
        const trailing = rest.includes('//') ? rest.slice(rest.indexOf('//')) : comment
        const eq = buffer.indexOf('=')
        const decl = buffer.replace(/\s+/g, ' ').trim()
        const head = (eq < 0 ? buffer : buffer.slice(0, eq)).replace(/\s+/g, ' ').trim()
        const hash = CONSTRUCTOR_DECL.exec(decl)
        if (hash) {
          owner.constructorHash = hash[1]
        } else {
          const m = FIELD_DECL.exec(head)
          if (m) {
            const custom = trailing != null && trailing.includes('custom')
            for (const name of m[2].split(',')) {
              owner.fields.push({ type: m[1], name: name.trim(), custom, line: bufferLine })
            }
          } else if (/^public\s/.test(head) && !/^public\s+(?:static|abstract|native)\b/.test(head)) {
            warnings.push({ file, line: bufferLine, message: `unparsed public member: ${head}` })
          }
        }
      }
      c.i++
      reset()
      continue
    }

    buffer += ch
    c.i++
  }

  if (stack.length > 0) {
    warnings.push({ file, line: c.line, message: `unbalanced braces, ${stack.length} class(es) left open` })
  }
  return out
}
