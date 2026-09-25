package ai.arena.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNameTest {

    @Test
    fun `RFC 5987 filename keeps unicode extension`() {
        val result = DownloadFileName.resolve(
            url = "https://arena.ai/api/files/7f2b",
            contentDisposition = "attachment; filename*=UTF-8''%D0%BE%D1%82%D1%87%D0%B5%D1%82.pdf",
            mimeType = "application/octet-stream",
        )

        assertEquals("отчет.pdf", result?.name)
        assertTrue(result?.hasExtension == true)
    }

    @Test
    fun `plain filename gets extension from mime type`() {
        val result = DownloadFileName.resolve(
            url = "https://arena.ai/download/7f2b",
            contentDisposition = "attachment; filename=photo",
            mimeType = "image/jpeg; charset=binary",
        )

        assertEquals("photo.jpg", result?.name)
    }

    @Test
    fun `extension from URL is preferred over generic download name`() {
        val result = DownloadFileName.resolve(
            url = "https://arena.ai/files/report.xlsx?download=1",
            contentDisposition = null,
            mimeType = "application/octet-stream",
        )

        assertEquals("report.xlsx", result?.name)
    }

    @Test
    fun `known mime type creates a useful fallback`() {
        val result = DownloadFileName.resolve(
            url = "https://arena.ai/files/7f2b",
            contentDisposition = null,
            mimeType = "application/pdf",
        )

        assertEquals("7f2b.pdf", result?.name)
        assertTrue(result?.hasExtension == true)
    }

    @Test
    fun `generic mime type is detected`() {
        assertTrue(DownloadFileName.isGenericMime("application/octet-stream"))
        assertTrue(DownloadFileName.isGenericMime(null))
        assertFalse(DownloadFileName.isGenericMime("application/pdf"))
    }
}
