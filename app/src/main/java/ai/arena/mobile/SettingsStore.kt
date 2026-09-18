package ai.arena.mobile

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import java.io.File

data class AppSettings(
    /** "system" | "light" | "dark" */
    val themeMode: String = SettingsStore.THEME_SYSTEM,
    val pinchZoom: Boolean = false,
    /**
     * Обновление жестом «потянуть вниз». По умолчанию выключено: на страницах
     * с собственной областью прокрутки жест может отбирать касания у страницы.
     */
    val pullToRefresh: Boolean = false,
)

/** Настройки всего приложения — в JSON, чтобы их видели все процессы. */
object SettingsStore {

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun file(ctx: Context) = File(ctx.filesDir, "settings.json")

    @Synchronized
    fun read(ctx: Context): AppSettings = try {
        val f = file(ctx)
        if (!f.exists()) {
            AppSettings()
        } else {
            val json = JSONObject(f.readText())
            val stored = json.optString("themeMode")
            val mode = when {
                stored == THEME_LIGHT || stored == THEME_DARK || stored == THEME_SYSTEM -> stored
                // обратная совместимость со старой настройкой «светлая тема»
                json.has("lightTheme") ->
                    if (json.optBoolean("lightTheme", false)) THEME_LIGHT else THEME_DARK

                else -> THEME_SYSTEM
            }
            AppSettings(
                themeMode = mode,
                pinchZoom = json.optBoolean("pinchZoom", false),
                pullToRefresh = json.optBoolean("pullToRefresh", false),
            )
        }
    } catch (t: Throwable) {
        AppSettings()
    }

    @Synchronized
    fun write(ctx: Context, settings: AppSettings) {
        try {
            val json = JSONObject()
                .put("themeMode", settings.themeMode)
                .put("pinchZoom", settings.pinchZoom)
                .put("pullToRefresh", settings.pullToRefresh)
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Применяет выбранную тему ко всему приложению. */
    fun applyTheme(settings: AppSettings) {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.themeMode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyStoredTheme(ctx: Context) = applyTheme(read(ctx))
}
