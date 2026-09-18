package ai.arena.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Переключение между профилями — каждый профиль это своя Activity в своём процессе. */
object ProfileRouter {

    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_URL = "url"
    const val EXTRA_SHARED_TEXT = "shared_text"

    fun activityClass(id: String): Class<out Activity> = when (id) {
        "p1" -> ProfileActivity1::class.java
        "p2" -> ProfileActivity2::class.java
        "p3" -> ProfileActivity3::class.java
        "p4" -> ProfileActivity4::class.java
        else -> ProfileActivity5::class.java
    }

    fun intent(context: Context, id: String, url: String? = null): Intent =
        Intent(context, activityClass(id)).apply {
            putExtra(EXTRA_PROFILE_ID, id)
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
        }

    fun open(context: Context, id: String, url: String? = null) {
        val intent = intent(context, id, url)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Быстрое переключение внутри приложения: открываем новый профиль и закрываем текущий. */
    @Suppress("DEPRECATION")
    fun switch(activity: Activity, id: String, url: String? = null) {
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
