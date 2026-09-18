package ai.arena.mobile

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Ярлыки приложения: долгое нажатие на иконку в лаунчере даёт быстрый доступ
 * к режимам («Новый чат», «Agent Mode») и к профилям аккаунтов.
 */
object AppShortcuts {

    private const val PREFS = "shortcut_prefs"
    private const val KEY_SIGNATURE = "signature"

    const val CONTINUE_ID = "shortcut_continue"
    const val NEW_CHAT_ID = "shortcut_new_chat"
    const val AGENT_ID = "shortcut_agent"

    /** Обновляет ярлыки, только если профили изменились (имена/цвета/порядок). */
    fun syncIfChanged(context: Context) {
        val profiles = ProfileStore.all(context)
        val last = ProfileStore.lastUsed(context)
        val signature = profiles.joinToString("|") { "${it.id}:${it.name}:${it.color}" } +
            "|last=${last.id}:${if (last.lastUsed > 0) 1 else 0}"

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SIGNATURE, null) == signature) return
        prefs.edit().putString(KEY_SIGNATURE, signature).apply()
        sync(context, profiles)
    }

    private fun sync(context: Context, profiles: List<Profile>) {
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            .coerceAtLeast(1)

        val last = ProfileStore.lastUsed(context)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()

        if (last.lastUsed > 0L) {
            // Режимы: сразу в нужный раздел последнего профиля
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, NEW_CHAT_ID)
                    .setShortLabel(context.getString(R.string.shortcut_new_chat))
                    .setLongLabel(context.getString(R.string.shortcut_new_chat_long, last.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_foreground))
                    .setIntent(ProfileRouter.intent(context, last.id, Links.HOME))
                    .build()
            )
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, AGENT_ID)
                    .setShortLabel(context.getString(R.string.shortcut_agent))
                    .setLongLabel(context.getString(R.string.shortcut_agent_long, last.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher_foreground))
                    .setIntent(ProfileRouter.intent(context, last.id, Links.AGENT))
                    .build()
            )
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
