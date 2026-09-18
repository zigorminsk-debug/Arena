package ai.arena.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Резервная копия настроек и профилей (без cookies — их не переносим сознательно).
 * Полезно после переустановки приложения: список аккаунтов восстанавливается
 * одной кнопкой, а не вручную.
 */
object BackupManager {

    const val MIME = "application/json"
    const val DEFAULT_FILE_NAME = "arena-mobile-backup.json"

    private const val KIND = "arena-mobile-backup"
    private const val FORMAT_VERSION = 1

    data class BackupData(
        val profiles: List<Profile>,
        val settings: AppSettings?,
        val exportedAt: Long,
        val formatVersion: Int,
    )

    data class ImportResult(
        val profilesRestored: Int,
        val settingsRestored: Boolean,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    // ------------------------------------------------------- чистые функции

    fun serialize(profiles: List<Profile>, settings: AppSettings, exportedAt: Long = System.currentTimeMillis()): String {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        val settingsJson = JSONObject()
            .put("themeMode", settings.themeMode)
            .put("pinchZoom", settings.pinchZoom)
            .put("pullToRefresh", settings.pullToRefresh)
            .put("openLastProfile", settings.openLastProfile)
            .put("keepSession", settings.keepSession)
            .put("appLock", settings.appLock)
        return JSONObject()
            .put("kind", KIND)
            .put("version", FORMAT_VERSION)
            .put("exportedAt", exportedAt)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("profiles", array)
            .put("settings", settingsJson)
            .toString(2)
    }

    fun parse(json: String): BackupData? = try {
        val root = JSONObject(json)
        if (root.optString("kind") != KIND) {
            null
        } else {
            val array = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Profile.fromJson(it) }
            }.filter { it.id.isNotEmpty() }

            val settingsJson = root.optJSONObject("settings")
            val settings = settingsJson?.let {
                AppSettings(
                    themeMode = it.optString("themeMode", SettingsStore.THEME_SYSTEM),
                    pinchZoom = it.optBoolean("pinchZoom", false),
                    pullToRefresh = it.optBoolean("pullToRefresh", false),
                    openLastProfile = it.optBoolean("openLastProfile", false),
                    keepSession = it.optBoolean("keepSession", true),
                    appLock = it.optBoolean("appLock", false),
                )
            }

            BackupData(
                profiles = profiles,
                settings = settings,
                exportedAt = root.optLong("exportedAt", 0L),
                formatVersion = root.optInt("version", 1),
            )
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Сливает резервную копию с текущими профилями: у существующих профилей
     * обновляются имена, цвета и метаданные, новые добавляются, если есть слот.
     */
    fun merge(existing: List<Profile>, incoming: List<Profile>): List<Profile> {
        val result = existing.toMutableList()
        incoming.forEach { candidate ->
            val index = result.indexOfFirst { it.id == candidate.id }
            if (index >= 0) {
                val current = result[index]
                result[index] = candidate.copy(
                    lastUsed = current.lastUsed,
                    lastUrl = current.lastUrl.ifBlank { candidate.lastUrl },
                )
            } else if (result.size < ProfileStore.IDS.size && candidate.id in ProfileStore.IDS) {
                result.add(candidate)
            }
        }
        result.sortBy { ProfileStore.IDS.indexOf(it.id).let { index -> if (index < 0) 99 else index } }
        return result
    }

    // ---------------------------------------------------------- работа с UI

    fun exportText(ctx: Context): String =
        serialize(ProfileStore.all(ctx), SettingsStore.read(ctx))

    fun importText(ctx: Context, json: String): ImportResult {
        val data = parse(json)
            ?: return ImportResult(0, false, ctx.getString(R.string.backup_error_format))

        val merged = merge(ProfileStore.all(ctx), data.profiles)
        var restored = 0
        merged.forEach { profile ->
            ProfileStore.update(ctx, profile)
            restored++
        }
        data.settings?.let { SettingsStore.write(ctx, it) }

        return ImportResult(
            profilesRestored = restored,
            settingsRestored = data.settings != null,
        )
    }
}
