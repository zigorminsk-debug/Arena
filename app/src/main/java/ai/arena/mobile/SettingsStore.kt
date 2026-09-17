package ai.arena.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File

data class AppSettings(
    val lightTheme: Boolean = false,
    val pinchZoom: Boolean = false,
)

/** Настройки всего приложения — тоже в JSON, чтобы их видели все процессы. */
object SettingsStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "settings.json")

    @Synchronized
    fun read(ctx: Context): AppSettings = try {
        val f = file(ctx)
        if (!f.exists()) {
            AppSettings()
        } else {
            val json = JSONObject(f.readText())
            AppSettings(
                lightTheme = json.optBoolean("lightTheme", false),
                pinchZoom = json.optBoolean("pinchZoom", false),
            )
        }
    } catch (t: Throwable) {
        AppSettings()
    }

    @Synchronized
    fun write(ctx: Context, settings: AppSettings) {
        try {
            val json = JSONObject()
                .put("lightTheme", settings.lightTheme)
                .put("pinchZoom", settings.pinchZoom)
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
}
