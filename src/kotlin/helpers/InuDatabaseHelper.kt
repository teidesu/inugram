package desu.inugram.helpers

import com.google.android.exoplayer2.util.Log
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MessagesStorage

object InuDatabaseHelper {
    @JvmStatic
    fun migrate(messagesStorage: MessagesStorage) {
        val db = messagesStorage.database;

        db.executeFast("CREATE TABLE IF NOT EXISTS inu_kv(key TEXT PRIMARY KEY, value TEXT)")
            .stepThis().dispose();
        var version = readKv(db, "version")?.toInt() ?: 0;
        Log.d("InuDatabaseHelper", "migrating from version $version")

        if (version == 0) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_folder_meta(filter_id INTEGER PRIMARY KEY, emoticon TEXT)")
                .stepThis().dispose();
            writeKv(db, "version", "1")
            version = 1
        }

        Log.d("InuDatabaseHelper", "migrating finished, new version = $version")
    }

    fun readKv(db: SQLiteDatabase, key: String): String? {
        val cursor = db.queryFinalized("select value from inu_kv where key = ?", key);
        try {
            if (!cursor.next()) {
                return null;
            }
            return cursor.stringValue(0);
        } finally {
            cursor.dispose();
        }
    }

    fun writeKv(db: SQLiteDatabase, key: String, value: String): Unit {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_kv VALUES(?, ?)");
        query.bindString(0, key)
        query.bindString(1, value)
        query.step()
        query.dispose()
    }
}