package ai.arena.mobile

import android.webkit.JavascriptInterface

/**
 * Минимальный мост «страница → приложение». Наружу отдаются только безопасные
 * методы: чтение GitHub-логина (meta[name=user-login]) и положение прокрутки,
 * которое нужно, чтобы pull-to-refresh не отбирал жест у страницы.
 */
class WebBridge(private val host: Host) {

    interface Host {
        fun onGithubLogin(login: String)

        /** true — страница прокручена в самый верх (жест «потянуть вниз» можно перехватывать). */
        fun onPageAtTopChanged(atTop: Boolean)
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
    fun platform(): String = "android"

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    companion object {
        val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

        const val JS_NAME = "ArenaAndroid"

        /** Скрипт читает логин GitHub со страницы, если пользователь авторизован. */
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

        /**
         * Отслеживает прокрутку страницы и сообщает приложению, находится ли она
         * в самом верху. Слушатель вешается в фазе перехвата, поэтому ловит
         * прокрутку и вложенных контейнеров — именно так скроллится arena.ai.
         */
        val SCROLL_SCRIPT: String = """
            (function () {
              if (window.__arenaScrollHook) { return; }
              window.__arenaScrollHook = true;

              var bridge = window.$JS_NAME;
              var lastScroller = null;
              var lastReported = null;

              function windowOffset() {
                if (typeof window.pageYOffset === 'number') { return window.pageYOffset; }
                var root = document.scrollingElement || document.documentElement;
                return root ? root.scrollTop : 0;
              }

              function computeAtTop() {
                if (windowOffset() > 2) { return false; }
                if (lastScroller && lastScroller.scrollTop > 2) { return false; }
                return true;
              }

              function report() {
                var value = computeAtTop();
                if (value === lastReported) { return; }
                lastReported = value;
                try { if (bridge) { bridge.reportScroll(value); } } catch (e) { }
              }

              function onScroll(event) {
                var target = event.target;
                if (!target || target === document || target === window ||
                    target === document.documentElement || target === document.body) {
                  lastScroller = null;          // прокручивается сама страница
                } else if (typeof target.scrollTop === 'number') {
                  lastScroller = target;        // прокручивается внутренний контейнер
                }
                report();
              }

              document.addEventListener('scroll', onScroll, true);   // capture: ловим и вложенные
              window.addEventListener('scroll', onScroll, true);
              window.addEventListener('resize', report, true);
              window.addEventListener('orientationchange', report, true);

              // Ручной возврат наверх — используется пунктом меню «Наверх»
              window.__arenaScrollToTop = function () {
                try { window.scrollTo(0, 0); } catch (e) { }
                try {
                  var root = document.scrollingElement || document.documentElement;
                  if (root) { root.scrollTop = 0; }
                } catch (e) { }
                try { if (lastScroller) { lastScroller.scrollTop = 0; } } catch (e) { }
                report();
              };

              report();
            })();
        """.trimIndent()

        /** Вызов JS-функции возврата наверх (безопасно, если страница ещё не готова). */
        val SCROLL_TOP_CALL: String =
            "(function(){try{if(window.__arenaScrollToTop){window.__arenaScrollToTop();}" +
                "else{window.scrollTo(0,0);}}catch(e){}})();"
    }
}
