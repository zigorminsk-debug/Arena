package ai.arena.mobile

import android.content.Context

/**
 * JS-скрипты лежат в assets/js — так их можно проверять линтером (node --check)
 * и править как обычные файлы, а не как строки внутри Kotlin.
 * Содержимое кэшируется в памяти процесса.
 */
object Scripts {

    const val GITHUB_PROBE = "github_probe.js"
    const val SCROLL_TRACKER = "scroll_tracker.js"
    const val SCROLL_TO_TOP = "scroll_to_top.js"
    const val SHARED_TEXT = "shared_text.js"
    const val COMPOSER_KEEPER = "composer_keeper.js"

    private val cache = HashMap<String, String>()

    @Synchronized
    fun load(context: Context, name: String): String {
        cache[name]?.let { return it }
        val text = try {
            context.assets.open("js/$name").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            ""
        }
        cache[name] = text
        return text
    }

    /** Вызов функции подстановки текста (безопасно, если страница её не определяла). */
    fun sharedTextCall(quotedText: String): String =
        "(function(){try{if(window.__arenaInjectText){window.__arenaInjectText($quotedText);}" +
            "else{window.scrollTo(0,0);}}catch(e){}})();"
}
