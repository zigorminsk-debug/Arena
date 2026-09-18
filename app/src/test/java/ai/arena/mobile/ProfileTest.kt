package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Профиль должен переживать сериализацию — на этом держится хранение аккаунтов. */
class ProfileTest {

    @Test
    fun `профиль переживает запись и чтение JSON`() {
        val profile = Profile(
            id = "p2",
            name = "Работа",
            color = "#22D3EE",
            account = "me@example.com",
            github = "octocat",
            desktopMode = true,
            googleCompat = true,
            keepScreenOn = true,
            lastUrl = "https://arena.ai/agent",
            lastUsed = 1_700_000_000_000L,
            createdAt = 42L,
        )

        val restored = Profile.fromJson(profile.toJson())

        assertEquals(profile, restored)
        assertEquals(":p2", restored.processNameSuffix)
    }

    @Test
    fun `недостающие поля читаются со значениями по умолчанию`() {
        val json = org.json.JSONObject("""{"id": "p3", "name": "Третий"}""")
        val profile = Profile.fromJson(json)

        assertEquals("p3", profile.id)
        assertEquals("Третий", profile.name)
        assertEquals("", profile.github)
        assertEquals(false, profile.desktopMode)
        assertEquals(0L, profile.lastUsed)
        assertTrue(profile.color.isNotEmpty())
    }
}
