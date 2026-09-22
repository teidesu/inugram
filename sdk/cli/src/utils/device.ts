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
/** what PluginManager.logConsole and its neighbours tag with; logcat filterspecs have no wildcards */
const LOG_TAG_PREFIX = 'InuPlugin'

/** what `PluginDevServer.fail` answers, whatever the command was */
const FailureSchema = v.object({
  ok: v.literal(false),
  error: v.string(),
})

/** `PluginDevServer.describe`; `pluginId` and `failure` are `putOpt`, so absent rather than null */
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

/** one pushed file, which the app may refuse on its own while the others go in */
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

  /** null when the app is not running, which is also when there is no dev receiver to talk to */
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
   * `am broadcast` prints the ordered result the receiver set, which is the only thing that says
   * whether an install worked. No data at all means nothing received it: dev mode is off.
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
   * logcat's tag filters are exact, so the whole process is read and filtered here. The pid is
   * re-resolved when the tail ends, which is how an app restart is picked up.
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
            if (!tag.trim().startsWith(LOG_TAG_PREFIX)) continue
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
