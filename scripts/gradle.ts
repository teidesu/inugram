import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
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

// upstream ships no gradlew.bat, so do what gradlew does with the JDK gradle would pick
function windowsWrapper(): [string, string[]] {
  const studioConfig = join(cwd, '.gradle/config.properties')
  const studioJavaHome = existsSync(studioConfig)
    ? readFileSync(studioConfig, 'utf8').match(/^[\t ]*java\.home[\t ]*[=:]([^\r\n]*)/m)?.[1].trim().replace(/\\([\\:= ])/g, '$1')
    : undefined
  const javaHome = process.env.JAVA_HOME || studioJavaHome
  return [
    javaHome ? join(javaHome, 'bin/java.exe') : 'java.exe',
    ['-Dorg.gradle.appname=gradlew', '-classpath', join(cwd, 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', ...args],
  ]
}

const isWindows = process.platform === 'win32'
const [command, commandArgs] = isWindows ? windowsWrapper() : ['./gradlew', args]
const child = spawn(command, commandArgs, { cwd, stdio: 'inherit' })

// ctrl+c reaches gradlew through the process group, keep the lock until it exits
process.on('SIGINT', () => {})
process.on('SIGTERM', () => child.kill('SIGTERM'))

const exitCode = await new Promise<number>((resolve) => {
  child.on('exit', code => resolve(code ?? 1))
  child.on('error', (err) => {
    console.error(err)
    if (isWindows) console.error('Set JAVA_HOME to your JDK, or configure the Gradle JDK in Android Studio')
    resolve(1)
  })
})
await release()
process.exit(exitCode)
