package ai.arena.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ai.arena.mobile.databinding.ActivityMainBinding

/** Экран со списком профилей: сюда приходит и первый запуск, и диплинки с arena.ai. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ProfileAdapter(
            onOpen = { profile -> ProfileRouter.open(this, profile.id) },
            onEdit = { profile -> Sheets.showEdit(this, profile.id) { refresh() } },
        )
        binding.listProfiles.layoutManager = LinearLayoutManager(this)
        binding.listProfiles.adapter = adapter
        binding.fabContinue.setOnClickListener {
            ProfileRouter.open(this, ProfileStore.lastUsed(this).id)
        }

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(ProfileStore.all(this))
        val last = ProfileStore.lastUsed(this)
        binding.fabContinue.isVisible = last.lastUsed > 0L
        if (last.lastUsed > 0L) {
            binding.fabContinue.text = getString(R.string.fab_continue) + ": " + last.name
        }
        // Доводим до конца отложенные очистки профилей
        ProfileResetter.finalizePendingWipes(this)
    }

    /** Открытие ссылок arena.ai (в том числе из письма-подтверждения). */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val host = uri.host ?: return
        if (!host.endsWith("arena.ai")) return
        ProfileRouter.open(this, ProfileStore.lastUsed(this).id, uri.toString())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            Sheets.showSettings(this) { refresh() }
            true
        }

        R.id.action_about -> {
            Sheets.showAbout(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
