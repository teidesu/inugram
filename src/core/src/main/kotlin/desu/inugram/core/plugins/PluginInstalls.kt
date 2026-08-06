package desu.inugram.core.plugins

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

data class PluginInstall(val id: String, val file: String, val enabled: Boolean)

/**
 * Identity of an installed plugin. The id is minted at install time and is unrelated to anything the
 * plugin file declares - `src/plugins/common.d.ts` promises renaming keeps a plugin's data and that
 * no plugin can name its way into another's `kv`/`fs` storage, which only holds while nothing keys
 * off the manifest.
 *
 * Ids also name on-disk storage (a prefs file today, a scoped directory once `fs` lands), so
 * [isValidId] is the gate every persisted id passes before it reaches a path.
 */
object PluginInstalls {
    const val ID_LENGTH = 32

    private const val HEX = "0123456789abcdef"
    private val defaultRandom: Random by lazy { SecureRandom().asKotlinRandom() }

    fun mintId(random: Random = defaultRandom): String {
        val sb = StringBuilder(ID_LENGTH)
        repeat(ID_LENGTH) { sb.append(HEX[random.nextInt(HEX.length)]) }
        return sb.toString()
    }

    fun isValidId(value: String?): Boolean =
        value != null && value.length == ID_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * joins persisted installs to the files actually present, in persisted order with unknown files
     * appended. a file nobody has an id for is a new install, so it gets a fresh id and starts
     * enabled; a record whose file is gone is dropped, taking its id with it.
     */
    fun reconcile(
        persisted: List<PluginInstall>,
        files: List<String>,
        random: Random = defaultRandom,
    ): List<PluginInstall> {
        val present = files.toHashSet()
        val seen = HashSet<String>()
        val out = mutableListOf<PluginInstall>()
        for (record in persisted) {
            if (record.file !in present || !seen.add(record.file)) continue
            out.add(if (isValidId(record.id)) record else record.copy(id = mintId(random)))
        }
        for (file in files) {
            if (!seen.add(file)) continue
            out.add(PluginInstall(mintId(random), file, true))
        }
        return out
    }
}
