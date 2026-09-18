package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор cookies — основа автологина: именно по именам решается, какие cookies
 * продлевать на год, чтобы вход пережил перезапуск приложения.
 */
class SessionKeeperTest {

    @Test
    fun `cookie-заголовок разбирается на пары`() {
        val pairs = SessionKeeper.parseCookieHeader("a=1; __session=abc.def; theme=dark")

        assertEquals(3, pairs.size)
        assertEquals("a", pairs[0].name)
        assertEquals("1", pairs[0].value)
        assertEquals("__session", pairs[1].name)
        assertEquals("abc.def", pairs[1].value)
    }

    @Test
    fun `мусор в заголовке не ломает разбор`() {
        assertEquals(0, SessionKeeper.parseCookieHeader(null).size)
        assertEquals(0, SessionKeeper.parseCookieHeader("   ").size)
        assertEquals(0, SessionKeeper.parseCookieHeader("=без имени; пусто=").size)
        // значение с «=» внутри сохраняется целиком
        val pairs = SessionKeeper.parseCookieHeader("token=a=b=c")
        assertEquals("a=b=c", pairs[0].value)
    }

    @Test
    fun `cookies авторизации отличаются от служебных`() {
        assertTrue(SessionKeeper.isAuthCookieName("__session"))
        assertTrue(SessionKeeper.isAuthCookieName("next-auth.session-token"))
        assertTrue(SessionKeeper.isAuthCookieName("jwt"))
        assertFalse(SessionKeeper.isAuthCookieName("theme"))
        assertFalse(SessionKeeper.isAuthCookieName("_ga"))
    }

    @Test
    fun `продление ставит год и безопасные атрибуты`() {
        val header = SessionKeeper.persistentCookieHeader("__session", "abc")

        assertTrue(header.startsWith("__session=abc;"))
        assertTrue(header.contains("Max-Age=31536000"))
        assertTrue(header.contains("Path=/"))
        assertTrue(header.contains("Secure"))
        assertEquals("__session=; Path=/; Domain=.arena.ai; Max-Age=0", SessionKeeper.expireDomainCookieHeader("__session"))
    }
}
