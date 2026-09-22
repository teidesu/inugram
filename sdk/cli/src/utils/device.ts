import { execFile } from 'node:child_process'
import { basename } from 'node:path'
import { promisify } from 'node:util'
import { sleep } from '@fuman/utils'
import * as v from 'valibot'
import { CliError } from './log.js'
import { describeIssue } from './schema.js'

const run = promisify(execFile)

export const RELEASE_APP_ID = 'desu.inugram'

const DROP_DIR_NAME = 'plugin-dev'
const DEV_ACTION = 'desu.inugram.plugins.DEV'
/** `PluginLog` tags: one channel per plugin, keyed by manifest id (install id without one), and one for the host */
const PLUGIN_LOG_TAG_PREFIX = 'InuPlugin/'
export const HOST_LOG_TAG = 'InuPluginHost'

export function getPluginLogTag(key: string): string {
  return PLUGIN_LOG_TAG_PREFIX + key
}

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

export interface DeviceOptions {
  serial?: string
  appId?: string
}

export function createDevice(args: { serial?: string, app: string }): Device {
  return new Device({ serial: args.serial, appId: args.app })
}

export class Device {
  readonly appId: string
  private readonly serial: string[]

  constructor(options: DeviceOptions = {}) {
    this.appId = options.appId ?? RELEASE_APP_ID
    this.serial = options.serial ? ['-s', options.serial] : []
  }

  get dropDir(): string {
    return `/sdcard/Android/data/${this.appId}/files/${DROP_DIR_NAME}`
  }

  private async adb(args: string[], allowFailure = false) {
    try {
      return await run('adb', [...this.serial, ...args], { maxBuffer: 32 * 1024 * 1024 })
    } catch (error) {
      if (allowFailure) return { stdout: '', stderr: String(error) }
      const detail = error as { stderr?: string, stdout?: string, code?: string }
      if (detail.code === 'ENOENT') throw new CliError('adb is not on PATH')
      throw new CliError((detail.stderr || detail.stdout || String(error)).trim())
    }
  }

  /** Returns null if the app is not running and no dev receiver is available. */
  async pid(): Promise<string | null> {
    const out = await this.adb(['shell', 'pidof', this.appId], true)
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

  async list(): Promise<DevPlugin[]> {
    const reply = await this.send({ cmd: 'list' }, ListSchema)
    return reply.plugins
  }

  async ping(): Promise<DevPing> {
    return this.send({ cmd: 'ping' }, PingSchema)
  }

  /**
   * Logcat only supports exact tag filters, so read the whole process and let [onLine] filter.
   * Resolve the PID again when the stream ends to handle app restarts.
   */
  async tailLogs(onLine: (level: string, tag: string, message: string) => void, signal: AbortSignal) {
    while (!signal.aborted) {
      const pid = await this.pid()
      if (!pid) {
        await sleep(1000)
        continue
      }
      await new Promise<void>((resolve) => {
        const child = execFile('adb', [...this.serial, 'logcat', '-v', 'brief', '--pid', pid])
        const stop = () => child.kill()
        signal.addEventListener('abort', stop, { once: true })
        let buffer = ''
        child.stdout?.on('data', (chunk: Buffer) => {
          buffer += String(chunk)
          const lines = buffer.split('\n')
          buffer = lines.pop() ?? ''
          for (const line of lines) {
            // brief format: "D/InuPlugin/name(  pid): message"
            const match = /^([VDIWEF])\/([^(]+)\(\s*\d+\):\s?(.*)$/.exec(line)
            if (!match) continue
            const [, level, tag, message] = match
            onLine(level, tag.trim(), message)
          }
        })
        child.on('close', () => {
          signal.removeEventListener('abort', stop)
          resolve()
        })
        child.on('error', () => {
          signal.removeEventListener('abort', stop)
          resolve()
        })
      })
    }
  }
}
