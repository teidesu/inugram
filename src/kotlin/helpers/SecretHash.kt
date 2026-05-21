package desu.inugram.helpers

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.nio.charset.StandardCharsets

// Salted SHA-256 for fork-local secrets (account passcodes, hidden-chats exit code).
// Kept out of InuConfig/backups by design — callers store into their own prefs file.
object SecretHash {
    private fun hash(code: String, salt: ByteArray): String {
        val pwd = code.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(32 + pwd.size)
        System.arraycopy(salt, 0, bytes, 0, 16)
        System.arraycopy(pwd, 0, bytes, 16, pwd.size)
        System.arraycopy(salt, 0, bytes, pwd.size + 16, 16)
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.size.toLong()))
    }

    fun store(prefs: SharedPreferences, hashKey: String, saltKey: String, code: String) {
        try {
            val salt = ByteArray(16).also { Utilities.random.nextBytes(it) }
            prefs.edit {
                putString(hashKey, hash(code, salt))
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
            stored == hash(code, salt)
        }
    } catch (e: Exception) {
        FileLog.e(e); false
    }
}
