package ai.arena.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ai.arena.mobile.databinding.ActivityMainBinding

/** Экран со списком профилей: сюда приходит и первый запуск, и диплинки с arena.ai. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapter
    private lateinit var backupUi: BackupUi
    private lateinit var appLockGate: AppLockGate
    private var pendingDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupUi = BackupUi(this)
        appLockGate = AppLockGate(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ProfileAdapter(
            onOpen = { profile -> ProfileRouter.open(this, profile.id) },
            onEdit = { profile -> Sheets.showEdit(this, profile.id) { refresh() } },
            onMenu = { profile, anchor -> showCardMenu(profile, anchor) },
        )
        binding.listProfiles.layoutManager = LinearLayoutManager(this)
        binding.listProfiles.adapter = adapter
        binding.fabContinue.setOnClickListener {
            ProfileRouter.open(this, ProfileStore.lastUsed(this).id)
        }

        pendingDeepLink = deepLinkOf(intent)

        // Всё, что открывает профили, делаем только после подтверждения личности
        appLockGate.ensure { handleAfterUnlock(intent) }

        // Тихая проверка обновлений (не чаще раза в 12 часов)
        UpdateChecker.checkAsync(this, manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkOf(intent)?.let { pendingDeepLink = it }
        appLockGate.ensure { handleAfterUnlock(intent) }
    }

    /** Действия, отложенные до разблокировки: диплинк, автозапуск профиля, «Поделиться». */
    private fun handleAfterUnlock(intent: Intent?) {
        val url = pendingDeepLink
        if (url != null) {
            pendingDeepLink = null
            ProfileRouter.open(this, ProfileStore.lastUsed(this).id, url)
        } else {
            openLastProfileIfEnabled()
        }
        handleSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Если обновление уже скачалось — предлагаем установить
        UpdateChecker.installPendingIfReady(this)
        AppShortcuts.syncIfChanged(this)
        // Возврат в приложение после долгого фона: спрашиваем подтверждение снова
        appLockGate.ensure { }
    }

    /** Список профилей перерисовывается после импорта резервной копии. */
    fun refreshAfterImport() {
        if (::adapter.isInitialized) refresh()
    }

    private fun refresh() {
        adapter.submit(ProfileStore.all(this))
        val last = ProfileStore.lastUsed(this)
        binding.fabContinue.isVisible = last.lastUsed > 0L
        if (last.lastUsed > 0L) {
            binding.fabContinue.text = getString(R.string.fab_continue) + ": " + last.name
        }
        // Доводим до конца отложенные очистки профилей и кэша
        ProfileResetter.finalizePendingWipes(this)
        ProfileResetter.finalizePendingCacheCleans(this)
    }

    /** «Открывать сразу последний профиль»: список не задерживает пользователя. */
    private fun openLastProfileIfEnabled() {
        if (!SettingsStore.read(this).openLastProfile) return
        val last = ProfileStore.lastUsed(this)
        if (last.lastUsed <= 0L) return
        startActivity(ProfileRouter.intent(this, last.id))
    }

    private fun deepLinkOf(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        val host = uri.host ?: return null
        return if (host.endsWith("arena.ai")) uri.toString() else null
    }

    /**
     * «Поделиться в Arena»: текст из другого приложения открываем в новом чате
     * последнего профиля (подставляется в поле ввода скриптом на странице).
     */
    private fun handleSharedText(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return false

        // чтобы повторный onNewIntent/пересоздание не отправило текст снова
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = null

        val profile = ProfileStore.lastUsed(this)
        startActivity(
            ProfileRouter.intent(this, profile.id, Links.HOME).apply {
                putExtra(ProfileRouter.EXTRA_SHARED_TEXT, text)
            }
        )
        return true
    }

    /** Меню быстрых действий на карточке профиля. */
    private fun showCardMenu(profile: Profile, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_profile_card, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.card_open -> {
                    ProfileRouter.open(this, profile.id)
                    true
                }

                R.id.card_agent -> {
                    ProfileRouter.open(this, profile.id, Links.AGENT)
                    true
                }

                R.id.card_github_repos -> {
                    ProfileRouter.open(this, profile.id, Links.githubRepos(profile.github))
                    true
                }

                R.id.card_diagnostics -> {
                    Sheets.showDiagnostics(this, profile.id, inProfileProcess = false)
                    true
                }

                R.id.card_settings -> {
                    Sheets.showEdit(this, profile.id) { refresh() }
                    true
                }

                R.id.card_clear_cache -> {
                    confirmClearCache(profile)
                    true
                }

                R.id.card_wipe -> {
                    confirmWipe(profile)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun confirmClearCache(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_cache_title)
            .setMessage(R.string.dialog_clear_cache_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.menu_clear_cache) { _, _ ->
                ProfileResetter.clearCache(this, profile.id)
                Toast.makeText(this, R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    private fun confirmWipe(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_title)
            .setMessage(R.string.dialog_reset_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                ProfileResetter.reset(this, profile.id)
                Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            Sheets.showSettings(
                activity = this,
                onChange = { refresh() },
                onExport = { backupUi.export() },
                onImport = { backupUi.import() },
            )
            true
        }

        R.id.action_lock_now -> {
            AppLock.lockNow(this)
            Toast.makeText(this, R.string.menu_lock_now, Toast.LENGTH_SHORT).show()
            recreate()
            true
        }

        R.id.action_about -> {
            Sheets.showAbout(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
