declare namespace inu {
  /**
   * A plugin manifest, inline in the entry file:
   *
   * ```ts
   * export const manifest = { name: 'Dog', grants: ['kv'] } satisfies inu.Manifest
   * ```
   *
   * The cli reads it without running the entry, so it must be a literal. Its keys override the
   * plugin's `manifest` in `inu.config.ts`, which overrides the config's top-level `manifest`.
   */
  interface Manifest {
    /** shown everywhere the plugin is named; required once all layers are merged */
    name?: string
    /** identifies updates to an installed plugin; derived from `author` and `name` when omitted */
    id?: string
    author?: string
    version?: string
    /** one string, or a language map whose `en` (or first entry) is the untagged description */
    description?: string | Record<string, string>
    /** `inu://{name}`, `tg://emoji?id={id}` or `tg://addstickers?set={slug}` */
    icon?: string
    /** `@grant` tokens, e.g. `account.read(peers)`. Replaces, not extends, the config's list */
    grants?: string[]
    /** defaults to the api level of the installed `@inugram/plugin-types` */
    pluginApi?: number
    platform?: string
    /** additional directives, preserved verbatim in `inu.info().header`; merged key by key */
    extra?: Record<string, string | string[]>
  }
}
