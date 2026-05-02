package desu.inugram.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.ui.SettingsImportConfirmSheet
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import kotlin.system.exitProcess

object SettingsBackupHelper {
    const val FORMAT_VERSION = 1

    // curated allowlist of stock mainconfig keys worth roundtripping.
    // values are typed at runtime via prefs.all; long/float distinguished with key suffix.
    internal const val STOCK_PREF_NAME = "mainconfig"
    internal val STOCK_KEYS: Set<String> = setOf(
        "saveToGallery", "autoplayGifs", "autoplayVideo", "customTabs", "directShare",
        "inappCamera", "fontSize", "bubbleRadius", "ivFontSize", "allowBigEmoji",
        "streamMedia", "saveStreamMedia", "pauseMusicOnRecord", "streamAllVideo", "streamMkv",
        "suggestStickers", "sortContactsByName", "sortFilesByName",
        "loopStickers", "noStatusBar", "disableVoiceAudioEffects", "chatSwipeAction",
        "useThreeLinesLayout", "archiveHidden", "distanceSystemType", "mapPreviewType",
        "repeatMode", "shuffleMusic", "playOrderReversed", "raiseToSpeak",
    )

    internal fun stockPrefs() =
        ApplicationLoader.applicationContext.getSharedPreferences(STOCK_PREF_NAME, Context.MODE_PRIVATE)

    private fun typedKey(key: String, value: Any?): String = when (value) {
        is Long -> key + "_long"
        is Float -> key + "_float"
        else -> key
    }

    private fun stripTypeSuffix(key: String): Pair<String, String> = when {
        key.endsWith("_long") -> Pair(key.removeSuffix("_long"), "long")
        key.endsWith("_float") -> Pair(key.removeSuffix("_float"), "float")
        else -> Pair(key, "")
    }

    sealed class ParseResult {
        data object BadFormat : ParseResult()
        data class Ok(val root: JSONObject, val changed: Int) : ParseResult()
    }

