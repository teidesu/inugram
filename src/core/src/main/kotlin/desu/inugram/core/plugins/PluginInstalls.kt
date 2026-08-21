package desu.inugram.core.plugins

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * [identity] is [PluginManifest.identity] as of the last time this install's file was read. Kept on
 * the record rather than taken from the live manifest so that an install whose file no longer parses
 * is still matchable: a re-import of a fixed file has to land back on this id, or the plugin's
 * stores are orphaned behind a record nothing lists.
 */
data class PluginInstall(
    val id: String,
    val file: String,
    val enabled: Boolean,
    val identity: String? = null,
)

/** Install IDs are random. Do not derive them from manifest data because they name plugin storage. */
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
