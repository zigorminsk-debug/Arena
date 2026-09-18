package ai.arena.mobile

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Факт применения суффикса каталога данных (иначе профили делили бы cookies). */
object WebViewState {
    @Volatile
    var suffixApplied: Boolean = false
        private set

    @Volatile
    var suffixError: String? = null
        private set

    fun markApplied() {
        suffixApplied = true
        suffixError = null
    }

    fun markFailed(reason: String) {
        suffixApplied = false
        suffixError = reason
    }
}

/** Собранная информация о состоянии профиля — показывается на экране «Диагностика». */
data class DiagnosticReport(
    val profileId: String,
    val profileName: String,
    val inProfileProcess: Boolean,
    val suffixApplied: Boolean,
    val suffixError: String?,
    val dataDirPath: String,
    val dataDirExists: Boolean,
    val dataSizeBytes: Long,
    val cookieCount: Int,
    val cookieNames: List<String>,
    val authCookiePresent: Boolean,
    val sessionDiff: SessionKeeper.SnapshotDiff?,
    val keepSessionEnabled: Boolean,
    val webViewPackage: String,
    val webViewVersion: String?,
    val desktopMode: Boolean,
    val signInCompat: Boolean,
    val thirdPartyCookies: Boolean,
    val arenaStatus: String,
    val githubStatus: String,
    val githubApiStatus: String,
    val checkedAt: Long,
)

/**
 * Сбор диагностики подключения: сеть, cookies, каталог данных, версия WebView.
 * Отвечает на вопросы «почему не сохраняется вход» и «почему не подключается GitHub».
 */
object Diagnostics {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    fun collect(
        ctx: Context,
        profile: Profile,
        inProfileProcess: Boolean,
    ): DiagnosticReport {
        val dirs = ProfileStore.profileDirs(ctx, profile.id)
        val primary = dirs.firstOrNull()

        val cookieNames = if (inProfileProcess) SessionKeeper.cookieNames() else emptyList()
        val authPresent = inProfileProcess && SessionKeeper.hasAuthCookie()
        val diff = if (inProfileProcess) SessionKeeper.diffWithSnapshot(ctx, profile.id) else null
        val settings = SettingsStore.read(ctx)

        return DiagnosticReport(
            profileId = profile.id,
            profileName = profile.name,
            inProfileProcess = inProfileProcess,
            suffixApplied = if (inProfileProcess) WebViewState.suffixApplied else false,
            suffixError = WebViewState.suffixError,
            dataDirPath = primary?.absolutePath ?: "—",
            dataDirExists = primary?.exists() == true,
            dataSizeBytes = ProfileStore.dataSize(ctx, profile.id),
            cookieCount = cookieNames.size,
            cookieNames = cookieNames,
            authCookiePresent = authPresent,
            sessionDiff = diff,
            keepSessionEnabled = settings.keepSession,
            webViewPackage = packageLabel(ctx),
            webViewVersion = packageVersion(ctx),
            desktopMode = profile.desktopMode,
            signInCompat = profile.googleCompat,
            thirdPartyCookies = true,
            arenaStatus = httpStatus(Links.HOME),
            githubStatus = httpStatus("https://github.com/"),
            githubApiStatus = httpStatus("https://api.github.com/"),
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun packageLabel(ctx: Context): String = try {
        WebViewCompat.getCurrentWebViewPackage(ctx)?.packageName ?: "—"
    } catch (t: Throwable) {
        "—"
    }

    private fun packageVersion(ctx: Context): String? = try {
        WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName
    } catch (t: Throwable) {
        null
    }

    private fun httpStatus(url: String): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ArenaMobile/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code in 200..399) "OK ($code)" else "ответ $code"
        } catch (t: Throwable) {
            "недоступно (${t.javaClass.simpleName})"
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /** Текстовый отчёт: его можно скопировать и прислать в поддержку. */
    fun render(ctx: Context, report: DiagnosticReport): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(report.checkedAt))
        val builder = StringBuilder()

        builder.appendLine("Arena Mobile ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})")
        builder.appendLine("Проверено: $time")
        builder.appendLine("Профиль: ${report.profileName} (${report.profileId})")
        builder.appendLine()
        builder.appendLine("— Процесс и данные —")
        builder.appendLine("Каталог данных: ${report.dataDirPath}")
        builder.appendLine("Каталог существует: ${mark(report.dataDirExists)}")
        builder.appendLine("Изоляция профилей (суффикс WebView): ${mark(report.suffixApplied)}")
        report.suffixError?.let { builder.appendLine("  ошибка: $it") }
        builder.appendLine("Размер данных: ${formatDataSize(ctx, report.dataSizeBytes)}")
        builder.appendLine("Версия WebView: ${report.webViewVersion ?: "—"} (${report.webViewPackage})")
        builder.appendLine()

