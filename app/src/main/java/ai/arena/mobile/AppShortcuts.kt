package ai.arena.mobile

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Ярлыки приложения: долгое нажатие на иконку в лаунчере даёт «Продолжить»
 * и список профилей — переключение аккаунтов без открытия списка.
 */
object AppShortcuts {

    private const val PREFS = "shortcut_prefs"
    private const val KEY_SIGNATURE = "signature"
    private const val CONTINUE_ID = "shortcut_continue"

    /** Обновляет ярлыки, только если профили изменились (имена/цвета/порядок). */
    fun syncIfChanged(context: Context) {
        val profiles = ProfileStore.all(context)
        val signature = profiles.joinToString("|") { "${it.id}:${it.name}:${it.color}" } +
            "|last=" + ProfileStore.lastUsed(context).id

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SIGNATURE, null) == signature) return
        prefs.edit().putString(KEY_SIGNATURE, signature).apply()
        sync(context, profiles)
    }

    private fun sync(context: Context, profiles: List<Profile>) {
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            .coerceAtLeast(1)

        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        val last = ProfileStore.lastUsed(context)
        if (last.lastUsed > 0L) {
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, CONTINUE_ID)
                    .setShortLabel(context.getString(R.string.shortcut_continue))
                    .setLongLabel(context.getString(R.string.shortcut_continue_long, last.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_foreground))
                    .setIntent(ProfileRouter.intent(context, last.id))
                    .build()
            )
        }

        profiles.forEach { profile ->
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, "shortcut_${profile.id}")
                    .setShortLabel(profile.name)
                    .setLongLabel(context.getString(R.string.shortcut_profile_long, profile.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_foreground))
                    .setIntent(ProfileRouter.intent(context, profile.id))
                    .build()
            )
        }

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(maxCount))
        } catch (t: Throwable) {
            // Лаунчер может не поддерживать ярлыки — это не критично
        }
    }
}
