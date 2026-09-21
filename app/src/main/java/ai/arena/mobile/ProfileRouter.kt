package ai.arena.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Переключение между профилями — каждый профиль это своя Activity в своём процессе. */
object ProfileRouter {

    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_URL = "url"
    const val EXTRA_SHARED_TEXT = "shared_text"
    /** Загрузить Arena заново с cookie выбранного профиля. */
    const val EXTRA_AUTO_LOGIN = "auto_login"

    fun activityClass(id: String): Class<out Activity> = when (id) {
        "p1" -> ProfileActivity1::class.java
        "p2" -> ProfileActivity2::class.java
        "p3" -> ProfileActivity3::class.java
        "p4" -> ProfileActivity4::class.java
        else -> ProfileActivity5::class.java
    }

    fun intent(context: Context, id: String, url: String? = null): Intent =
        Intent(context, activityClass(id)).apply {
            // Не накапливаем по экземпляру одной Activity при каждом
            // переключении: возвращаем уже открытый профиль на передний план.
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_PROFILE_ID, id)
            if (!url.isNullOrBlank()) {
                putExtra(EXTRA_URL, url)
            } else {
                // Переход без конкретной страницы означает смену профиля:
                // открыть Arena с cookie именно этого профиля.
                putExtra(EXTRA_AUTO_LOGIN, true)
            }
        }

    fun open(context: Context, id: String, url: String? = null) {
        val intent = intent(context, id, url)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Быстрое переключение внутри приложения: открываем новый профиль и закрываем текущий. */
    @Suppress("DEPRECATION")
    fun switch(activity: Activity, id: String, url: String? = null) {
        // onStop() обычно успевает выполниться, но Android может начать новый
        // процесс раньше. Сохраняем cookie текущего профиля до запуска target.
        (activity as? BaseProfileActivity)?.persistSessionBeforeLeaving()

        activity.startActivity(intent(activity, id, url))
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        activity.finish()
    }

    /** Возврат к списку профилей. */
    fun openChooser(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
