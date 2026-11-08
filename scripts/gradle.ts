import { spawn } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import process from 'node:process'
import lockfile from 'proper-lockfile'
import { rootDir, worktreeDir } from './config.js'
import { step } from './lib.js'

const args = process.argv.slice(2)
const pluginless = args[0] === '--pluginless'
if (pluginless) args.shift()

const cwd = pluginless ? join(rootDir, 'worktree-pluginless') : worktreeDir
mkdirSync(join(cwd, '.gradle'), { recursive: true })

// a gradle daemon runs one build at a time: waiting inside the build would spawn a cold daemon
const lockOptions = {
  lockfilePath: join(cwd, '.gradle/inu-gradle.lock'),
  onCompromised: (err: Error) => console.warn(`build lock compromised: ${err.message}`),
}
if (await lockfile.check(cwd, lockOptions)) { step('Waiting for another build to finish') }
const release = await lockfile.lock(cwd, { ...lockOptions, retries: { forever: true, minTimeout: 200, maxTimeout: 1000 } })

const isWindows = process.platform === 'win32'
const child = spawn(isWindows ? 'gradlew.bat' : './gradlew', args, { cwd, stdio: 'inherit', shell: isWindows })

// ctrl+c reaches gradlew through the process group, keep the lock until it exits
process.on('SIGINT', () => {})
process.on('SIGTERM', () => child.kill('SIGTERM'))

const exitCode = await new Promise<number>((resolve) => {
  child.on('exit', code => resolve(code ?? 1))
  child.on('error', (err) => {
    console.error(err)
    resolve(1)
  })
})
await release()
process.exit(exitCode)
