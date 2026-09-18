package ai.arena.mobile

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefreshLayout, который не мешает прокрутке страницы.
 *
 * Обычный SwipeRefreshLayout определяет «страница на самом верху» по
 * `webView.scrollY`. У одностраничных приложений (arena.ai) прокрутка часто
 * живёт во внутреннем контейнере: `scrollY` остаётся нулевым, жест забирает
 * SwipeRefreshLayout — и страница перестаёт скроллиться.
 *
 * Поэтому состояние «на самом верху» сообщает сама страница через JS-мост,
 * а жест включается только при явном подтверждении. Пока подтверждения нет,
 * касания полностью принадлежат WebView.
 */
class ArenaSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    private var webViewProvider: (() -> WebView?)? = null

    /** Подтверждение страницы, что она прокручена в самый верх. */
    private var pageAtTop = false

    fun bindWebView(provider: () -> WebView?) {
        webViewProvider = provider
    }

    /** Вызывается из JS-моста при каждой смене положения прокрутки. */
    fun setPageAtTop(atTop: Boolean) {
        pageAtTop = atTop
    }

    /** Сброс состояния при переходе на новую страницу: до подтверждения не перехватываем. */
    fun resetScrollState() {
        pageAtTop = false
    }

    override fun canChildScrollUp(): Boolean {
        val view = webViewProvider?.invoke()
        if (!isEnabled || view == null) return true      // жест выключен — страница скроллится сама
        if (view.scrollY > 0) return true                // страница уже прокручена
        return !pageAtTop                                // ждём подтверждения из JS
    }
}
