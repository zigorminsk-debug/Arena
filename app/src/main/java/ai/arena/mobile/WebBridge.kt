package ai.arena.mobile

import android.webkit.JavascriptInterface

/**
 * Минимальный мост «страница → приложение». Наружу отдаются только безопасные
 * методы: логин GitHub, положение прокрутки и результат подстановки текста из
 * «Поделиться». Скрипты живут в assets/js (см. Scripts).
 */
class WebBridge(private val host: Host) {

    interface Host {
        fun onGithubLogin(login: String)

        /** true — страница прокручена в самый верх (жест «потянуть вниз» можно перехватывать). */
        fun onPageAtTopChanged(atTop: Boolean)

        /** Результат попытки подставить присланный извне текст в поле ввода. */
        fun onSharedTextResult(injected: Boolean)

        /** Черновик восстановлен после перерисовки страницы (например, при повороте). */
        fun onDraftRestored(textRestored: Boolean, filesRestored: Int)
    }

    @JavascriptInterface
    fun reportGithub(login: String?) {
        val clean = login?.trim().orEmpty()
        if (clean.isEmpty() || clean.length > 39) return
        if (!clean.matches(GITHUB_LOGIN)) return
        host.onGithubLogin(clean)
    }

    @JavascriptInterface
    fun reportScroll(atTop: Boolean) {
        host.onPageAtTopChanged(atTop)
    }

    @JavascriptInterface
    fun reportSharedText(injected: Boolean) {
        host.onSharedTextResult(injected)
    }

    @JavascriptInterface
    fun reportDraftRestored(textRestored: Boolean, filesRestored: Int) {
        val files = filesRestored.coerceIn(0, 20)
        host.onDraftRestored(textRestored, files)
    }

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    companion object {
        val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

        const val JS_NAME = "ArenaAndroid"
    }
}
