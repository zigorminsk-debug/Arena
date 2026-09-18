package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Резервная копия профилей: формат должен читаться и не терять настройки. */
class BackupManagerTest {

    private fun profile(id: String, name: String, github: String = "", used: Long = 0L) = Profile(
        id = id,
        name = name,
        color = "#7C5CFF",
        account = "$id@example.com",
        github = github,
        sections = listOf(ProfileSections.CHAT, ProfileSections.AGENT),
        lastUrl = "https://arena.ai/agent",
        lastUsed = used,
        createdAt = 1L,
    )

    @Test
    fun `копия читается обратно без потерь`() {
        val profiles = listOf(profile("p1", "Личный", "octocat", 111L), profile("p2", "Работа"))
        val settings = AppSettings(
            themeMode = SettingsStore.THEME_DARK,
            pinchZoom = true,
            pullToRefresh = false,
            openLastProfile = true,
            keepSession = false,
            appLock = true,
        )

        val json = BackupManager.serialize(profiles, settings, exportedAt = 123L)
        val restored = BackupManager.parse(json)

        assertNotNull(restored)
        assertEquals(2, restored!!.profiles.size)
        assertEquals("octocat", restored.profiles[0].github)
        assertEquals(listOf(ProfileSections.CHAT, ProfileSections.AGENT), restored.profiles[0].sections)
        assertEquals(123L, restored.exportedAt)
        assertEquals(SettingsStore.THEME_DARK, restored.settings?.themeMode)
        assertTrue(restored.settings?.appLock == true)
        assertFalse(restored.settings?.keepSession == true)
    }

    @Test
    fun `чужой файл не считается копией`() {
        assertNull(BackupManager.parse("""{"hello":"world"}"""))
        assertNull(BackupManager.parse("это не json"))
        assertNull(BackupManager.parse(""))
    }

    @Test
    fun `слияние обновляет существующие профили и добавляет новые в свободные слоты`() {
        val existing = listOf(profile("p1", "Старое имя", used = 500L), profile("p2", "Работа"))
        val incoming = listOf(
            profile("p1", "Новое имя", github = "octocat"),
            profile("p4", "Планшет"),
            profile("p9", "Некуда"),
        )

        val merged = BackupManager.merge(existing, incoming)

        assertEquals(listOf("p1", "p2", "p4"), merged.map { it.id })
        assertEquals("Новое имя", merged[0].name)
        assertEquals("octocat", merged[0].github)
        // время последнего использования не берём из чужого файла
        assertEquals(500L, merged[0].lastUsed)
        assertTrue(merged.none { it.id == "p9" })
    }
}