        if (report.inProfileProcess) {
            builder.appendLine("— Cookies (${report.cookieCount}) —")
            builder.appendLine("Вход в Arena: ${if (report.authCookiePresent) "сохранён" else "не найден"}")
            builder.appendLine("Продление сессии включено: ${mark(report.keepSessionEnabled)}")
            report.sessionDiff?.let { diff ->
                if (!diff.hasPrevious) {
                    builder.appendLine("Снимок сессии: ещё не сохранялся")
                } else {
                    builder.appendLine(
                        "С прошлого закрытия: было ${diff.previousCount}, сейчас ${diff.currentCount}"
                    )
                    if (diff.missing.isNotEmpty()) {
                        builder.appendLine("Потеряны: ${diff.missing.joinToString(", ")}")
                    }
                    if (diff.lostAuth) {
                        builder.appendLine("ВНИМАНИЕ: cookie авторизации не пережили перезапуск")
                    }
                }
            }
            if (report.cookieNames.isNotEmpty()) {
                builder.appendLine("Имена: ${report.cookieNames.joinToString(", ")}")
            }
            builder.appendLine()
        } else {
            builder.appendLine("— Cookies —")
            builder.appendLine("Раздел доступен только внутри профиля")
            builder.appendLine()
        }

        builder.appendLine("— Сеть —")
        builder.appendLine("arena.ai: ${report.arenaStatus}")
        builder.appendLine("github.com: ${report.githubStatus}")
        builder.appendLine("api.github.com: ${report.githubApiStatus}")
        builder.appendLine()

        builder.appendLine("— Режимы профиля —")
        builder.appendLine("Версия для ПК: ${mark(report.desktopMode)}")
        builder.appendLine("Режим совместимости входа: ${mark(report.signInCompat)}")
        builder.appendLine("Сторонние cookies разрешены: ${mark(report.thirdPartyCookies)}")
        builder.appendLine()

        val hints = hints(report)
        if (hints.isNotEmpty()) {
            builder.appendLine("— Что можно сделать —")
            hints.forEach { builder.appendLine("• $it") }
        }

        return builder.toString()
    }

    /** Подсказки под конкретную проблему — без них отчёт бесполезен. */
    fun hints(report: DiagnosticReport): List<String> {
        val hints = mutableListOf<String>()

        if (!report.suffixApplied && report.inProfileProcess) {
            hints.add("Изоляция профилей не включилась — перезапустите приложение (профили могут делить cookies)")
        }
        if (report.inProfileProcess && !report.authCookiePresent) {
            hints.add("Войдите в Arena заново — сейчас cookies авторизации нет")
        }
        if (report.sessionDiff?.lostAuth == true) {
            hints.add("Вход не сохранился между запусками: включите «Продление сессии» и войдите заново")
        }
        if (!report.arenaStatus.startsWith("OK")) {
            hints.add("arena.ai недоступен из сети — проверьте соединение или VPN")
        }
        if (!report.githubStatus.startsWith("OK") || !report.githubApiStatus.startsWith("OK")) {
            hints.add("GitHub недоступен из сети — подключение репозиториев не сработает")
        }
        if (report.signInCompat.not() && report.inProfileProcess && !report.authCookiePresent) {
            hints.add("Попробуйте «Режим совместимости входа» в настройках профиля")
        }
        if (hints.isEmpty()) {
            hints.add("Проблем не видно: сеть доступна, cookies на месте")
        }
        return hints
    }

    private fun mark(value: Boolean): String = if (value) "да" else "нет"

    /** Короткая подпись состояния для карточки профиля. */
    fun statusLine(ctx: Context, report: DiagnosticReport): String = when {
        !report.inProfileProcess -> ctx.getString(R.string.diag_in_profile_only)
        !report.authCookiePresent -> ctx.getString(R.string.diag_no_auth_cookie)
        report.sessionDiff?.lostAuth == true -> ctx.getString(R.string.diag_session_lost)
        else -> ctx.getString(R.string.diag_ok)
    }

    /** Хост текущего URL (для проверок). */
    fun hostOf(url: String?): String? = try {
        Uri.parse(url ?: "").host
    } catch (t: Throwable) {
        null
    }

    /** Небольшая утилита: считать, что страница загрузилась успешно. */
    fun looksLikeLoginPage(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        if (!host.endsWith("arena.ai")) return false
        return (url ?: "").contains("login", ignoreCase = true) ||
            (url ?: "").contains("signin", ignoreCase = true)
    }

    /** Сериализация в JSON — на случай, если понадобится приложить отчёт к письму. */
    fun toJson(report: DiagnosticReport): String = JSONObject().apply {
        put("app", BuildConfig.VERSION_NAME)
        put("build", BuildConfig.VERSION_CODE)
        put("profile", report.profileId)
        put("suffixApplied", report.suffixApplied)
        put("cookieCount", report.cookieCount)
        put("authCookie", report.authCookiePresent)
        put("arena", report.arenaStatus)
        put("github", report.githubStatus)
        put("api", report.githubApiStatus)
        put("webView", report.webViewVersion)
    }.toString()
}
