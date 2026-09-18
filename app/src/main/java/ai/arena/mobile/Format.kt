package ai.arena.mobile

import android.content.Context
import java.util.Locale

/** Человекочитаемый размер данных (для карточки профиля). */
fun formatDataSize(context: Context, bytes: Long): String = when {
    bytes >= 1_073_741_824L -> context.getString(R.string.size_gb, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> context.getString(R.string.size_mb, bytes / 1_048_576.0)
    bytes >= 1024L -> context.getString(R.string.size_kb, bytes / 1024.0)
    else -> context.getString(R.string.size_bytes, bytes)
}

/** «только что», «12 мин назад», «3 ч назад», «вчера», «12 сен». */
fun relativeTime(context: Context, time: Long): String {
    if (time <= 0L) return ""
    val minutes = (System.currentTimeMillis() - time) / 60_000L
    return when {
        minutes < 1L -> context.getString(R.string.time_now)
        minutes < 60L -> context.getString(R.string.time_minutes_ago, minutes)
        minutes < 1_440L -> context.getString(R.string.time_hours_ago, minutes / 60L)
        minutes < 2_880L -> context.getString(R.string.time_yesterday)
        else -> java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(time))
    }
}
