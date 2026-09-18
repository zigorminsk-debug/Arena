package ai.arena.mobile

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Сохранение входа между запусками.
 *
 * Проблема: WebView держит cookies сессии (без срока жизни) в памяти, а пишет
 * их на диск с задержкой. Профиль живёт в отдельном процессе, который Android
 * убивает сразу при закрытии приложения — незаписанные cookies теряются, и сайт
 * снова просит пароль. Браузеры для этого включают «восстановление сессии»,
 * в WebView такого флага нет.
 *
 * Решение из двух частей:
 *  1. flush() — принудительно сбрасываем cookie-jar на диск при сворачивании;
 *  2. persistAuthCookies() — cookies авторизации arena.ai переписываем с длинным
 *     Max-Age, чтобы они гарантированно стали постоянными.
 *
 * Дополнительно ведём «снимок» cookies: при закрытии профиля записываем список
 * имён, при следующем открытии сравниваем — это даёт честный ответ, пережил ли
 * вход перезапуск (см. экран «Диагностика»).
 */
object SessionKeeper {

    const val ARENA_URL = "https://arena.ai/"
    private const val SNAPSHOT_PREFIX = "session_"
    private const val PERSIST_MAX_AGE_SECONDS = 31_536_000 // 1 год

    /** Части имён cookies, которые считаем относящимися к авторизации. */
    private val AUTH_NAME_PARTS = listOf("auth", "session", "token", "jwt", "login")

    data class CookiePair(val name: String, val value: String)

    data class Snapshot(val names: List<String>, val authPresent: Boolean, val savedAt: Long)

    data class SnapshotDiff(
        val hasPrevious: Boolean,
        val previousCount: Int,
        val currentCount: Int,
        val missing: List<String>,
        val lostAuth: Boolean,
    )

    // --------------------------------------------------------------- базовое

    /** Принудительная запись cookie-jar на диск. */
    fun flush() {
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            // WebView недоступен в этом процессе — нечего сбрасывать
        }
    }

    fun parseCookieHeader(header: String?): List<CookiePair> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else CookiePair(name, value)
        }
    }

    fun isAuthCookieName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return AUTH_NAME_PARTS.any { lower.contains(it) }
    }

    /** Заголовок для продления cookie на год. */
    fun persistentCookieHeader(
        name: String,
        value: String,
        maxAgeSeconds: Int = PERSIST_MAX_AGE_SECONDS,
    ): String = "$name=$value; Path=/; Max-Age=$maxAgeSeconds; Secure; SameSite=Lax"

    /** Заголовок, удаляющий возможный дубликат с Domain=.arena.ai. */
    fun expireDomainCookieHeader(name: String): String =
        "$name=; Path=/; Domain=.arena.ai; Max-Age=0"

    fun cookieNames(url: String = ARENA_URL): List<String> =
        parseCookieHeader(getCookie(url)).map { it.name }

    fun authCookieNames(url: String = ARENA_URL): List<String> =
        cookieNames(url).filter { isAuthCookieName(it) }

    fun hasAuthCookie(): Boolean = authCookieNames().isNotEmpty()

    private fun getCookie(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------- продление

    /**
     * Переписывает cookies авторизации arena.ai с длинным сроком жизни.
     * Возвращает количество продлённых cookies.
     */
    fun persistAuthCookies(): Int {
        val manager = try {
            CookieManager.getInstance()
        } catch (t: Throwable) {
            return 0
        }
        val cookies = parseCookieHeader(getCookie(ARENA_URL))
        if (cookies.isEmpty()) return 0

        var count = 0
        cookies.forEach { cookie ->
            if (!isAuthCookieName(cookie.name)) return@forEach
            try {
                manager.setCookie(ARENA_URL, expireDomainCookieHeader(cookie.name))
                manager.setCookie(ARENA_URL, persistentCookieHeader(cookie.name, cookie.value))
                count++
            } catch (t: Throwable) {
                // ignore
            }
        }
        if (count > 0) flush()
        return count
    }

    // ------------------------------------------------------------- снимок

    private fun snapshotFile(ctx: Context, profileId: String) =
        File(ctx.filesDir, "$SNAPSHOT_PREFIX$profileId.json")

    /** Запоминает состояние cookies перед закрытием профиля. */
    fun saveSnapshot(ctx: Context, profileId: String) {
        try {
            val names = cookieNames()
            val json = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("auth", hasAuthCookie())
                .put("names", JSONArray(names))
            snapshotFile(ctx, profileId).writeText(json.toString())
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun loadSnapshot(ctx: Context, profileId: String): Snapshot? = try {
        val file = snapshotFile(ctx, profileId)
        if (!file.exists()) {
            null
        } else {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("names")
            val names = if (array == null) emptyList() else
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
            Snapshot(
                names = names,
                authPresent = json.optBoolean("auth", false),
                savedAt = json.optLong("savedAt", 0L),
            )
        }
    } catch (t: Throwable) {
        null
    }

    /** Сравнивает текущие cookies со снимком: что потерялось после перезапуска. */
    fun diffWithSnapshot(ctx: Context, profileId: String): SnapshotDiff {
        val previous = loadSnapshot(ctx, profileId)
        val current = cookieNames()
        if (previous == null) {
            return SnapshotDiff(
                hasPrevious = false,
                previousCount = 0,
                currentCount = current.size,
                missing = emptyList(),
                lostAuth = false,
            )
        }
        val missing = previous.names.filterNot { current.contains(it) }
        return SnapshotDiff(
            hasPrevious = true,
            previousCount = previous.names.size,
            currentCount = current.size,
            missing = missing,
            lostAuth = previous.authPresent && !hasAuthCookie(),
        )
    }
}
