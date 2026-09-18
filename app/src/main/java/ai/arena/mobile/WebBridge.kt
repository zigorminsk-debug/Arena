package ai.arena.mobile

import android.webkit.JavascriptInterface

/**
 * Минимальный мост «страница → приложение». Наружу отдаются только безопасные
 * методы: чтение GitHub-логина, положение прокрутки и результат подстановки
 * текста из «Поделиться».
 */
class WebBridge(private val host: Host) {

    interface Host {
        fun onGithubLogin(login: String)

        /** true — страница прокручена в самый верх (жест «потянуть вниз» можно перехватывать). */
        fun onPageAtTopChanged(atTop: Boolean)

        /** Результат попытки подставить присланный извне текст в поле ввода. */
        fun onSharedTextResult(injected: Boolean)
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

        /**
         * Подставляет текст, присланный через «Поделиться», в поле ввода Arena.
         * Поле появляется не сразу, поэтому скрипт повторяет попытки и сообщает
         * результат в приложение: удалось ли вставить текст.
         *
         * @param quotedText текст, уже превращённый в JS-литерал (JSONObject.quote).
         */
        fun sharedTextScript(quotedText: String): String = """
            (function (text) {
              if (!text) { return; }
              var bridge = window.$JS_NAME;
              var attempts = 0;
              var done = false;

              function report(injected) {
                if (done) { return; }
                done = true;
                try { if (bridge) { bridge.reportSharedText(injected); } } catch (e) { }
              }

              function setNativeValue(el, value) {
                try {
                  var isArea = (el.tagName === 'TEXTAREA');
                  var proto = isArea ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                  var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (descriptor && descriptor.set) { descriptor.set.call(el, value); } else { el.value = value; }
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return el.value === value;
                } catch (e) {
                  return false;
                }
              }

              function attempt() {
                attempts++;
                try {
                  var field = document.querySelector('textarea:not([readonly]):not([disabled])');
                  if (field && setNativeValue(field, text)) {
                    field.focus();
                    report(true);
                    return true;
                  }
                  var editable = document.querySelector('[contenteditable="true"]');
                  if (editable) {
                    editable.focus();
                    var inserted = false;
                    try { inserted = document.execCommand('insertText', false, text); } catch (e) { inserted = false; }
                    if (!inserted) {
                      editable.textContent = text;
                      editable.dispatchEvent(new Event('input', { bubbles: true }));
                      inserted = true;
                    }
                    if (inserted) { report(true); return true; }
                  }
                } catch (e) { }
                if (attempts >= 20) { report(false); return true; }
                return false;
              }

              if (!attempt()) {
                var timer = setInterval(function () {
                  if (attempt()) { clearInterval(timer); }
                }, 700);
              }
            })($quotedText);
        """.trimIndent()
    }
}
