package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка разбора релизов GitHub и решения «есть ли обновление». */
class UpdateCheckerTest {

    private val fullRelease = """
        {
          "tag_name": "v1.2.0",
          "html_url": "https://github.com/zigorminsk-debug/Arena/releases/tag/v1.2.0",
          "assets": [
            {"name": "ArenaMobile-1.2.0-build37-debug.apk", "browser_download_url": "https://example.com/debug.apk"},
            {"name": "ArenaMobile-1.2.0-build37.apk", "browser_download_url": "https://example.com/release.apk"}
          ]
        }
    """.trimIndent()

    private fun parse(json: String): ReleaseInfo =
        UpdateChecker.parseRelease(json) ?: error("релиз не разобран: $json")

    @Test
    fun `предпочитает release-сборку debug-сборке`() {
        val info = parse(fullRelease)

        assertEquals("ArenaMobile-1.2.0-build37.apk", info.assetName)
        assertFalse(info.isDebugAsset)
        assertEquals(37, info.buildNumber)
        assertEquals("1.2.0", info.versionName)
        assertEquals("https://example.com/release.apk", info.assetUrl)
        assertEquals(
            "https://github.com/zigorminsk-debug/Arena/releases/tag/v1.2.0",
            info.pageUrl,
        )
    }

    @Test
    fun `если есть только debug, берёт его`() {
        val info = parse(
            """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://example.com/release",
              "assets": [
                {"name": "ArenaMobile-1.2.0-build41-debug.apk", "browser_download_url": "https://example.com/debug.apk"}
              ]
            }
            """.trimIndent()
        )

        assertTrue(info.isDebugAsset)
        assertEquals(41, info.buildNumber)
    }

    @Test
    fun `из нескольких релизных сборок берёт самую новую`() {
        val info = parse(
            """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://example.com/release",
              "assets": [
                {"name": "ArenaMobile-1.1.0-build20.apk", "browser_download_url": "https://example.com/old.apk"},
                {"name": "ArenaMobile-1.2.0-build37.apk", "browser_download_url": "https://example.com/new.apk"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("https://example.com/new.apk", info.assetUrl)
        assertEquals(37, info.buildNumber)
    }

    @Test
    fun `обновление есть только при большем номере сборки`() {
        val info = parse(fullRelease)

        assertTrue(UpdateChecker.isNewer(info, 36))
        assertFalse(UpdateChecker.isNewer(info, 37))
        assertFalse(UpdateChecker.isNewer(info, 38))
    }

    @Test
    fun `битый или неподходящий ответ не ломает проверку`() {
        assertNull(UpdateChecker.parseRelease("не json"))
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease("""{"assets": []}"""))
        assertNull(
            UpdateChecker.parseRelease(
                """{"assets": [{"name": "readme.txt", "browser_download_url": "x"}]}"""
            )
        )
    }
}
