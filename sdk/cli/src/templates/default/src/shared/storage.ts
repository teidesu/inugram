// shared between every plugin in this repo: each one bundles its own copy, because a plugin runs
// alone in its own engine and there is nothing for them to share at runtime

export function readFlag(key: string, fallback: boolean): boolean {
  const stored = localStorage.getItem(key)
  return stored === null ? fallback : stored === '1'
}

export function writeFlag(key: string, value: boolean): void {
  localStorage.setItem(key, value ? '1' : '0')
}
