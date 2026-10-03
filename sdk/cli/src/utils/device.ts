import { execFile, spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { basename } from 'node:path'
import { createInterface } from 'node:readline'
import { promisify } from 'node:util'
import { sleep } from '@fuman/utils'
import * as v from 'valibot'
import { CliError } from './log.js'
import { describeIssue } from './schema.js'

const run = promisify(execFile)

export const RELEASE_APP_ID = 'desu.inugram'

const PID_POLL_MS = 2000
const DROP_DIR_NAME = 'plugin-dev'
const DEV_ACTION = 'desu.inugram.plugins.DEV'
/** `PluginLog` tags: one channel per plugin, keyed by manifest id (install id without one), and one for the host */
export const PLUGIN_LOG_TAG_PREFIX = 'InuPlugin/'
export const HOST_LOG_TAG = 'InuPluginHost'

/** The error returned by `PluginDevServer.fail` for any command. */
const FailureSchema = v.object({
  ok: v.literal(false),
  error: v.string(),
})

/** `PluginDevServer.describe` output. `pluginId` and `failure` use `putOpt`, so null values are omitted. */
const DevPluginSchema = v.object({
  id: v.string(),
  name: v.string(),
  pluginId: v.optional(v.string()),
  file: v.string(),
  enabled: v.boolean(),
  running: v.boolean(),
  dev: v.boolean(),
  failure: v.optional(v.string()),
})
export type DevPlugin = v.InferOutput<typeof DevPluginSchema>

const PingSchema = v.object({
  ok: v.literal(true),
  pluginApi: v.number(),
  engineEnabled: v.boolean(),
  safeMode: v.boolean(),
  dropDir: v.string(),
})
export type DevPing = v.InferOutput<typeof PingSchema>

const ListSchema = v.object({
  ok: v.literal(true),
  plugins: v.array(DevPluginSchema),
})

/** Result for one pushed file. The app can reject individual files in a batch. */
const InstallSchema = v.union([
  FailureSchema,
  v.object({
    ok: v.literal(true),
    action: v.string(),
    file: v.string(),
    plugin: DevPluginSchema,
  }),
])
export type DevInstall = v.InferOutput<typeof InstallSchema>

const InstallsSchema = v.object({
  ok: v.boolean(),
  results: v.array(InstallSchema),
})

const RemoveSchema = v.object({
  ok: v.literal(true),
  action: v.string(),
  plugin: DevPluginSchema,
})
export type DevRemoval = v.InferOutput<typeof RemoveSchema>

/** a rejection is `fulfilled: false`, not a failure: the code ran */
const EvalSchema = v.object({
  ok: v.literal(true),
  fulfilled: v.boolean(),
  text: v.string(),
  plugin: DevPluginSchema,
})
export type DevEval = v.InferOutput<typeof EvalSchema>

export interface LogLine {
  /** epoch microseconds */
  time: number
  level: string
  tag: string
  message: string
}

/** epoch format: "  1790813149.881343  pid  tid D tag: message" */
function parseLogLine(line: string): LogLine | null {
  const match = /^\s*(\d+)\.(\d{6})\s+\d+\s+\d+\s+([VDIWEF]) ([^:]*): ?(.*)$/.exec(line)
  if (!match) return null
  const [, seconds, micros, level, paddedTag, message] = match
  return { time: Number(seconds) * 1_000_000 + Number(micros), level, tag: paddedTag.trim(), message }
}

export class Device {
  readonly appId: string
  private readonly serial: string[]

  constructor(args: { serial?: string, app: string }) {
    this.appId = args.app
    this.serial = args.serial ? ['-s', args.serial] : []
  }

  get dropDir(): string {
    return `/sdcard/Android/data/${this.appId}/files/${DROP_DIR_NAME}`
  }

  /** [input] is written to stdin; `adb shell` passes its end on to the device under shell protocol v2, which android 7+ has */
  private async adb(args: string[], { allowFailure = false, input }: { allowFailure?: boolean, input?: string } = {}) {
    try {
      const pending = run('adb', [...this.serial, ...args], { maxBuffer: 32 * 1024 * 1024 })
      if (input !== undefined) pending.child.stdin?.end(input)
      return await pending
    } catch (error) {
      if (allowFailure) return { stdout: '', stderr: String(error) }
      const detail = error as { stderr?: string, stdout?: string, code?: string }
      if (detail.code === 'ENOENT') throw new CliError('adb is not on PATH')
      throw new CliError((detail.stderr || detail.stdout || String(error)).trim())
    }
  }

  /** Returns null if the app is not running and no dev receiver is available. */
  async pid(): Promise<string | null> {
    const out = await this.adb(['shell', 'pidof', this.appId], { allowFailure: true })
    const value = out.stdout.trim().split(/\s+/)[0]
    return value || null
  }

  async requireRunning(): Promise<string> {
    const pid = await this.pid()
    if (!pid) throw new CliError(`${this.appId} is not running - launch it first`)
    return pid
  }

  /**
   * `am broadcast` prints the receiver's ordered result, which reports whether installation
   * succeeded. No result data means no receiver handled it: dev mode is off.
   */
  private async send<TSchema extends v.GenericSchema>(
    extras: Record<string, string>,
    schema: TSchema,
  ): Promise<v.InferOutput<TSchema>> {
    const flags = Object.entries(extras).flatMap(([key, value]) => ['--es', key, value])
    const out = await this.adb(['shell', 'am', 'broadcast', '-a', DEV_ACTION, '-p', this.appId, ...flags])

    const at = out.stdout.indexOf('data="')
    const rest = at === -1 ? '' : out.stdout.slice(at + 'data="'.length)
    const end = rest.lastIndexOf('"')
    if (end === -1) {
      throw new CliError(`no reply from ${this.appId} - turn on developer mode in Settings > Plugins`)
    }
    const text = rest.slice(0, end)

    let reply: unknown
    try {
      reply = JSON.parse(text)
    } catch {
      throw new CliError(`${this.appId} replied with something that is not json: ${text}`)
    }

    const failure = v.safeParse(FailureSchema, reply)
    if (failure.success) throw new CliError(failure.output.error)

    const parsed = v.safeParse(schema, reply)
    if (!parsed.success) {
      const issues = parsed.issues.map(issue => describeIssue(issue))
      throw new CliError(`${this.appId} replied ${extras.cmd} with something this cli does not understand:\n${issues.join('\n')}`)
    }
    return parsed.output
  }

  /** one entry per pushed file, in the order they were pushed */
  async install(files: string[]): Promise<DevInstall[]> {
    await this.adb(['shell', 'mkdir', '-p', this.dropDir])
    for (const file of files) {
      await this.adb(['push', file, `${this.dropDir}/${basename(file)}`])
    }

    const installs: DevInstall[] = []
    for (const file of files) {
      const reply = await this.send({ cmd: 'install', file: basename(file) }, InstallsSchema)
      installs.push(...reply.results)
    }
    return installs
  }

  async remove(name: string): Promise<DevRemoval> {
    return this.send({ cmd: 'remove', file: basename(name) }, RemoveSchema)
  }

  /**
   * runs [code] in the plugin [pluginFile] was pushed as, or with null in the scratch plugin, which [reset]
   * restarts with its storage wiped first. The app reads the code from the drop dir and deletes it
   */
  async evaluate(pluginFile: string | null, code: string, timeoutSeconds: number, reset = false): Promise<DevEval> {
    const name = `eval-${randomUUID()}.txt`
    await this.adb(['shell', `mkdir -p ${this.dropDir} && cat > ${this.dropDir}/${name}`], { input: code })
    const target: Record<string, string> = pluginFile === null ? { reset: String(reset) } : { file: basename(pluginFile) }
    return this.send({ cmd: 'eval', code: name, timeout: String(timeoutSeconds), ...target }, EvalSchema)
  }

  async list(): Promise<DevPlugin[]> {
    const reply = await this.send({ cmd: 'list' }, ListSchema)
    return reply.plugins
  }

  async ping(): Promise<DevPing> {
    return this.send({ cmd: 'ping' }, PingSchema)
  }

  /** Device clock in logcat's epoch `-T` format. */
  async getLogTime(): Promise<string> {
    const out = await this.adb(['shell', 'date', '+%s.%N'])
    // older toybox date has no %N
    const match = /^(\d+)\.(\d{3})?/.exec(out.stdout.trim())
    if (!match) throw new CliError(`unexpected device time: ${out.stdout.trim()}`)
    return `${match[1]}.${match[2] ?? '000'}`
  }

  /**
   * The lines [pid] logged since [since]. A stream started at that moment can miss lines logged while
   * it attaches, even with `-T`; a dump of the buffer misses none
   */
  async dumpLogs(since: string, pid: string): Promise<LogLine[]> {
    const out = await this.adb(['logcat', '-d', '-v', 'epoch,usec', '-T', since, '--pid', pid])
    return out.stdout.split('\n').map(parseLogLine).filter(line => line !== null)
  }

  /**
   * Logcat only supports exact tag filters, so read the whole process and let [onLine] filter.
   * The stream ends when logd drops a reader, and is ended here when the app restarts, since
   * `logcat --pid` keeps waiting on a dead process. [onEnd] gets the reason, then the PID is resolved
   * again and reading resumes after the last line seen, never replaying one.
   */
  async tailLogs(
    since: string,
    onLine: (level: string, tag: string, message: string) => void,
    onEnd: (reason: string) => void,
    signal: AbortSignal,
  ) {
    // epoch microseconds of the latest line printed, and how many were printed at it: one crash logs its
    // whole stack under one timestamp, and `-T` resumes at that millisecond, so a reconnect skips
    // exactly what was already printed. Threads interleave with slightly earlier timestamps, so only a
    // reconnect's replay skips by time
    let lastTime = 0
    let printedAtLast = 0
    let from = since
    while (!signal.aborted) {
      const pid = await this.pid()
      if (!pid) {
        await sleep(1000)
        continue
      }
      await new Promise<void>((resolve) => {
        const child = spawn('adb', [...this.serial, 'logcat', '-v', 'epoch,usec', '-T', from, '--pid', pid], { stdio: ['ignore', 'pipe', 'pipe'] })
        const stop = () => child.kill()
        signal.addEventListener('abort', stop, { once: true })
        let stderr = ''
        let restarted = false
        let skipAtLast = printedAtLast
        let replaying = lastTime > 0
        const watch = setInterval(() => {
          void this.pid().then((current) => {
            if (current === pid) return
            restarted = true
            child.kill()
          }, () => {})
        }, PID_POLL_MS)
        child.stderr.on('data', (chunk: Buffer) => {
          stderr += chunk.toString()
        })
        createInterface({ input: child.stdout }).on('line', (text) => {
          const line = parseLogLine(text)
          if (!line) return
          const { time, level, tag, message } = line
          if (replaying) {
            if (time < lastTime) return
            if (time === lastTime && skipAtLast > 0) {
              skipAtLast--
              return
            }
            replaying = false
          }
          if (time === lastTime) {
            printedAtLast++
          } else if (time > lastTime) {
            lastTime = time
            printedAtLast = 1
            from = `${Math.floor(time / 1_000_000)}.${String(Math.floor(time / 1000) % 1000).padStart(3, '0')}`
          }
          onLine(level, tag, message)
        })
        const finish = (reason: string) => {
          clearInterval(watch)
          signal.removeEventListener('abort', stop)
          if (!signal.aborted) onEnd(reason)
          resolve()
        }
        child.on('close', code => finish(restarted ? 'the app restarted' : stderr.trim() || `logcat exited with ${code}`))
        child.on('error', error => finish(error.message))
      })
    }
  }
}
