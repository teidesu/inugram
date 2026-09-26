import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import { join } from 'node:path'

export async function readFileSize(file: string): Promise<number> {
  return await fs.stat(file).then(stat => stat.size, () => 0)
}

/** Returns null if the file is missing or a build is still writing it. */
export async function readFileHash(file: string): Promise<string | null> {
  const body = await fs.readFile(file).catch(() => null)
  if (!body || body.length === 0) return null
  return createHash('sha256').update(body).digest('hex')
}

export interface CopyTree {
  from: string
  into: string
  rename: (name: string) => string
  substitute: (body: string) => string
}

export async function copyTree(options: CopyTree): Promise<void> {
  const { from, into, rename, substitute } = options
  for (const entry of await fs.readdir(from, { withFileTypes: true })) {
    const source = join(from, entry.name)
    const target = join(into, rename(entry.name))
    if (entry.isDirectory()) {
      await fs.mkdir(target, { recursive: true })
      await copyTree({ ...options, from: source, into: target })
      continue
    }
    await fs.writeFile(target, substitute(await fs.readFile(source, 'utf8')))
  }
}
