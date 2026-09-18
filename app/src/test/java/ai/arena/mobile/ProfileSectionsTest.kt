package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Настраиваемые разделы меню: список всегда должен быть корректным. */
class ProfileSectionsTest {

    @Test
    fun `неизвестные разделы отбрасываются`() {
        val result = ProfileSections.normalize(listOf("chat", "nonsense", "repos"))
        assertEquals(listOf(ProfileSections.CHAT, ProfileSections.REPOS), result)
    }

    @Test
    fun `пустой список превращается в набор по умолчанию`() {
        assertEquals(ProfileSections.DEFAULT, ProfileSections.normalize(emptyList()))
    }

    @Test
    fun `чат всегда остаётся доступным`() {
        assertTrue(ProfileSections.CHAT in ProfileSections.ALWAYS)
        assertTrue(ProfileSections.CHAT in ProfileSections.normalize(listOf(ProfileSections.CHAT)))
    }

    @Test
    fun `адреса разделов ведут на arena ai`() {
        val profile = Profile(id = "p1", name = "Тест", color = "#7C5CFF", github = "octocat")

        assertEquals(Links.HOME, ProfileSections.url(ProfileSections.CHAT, profile))
        assertEquals(Links.AGENT, ProfileSections.url(ProfileSections.AGENT, profile))
        assertEquals(Links.LEADERBOARD, ProfileSections.url(ProfileSections.LEADERBOARD, profile))
        assertEquals("https://github.com/octocat?tab=repositories", ProfileSections.url(ProfileSections.REPOS, profile))
    }

    @Test
    fun `порядок разделов всегда одинаковый`() {
        val first = ProfileSections.normalize(listOf("help", "chat", "history"))
        val second = ProfileSections.normalize(listOf("history", "help", "chat"))
        assertEquals(first, second)
    }
}