    fun parse(json: String): ParseResult {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return ParseResult.BadFormat
        }
        if (root.optInt("version", -1) != FORMAT_VERSION) return ParseResult.BadFormat
        val values = root.optJSONObject("values") ?: return ParseResult.BadFormat
        val byKey = InuConfig.items.filter { it.exportable }.associateBy { it.key }
        var changed = 0
        for (key in values.keys()) {
            val item = byKey[key] ?: continue
            if (differsFromInu(item, values.get(key))) changed++
        }
        val sub = root.optJSONObject("stock")?.optJSONObject(STOCK_PREF_NAME)
        if (sub != null) {
            val prefs = stockPrefs()
            for (rawKey in sub.keys()) {
                val (key, type) = stripTypeSuffix(rawKey)
                if (key !in STOCK_KEYS) continue
                if (differsFromStock(prefs, key, type, sub.get(rawKey))) changed++
            }
        }
        return ParseResult.Ok(root, changed)
    }

    private fun differsFromInu(item: InuConfig.Item<*>, raw: Any?): Boolean {
        val prefs = InuConfig.prefs
        val key = item.key
        return when (item) {
            is InuConfig.BoolItem -> raw is Boolean && raw != prefs.getBoolean(key, item.default)
            is InuConfig.IntItem -> raw is Number && raw.toInt() != prefs.getInt(key, item.default)
            is InuConfig.LongItem -> raw is Number && raw.toLong() != prefs.getLong(key, item.default)
            is InuConfig.FloatItem -> raw is Number && raw.toFloat() != prefs.getFloat(key, item.default)
            is InuConfig.StringItem -> raw is String && raw != prefs.getString(key, item.default)
            else -> false
        }
    }

    private fun differsFromStock(
        prefs: SharedPreferences,
        key: String,
        type: String,
        raw: Any?,
    ): Boolean {
        if (!prefs.contains(key)) return true
        return when {
            raw is Boolean -> raw != prefs.getBoolean(key, false)
            raw is Number && type == "long" -> raw.toLong() != prefs.getLong(key, 0L)
            raw is Number && type == "float" -> raw.toFloat() != prefs.getFloat(key, 0f)
            raw is Number -> raw.toInt() != prefs.getInt(key, 0)
            raw is String -> raw != prefs.getString(key, null)
            else -> false
        }
    }

    fun export(): String {
        val exportable = InuConfig.items.filter { it.exportable }.map { it.key }.toSet()
        val values = JSONObject()
        for ((k, v) in InuConfig.prefs.all) {
            if (k !in exportable) continue
            when (v) {
                is Boolean, is Int, is Long, is Float, is Double, is String -> values.put(k, v)
                else -> {}
            }
        }
        val sub = JSONObject()
        for ((k, v) in stockPrefs().all) {
            if (k !in STOCK_KEYS) continue
            when (v) {
                is Boolean, is Int, is Long, is Float, is Double, is String -> sub.put(typedKey(k, v), v)
                else -> {}
            }
        }
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exported_at", System.currentTimeMillis())
        root.put("app_version", BuildVars.BUILD_VERSION_STRING)
        root.put("values", values)
        if (sub.length() > 0) root.put("stock", JSONObject().put(STOCK_PREF_NAME, sub))
        return root.toString(2)
    }

    fun apply(parsed: ParseResult.Ok): Int {
        CloudSettingsHelper.restoring = true
        try {
            val root = parsed.root
            val values = root.optJSONObject("values") ?: return 0

            val byKey = InuConfig.items.filter { it.exportable }.associateBy { it.key }
            var applied = 0
            InuConfig.prefs.edit {
                for (key in values.keys()) {
                    val item = byKey[key] ?: continue
                    val raw = values.get(key)
                    val ok = when {
                        item is InuConfig.BoolItem && raw is Boolean -> { putBoolean(key, raw); true }
                        item is InuConfig.IntItem && raw is Number -> { putInt(key, raw.toInt()); true }
                        item is InuConfig.LongItem && raw is Number -> { putLong(key, raw.toLong()); true }
                        item is InuConfig.FloatItem && raw is Number -> { putFloat(key, raw.toFloat()); true }
                        item is InuConfig.StringItem && raw is String -> { putString(key, raw); true }
                        else -> false
                    }
                    if (ok) applied++
                }
            }
            for (item in InuConfig.items) item.load(InuConfig.prefs)

            val sub = root.optJSONObject("stock")?.optJSONObject(STOCK_PREF_NAME) ?: return applied
            stockPrefs().edit {
                for (rawKey in sub.keys()) {
                    val (key, type) = stripTypeSuffix(rawKey)
                    if (key !in STOCK_KEYS) continue
                    val raw = sub.get(rawKey)
                    val ok = when {
                        raw is Boolean -> { putBoolean(key, raw); true }
                        raw is Number && type == "long" -> { putLong(key, raw.toLong()); true }
                        raw is Number && type == "float" -> { putFloat(key, raw.toFloat()); true }
                        raw is Number -> { putInt(key, raw.toInt()); true }
                        raw is String -> { putString(key, raw); true }
                        else -> false
                    }
                    if (ok) applied++
                }
            }
            return applied
        } finally {
            CloudSettingsHelper.restoring = false
        }
    }

    const val FILENAME_SUFFIX = ".inu-settings.json"

    fun startImportFromFile(fragment: BaseFragment, file: File) {
        Utilities.globalQueue.postRunnable {
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            AndroidUtilities.runOnUIThread {
                if (text == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(R.string.InuBackupImportBadFormat)
                    ).show()
                    return@runOnUIThread
                }
                showImportConfirm(fragment, text)
            }
        }
    }

    fun showImportConfirm(fragment: BaseFragment, text: String) {
        val ctx = fragment.context ?: fragment.parentActivity ?: return
        when (val parsed = parse(text)) {
            is ParseResult.BadFormat -> BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuBackupImportBadFormat)
            ).show()

            is ParseResult.Ok -> {
                if (parsed.changed == 0) {
                    BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuBackupImportNoChanges)
                    ).show()
                } else {
                    SettingsImportConfirmSheet(ctx, parsed.changed) {
                        applyAndPromptRestart(fragment, parsed)
                    }.show()
                }
            }
        }
    }

    fun applyAndPromptRestart(fragment: BaseFragment, parsed: ParseResult.Ok) {
        val applied = apply(parsed)
        BulletinFactory.of(fragment).createSimpleBulletin(
            R.raw.chats_infotip,
            LocaleController.formatString(R.string.InuBackupImportSuccess, applied),
            LocaleController.getString(R.string.InuRestartNow)
        ) {
            val activity = fragment.parentActivity ?: return@createSimpleBulletin
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            activity.finishAffinity()
            activity.startActivity(intent)
            exitProcess(0)
        }.show()
    }
}
