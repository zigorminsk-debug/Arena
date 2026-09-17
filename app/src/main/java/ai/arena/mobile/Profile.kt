package ai.arena.mobile

import org.json.JSONObject

/**
 * Профиль аккаунта. Каждый профиль = отдельный процесс приложения + отдельный
 * каталог данных WebView (app_webview_p1 … app_webview_p5),
 * поэтому cookie, localStorage и кэш никогда не пересекаются.
 */
data class Profile(
    val id: String,
    var name: String,
    var color: String,
    var account: String = "",
    var github: String = "",
    var desktopMode: Boolean = false,
    var googleCompat: Boolean = false,
    var keepScreenOn: Boolean = false,
    var lastUrl: String = "",
    var lastUsed: Long = 0L,
    var createdAt: Long = 0L,
) {
    val processNameSuffix: String get() = ":$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("color", color)
        put("account", account)
        put("github", github)
        put("desktopMode", desktopMode)
        put("googleCompat", googleCompat)
        put("keepScreenOn", keepScreenOn)
        put("lastUrl", lastUrl)
        put("lastUsed", lastUsed)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            color = o.optString("color", "#7C5CFF"),
            account = o.optString("account", ""),
            github = o.optString("github", ""),
            desktopMode = o.optBoolean("desktopMode", false),
            googleCompat = o.optBoolean("googleCompat", false),
            keepScreenOn = o.optBoolean("keepScreenOn", false),
            lastUrl = o.optString("lastUrl", ""),
            lastUsed = o.optLong("lastUsed", 0L),
            createdAt = o.optLong("createdAt", 0L),
        )
    }
}
