package ai.arena.mobile

import android.webkit.JavascriptInterface

/**
 * Минимальный мост «страница → приложение». Наружу отдаём единственный
 * безопасный метод: чтение GitHub-логина со страницы github.com
 * (meta[name=user-login]) — чтобы показывать, к какому аккаунту подключён профиль.
 */
class WebBridge(private val host: Host) {

    interface Host {
        fun onGithubLogin(login: String)
    }

    @JavascriptInterface
    fun reportGithub(login: String?) {
        val clean = login?.trim().orEmpty()
        if (clean.isEmpty() || clean.length > 39) return
        if (!clean.matches(GITHUB_LOGIN)) return
        host.onGithubLogin(clean)
    }

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    companion object {
        val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

        /** Скрипт читает логин GitHub со страницы, если пользователь авторизован. */
        const val JS_NAME = "ArenaAndroid"

        val PROBE_SCRIPT: String = """
            (function () {
              try {
                var bridge = window.$JS_NAME;
                if (!bridge) { return; }
                var meta = document.querySelector('meta[name="user-login"]');
                if (meta && meta.content) { bridge.reportGithub(meta.content); }
              } catch (e) { }
            })();
        """.trimIndent()
    }
}
