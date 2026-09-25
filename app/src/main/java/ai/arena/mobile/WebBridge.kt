package ai.arena.mobile

import android.webkit.JavascriptInterface

/**
 * Минимальный мост «страница → приложение». Наружу отдаются только безопасные
 * методы: логин GitHub, положение прокрутки, результат подстановки текста из
 * «Поделиться» и передача blob-загрузок. Скрипты живут в assets/js (см. Scripts).
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

        /** WebView передал содержимое blob://-загрузки в формате data URL. */
        fun onBlobDownload(dataUrl: String, suggestedName: String?, mimeType: String?)

        /** WebView не смог прочитать blob://-ссылку. */
        fun onBlobDownloadFailed()
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

    /**
     * DownloadManager принимает только http(s), а браузерные приложения часто
     * создают ссылки на локальный blob://. JS передаёт такой blob как data URL,
     * после чего Activity сохраняет байты в Downloads.
     */
    @JavascriptInterface
    fun reportBlobDownload(dataUrl: String?, suggestedName: String?, mimeType: String?) {
        val data = dataUrl?.trim().orEmpty()
        val comma = data.indexOf(',')
        if (!data.startsWith("data:") || comma <= "data:".length || comma == data.lastIndex) {
            host.onBlobDownloadFailed()
            return
        }
        host.onBlobDownload(data, suggestedName?.trim(), mimeType?.trim())
    }

    @JavascriptInterface
    fun reportBlobDownloadFailed() {
        host.onBlobDownloadFailed()
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
