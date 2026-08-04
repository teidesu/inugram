package desu.inugram.helpers.security

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Salted PBKDF2-HMAC-SHA256 for fork-local secrets (account passcodes, hidden-chats exit code).
// Kept out of InuConfig/backups by design — callers store into their own prefs file.
object SecretHash {
    private const val PREFIX = "pbkdf2$"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    private fun pbkdf2(code: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, KEY_BITS)
        val dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return PREFIX + iterations + "$" + Utilities.bytesToHex(dk)
    }

    private fun legacySha256(code: String, salt: ByteArray): String {
        val pwd = code.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(32 + pwd.size)
        System.arraycopy(salt, 0, bytes, 0, 16)
        System.arraycopy(pwd, 0, bytes, 16, pwd.size)
        System.arraycopy(salt, 0, bytes, pwd.size + 16, 16)
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.size.toLong()))
    }

    private fun constEq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    fun store(prefs: SharedPreferences, hashKey: String, saltKey: String, code: String) {
        try {
            val salt = ByteArray(16).also { Utilities.random.nextBytes(it) }
            prefs.edit {
                putString(hashKey, pbkdf2(code, salt, ITERATIONS))
                putString(saltKey, Base64.encodeToString(salt, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    fun verify(prefs: SharedPreferences, hashKey: String, saltKey: String, code: String): Boolean = try {
        val stored = prefs.getString(hashKey, "") ?: ""
        if (stored.isEmpty()) {
            false
        } else {
            val saltB64 = prefs.getString(saltKey, "") ?: ""
            val salt = if (saltB64.isNotEmpty()) Base64.decode(saltB64, Base64.DEFAULT) else ByteArray(0)
            if (stored.startsWith(PREFIX)) {
                val sep = stored.indexOf('$', PREFIX.length)
                val iterations = stored.substring(PREFIX.length, sep).toInt()
                constEq(stored, pbkdf2(code, salt, iterations))
            } else if (constEq(stored, legacySha256(code, salt))) {
                store(prefs, hashKey, saltKey, code)
                true
            } else {
                false
            }
        }
    } catch (e: Exception) {
        FileLog.e(e); false
    }
}
