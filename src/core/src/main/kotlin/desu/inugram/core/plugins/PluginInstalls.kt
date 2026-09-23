package desu.inugram.core.plugins

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * [pluginId] records [PluginManifest.id] from the last successful file read. Keep it even if
 * the file stops parsing, so importing a fixed copy can recover the install ID and its storage.
 *
 * [dev] describes the latest source write: dev-server installs skip trust and permission review.
 * A normal installation clears it.
 */
data class PluginInstall(
    val id: String,
    val enabled: Boolean,
    val pluginId: String? = null,
    val dev: Boolean = false,
) {
    val file: String get() = PluginInstalls.fileName(id)
}

/**
 * Install IDs are random. Do not derive them from manifest data because they name plugin storage.
 *
 * A plugin's source file is named after its install ID, so the ID survives losing the persisted
 * state: the state only adds order and flags to what the directory already says.
 */
object PluginInstalls {
    const val ID_LENGTH = 32

    private const val SUFFIX = ".js"
    private const val HEX = "0123456789abcdef"
    private val defaultRandom: Random by lazy { SecureRandom().asKotlinRandom() }

    fun mintId(random: Random = defaultRandom): String {
        val sb = StringBuilder(ID_LENGTH)
        repeat(ID_LENGTH) { sb.append(HEX[random.nextInt(HEX.length)]) }
        return sb.toString()
    }

    fun isValidId(value: String?): Boolean =
        value != null && value.length == ID_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun fileName(id: String): String = "$id$SUFFIX"

    /** the install [name] is the source of, or null for anything else in the directory */
    fun idOfFile(name: String): String? =
        name.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)?.takeIf(::isValidId)

    /**
     * Every install with a source file, in persisted order. A file with no record comes last and
     * disabled: its record was lost, and nothing says the user still wanted it running.
     */
    fun reconcile(persisted: List<PluginInstall>, files: List<String>): List<PluginInstall> {
        val present = files.mapNotNullTo(LinkedHashSet(), ::idOfFile)
        val out = mutableListOf<PluginInstall>()
        for (record in persisted) {
            if (present.remove(record.id)) out.add(record)
        }
        for (id in present.sorted()) out.add(PluginInstall(id, enabled = false))
        return out
    }
}
