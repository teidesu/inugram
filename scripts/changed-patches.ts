import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { $, chalk } from 'zx'
import { rootDir } from './config.js'

$.verbose = false

// hunk headers carry line numbers that shift on every re-export even when the
// content is identical; strip them so only real content diffs remain.
function normalize(patch: string) {
  return patch.replace(/^@@ -[\d,]+ \+[\d,]+ @@/gm, '@@')
}

const git = $({ cwd: rootDir })

const names = (await git`git diff --name-only -- patches/*.patch`).stdout.split('\n').filter(Boolean)

const changed: string[] = []
for (const name of names) {
  if (!existsSync(join(rootDir, name))) continue // skip deleted
  const [current, old] = await Promise.all([
    git`cat ${name}`,
    git`git show HEAD:${name}`,
  ])

  if (normalize(old.stdout) !== normalize(current.stdout)) {
    changed.push(name)
  }
}

for (const name of changed) console.log(name)
console.error(
  chalk.blue('==>'),
  `${changed.length} of ${names.length} changed patches differ in content`,
)
