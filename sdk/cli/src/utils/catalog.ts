import type { Catalog, CatalogGrant } from '@inugram/plugin-types/grants'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { CliError } from './log.js'

export type { Catalog, CatalogGrant, GrantTier, ScopeKind } from '@inugram/plugin-types/grants'

export interface Vocabulary {
  catalog: Catalog
  byName: Map<string, CatalogGrant>
  rpcMethods: Set<string>
  updateTypes: Set<string>
  domain: RegExp
  fsSize: RegExp
}

const PACKAGE = '@inugram/plugin-types'

function resolveFromProject(root: string, specifier: string): string {
  const require = createRequire(pathToFileURL(join(root, 'package.json')))
  try {
    return require.resolve(specifier)
  } catch {
    throw new CliError(`cannot find ${PACKAGE} in ${root} - install it with \`pnpm add -D ${PACKAGE}\``)
  }
}

/** the `m`/`u` records of `tl-names.txt`, written by the app's own TL generator */
function parseTlNames(source: string) {
  const rpcMethods = new Set<string>()
  const updateTypes = new Set<string>()
  for (const line of source.split('\n')) {
    if (line === '' || line.startsWith('#')) continue
    const at = line.indexOf(' ')
    const name = line.slice(at + 1)
    if (line.startsWith('m ')) rpcMethods.add(name)
    else if (line.startsWith('u ')) updateTypes.add(name)
  }
  return { rpcMethods, updateTypes }
}

export async function loadVocabulary(root: string): Promise<Vocabulary> {
  const catalogPath = resolveFromProject(root, `${PACKAGE}/grants.json`)
  const namesPath = resolveFromProject(root, `${PACKAGE}/tl-names.txt`)
  const catalog = JSON.parse(await fs.readFile(catalogPath, 'utf8')) as Catalog

  const { rpcMethods, updateTypes } = parseTlNames(await fs.readFile(namesPath, 'utf8'))
  if (rpcMethods.size === 0 || updateTypes.size === 0) {
    throw new CliError(`${namesPath} names no rpc methods or update types`)
  }

  return {
    catalog,
    byName: new Map(catalog.grants.map(grant => [grant.name, grant])),
    rpcMethods,
    updateTypes,
    domain: new RegExp(`^(?:${catalog.patterns.domain})$`),
    fsSize: new RegExp(`^(?:${catalog.patterns.fsSize})$`, 'i'),
  }
}
