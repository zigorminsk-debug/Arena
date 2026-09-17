package ai.arena.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Адреса Arena, используемые в меню. */
object Links {
    const val HOME = "https://arena.ai/"
    const val AGENT = "https://arena.ai/agent"
    const val LEADERBOARD = "https://arena.ai/leaderboard/text"
    const val HELP = "https://help.arena.ai/"
}

object WebUtils {

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Обычный режим — штатный User-Agent WebView.
     * desktopMode — убираем «Mobile», сайт отдаёт десктопную вёрстку.
     * googleCompat — прячем маркеры WebView, чтобы провайдеры входа
     * (Google/GitHub) не отказывали в авторизации.
     */
    fun userAgent(defaultUserAgent: String?, profile: Profile): String {
        val base = defaultUserAgent?.takeIf { it.isNotBlank() } ?: FALLBACK_UA
        var ua = base
        if (profile.googleCompat) {
            ua = ua.replace("; wv", "").replace("Version/4.0 ", "")
        }
        if (profile.desktopMode) {
            ua = ua.replace(" Mobile Safari", " Safari").replace("Mobile Safari", "Safari")
        }
        return ua
    }
}

fun parseColorSafe(hex: String?, fallback: Int = 0xFF7C5CFF.toInt()): Int = try {
    Color.parseColor(if (hex.isNullOrBlank()) "#7C5CFF" else hex)
} catch (t: Throwable) {
    fallback
}

fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun ringDrawable(color: Int, strokeDp: Int, density: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(Color.TRANSPARENT)
    setStroke((strokeDp * density).toInt(), color)
}

fun profileInitial(profile: Profile): String =
    profile.name.trim().take(1).uppercase().ifEmpty { profile.id.removePrefix("p") }

/**
 * Строка-подпись профиля: аккаунт Arena и/или GitHub.
 * Используется и в списке профилей, и в шторке переключения.
 */
fun profileSubtitle(context: Context, profile: Profile): String {
    val parts = mutableListOf<String>()
    parts.add(profile.account.ifBlank { context.getString(R.string.profile_account_unknown) })
    if (profile.github.isNotBlank()) {
        parts.add(context.getString(R.string.github_connected, profile.github))
    }
    return parts.joinToString(" · ")
}
