package ai.arena.mobile

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Информация о релизе на GitHub. */
data class ReleaseInfo(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val assetUrl: String,
    val pageUrl: String,
    val isDebugAsset: Boolean,
)

/**
 * Самообновление приложения: проверяем последний релиз на GitHub, скачиваем APK
 * через DownloadManager и открываем системный установщик.
 *
 * Работает благодаря постоянному ключу подписи: новая версия ставится поверх
 * установленной, профили и входы сохраняются.
 */
object UpdateChecker {

    private const val REPO = "zigorminsk-debug/Arena"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DOWNLOAD_ID = "download_id"

    /** Автопроверка не чаще одного раза в час. */
    private const val AUTO_CHECK_INTERVAL_MS = 60L * 60L * 1000L

    /** Не запускаем два сетевых запроса из MainActivity и profile Activity одновременно. */
    @Volatile
    private var checkInProgress = false

    private val buildRegex = Regex("""build(\d+)""")
    private val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)*)""")

    // ------------------------------------------------------------------ разбор

    /**
     * Разбор ответа GitHub API. Чистая функция без обращения к Android —
     * покрыта юнит-тестами (см. app/src/test).
     */
    fun parseRelease(json: String): ReleaseInfo? = try {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").removePrefix("v")
        val page = root.optString("html_url").ifEmpty { RELEASES_PAGE }
        val assets = root.optJSONArray("assets")

        var best: ReleaseInfo? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!name.endsWith(".apk", ignoreCase = true) || url.isEmpty()) continue

                val build = buildRegex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val isDebug = name.contains("debug", ignoreCase = true)
                val version = versionRegex.find(name)?.groupValues?.get(1) ?: tag
                val candidate = ReleaseInfo(
                    versionName = version,
                    buildNumber = build,
                    assetName = name,
                    assetUrl = url,
                    pageUrl = page,
                    isDebugAsset = isDebug,
                )

                val current = best
                val better = current == null ||
                    (current.isDebugAsset && !isDebug) ||
                    (current.isDebugAsset == isDebug && build > current.buildNumber)
                if (better) best = candidate
            }
        }
        best
    } catch (t: Throwable) {
        null
    }

    fun isNewer(release: ReleaseInfo, currentVersionCode: Int): Boolean =
        release.buildNumber > currentVersionCode

    // ---------------------------------------------------------------- проверка

    /**
     * @param manual true — пользователь нажал «Проверить обновление» (показываем
     * результат всегда), false — тихая проверка при запуске.
     */
    fun checkAsync(activity: Activity, manual: Boolean) {
        val prefs = prefs(activity)
        synchronized(this) {
            if (checkInProgress) return
            if (!manual) {
                val last = prefs.getLong(KEY_LAST_CHECK, 0L)
                if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
            }
            checkInProgress = true
        }

        Thread {
            try {
                val json = fetch(API_URL)
                val release = json?.let { parseRelease(it) }

                if (json != null) {
                    // Время сохраняем только после ответа GitHub. При сетевой
                    // ошибке старый timestamp не должен блокировать повторную
                    // проверку ещё на час.
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                } else {
                    prefs.edit().remove(KEY_LAST_CHECK).apply()
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    when {
                        release == null ->
                            if (manual) toast(activity, R.string.update_check_failed)

                        isNewer(release, BuildConfig.VERSION_CODE) ->
                            showUpdateDialog(activity, release)

                        manual -> toast(activity, R.string.update_latest)
                    }
                }
            } finally {
                checkInProgress = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ArenaMobile/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    // -------------------------------------------------------------- диалог

    private fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        val channel = if (release.isDebugAsset) "debug" else "release"
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, release.versionName))
            .setMessage(
                activity.getString(
                    R.string.update_available_message,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    release.buildNumber,
                    channel,
                )
            )
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(activity, release) }
            .setNeutralButton(R.string.update_open_github) { _, _ -> openUrl(activity, release.pageUrl) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------ загрузка

    fun startDownload(activity: Activity, release: ReleaseInfo) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(release.assetUrl)).apply {
                setTitle(release.assetName)
                setDescription(activity.getString(R.string.app_name))
                setMimeType(APK_MIME)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Приложение не должно требовать WRITE_EXTERNAL_STORAGE только
                // ради обновления. DownloadManager выдаёт content:// URI, из
                // которого системный установщик прочитает APK.
                setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    release.assetName,
                )
            }
            val id = manager.enqueue(request)
            prefs(activity).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
            toast(activity, R.string.update_download_started)
        } catch (t: Throwable) {
            // Не открываем HTML-страницу как запасной файл: на устройствах
            // без браузера это превращается в «нет приложения для ссылки».
            // Пользователь может отдельно выбрать «Открыть на GitHub».
            toast(activity, R.string.update_download_failed)
        }
    }

    /**
     * Если обновление уже скачано — открываем установщик. Вызывается при
     * возвращении в приложение (в фоне доигрывает загрузка DownloadManager).
     */
    fun installPendingIfReady(activity: Activity) {
        val prefs = prefs(activity)
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0L) return

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = -1
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (index >= 0) status = cursor.getInt(index)
                }
            }
        } catch (t: Throwable) {
            status = -1
        }

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                val uri = try {
                    manager.getUriForDownloadedFile(id)
                } catch (t: Throwable) {
                    null
                }
                if (uri != null) {
                    launchInstaller(activity, uri)
                } else {
                    toast(activity, R.string.update_download_failed)
                }
            }

            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                toast(activity, R.string.update_download_failed)
            }

            else -> Unit // ещё качается — проверим при следующем возвращении
        }
    }

    private fun launchInstaller(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            // Не подменяем APK HTML-страницей релиза. Кнопка «Открыть на
            // GitHub» в диалоге остаётся отдельным явным действием.
            toast(activity, R.string.update_download_failed)
        }
    }

    // -------------------------------------------------------------- утилиты

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun toast(activity: Activity, resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
    }
}
