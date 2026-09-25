package ai.arena.mobile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Выбор имени файла для DownloadManager.
 *
 * WebView часто передаёт URL без расширения и mime-type
 * application/octet-stream. В таком случае URLUtil.guessFileName() даёт
 * downloadfile.bin, даже если сервер прислал имя в Content-Disposition.
 */
object DownloadFileName {

    data class Result(
        val name: String,
        val hasExtension: Boolean,
    )

    private val filenameStarRegex = Regex(
        "(?i)(?:^|;)\\s*filename\\*\\s*=\\s*(?:[A-Za-z0-9_-]+''\\s*)?(\"[^\"]*\"|[^;]*)"
    )
    private val filenameRegex = Regex(
        "(?i)(?:^|;)\\s*filename\\s*=\\s*(\"[^\"]*\"|[^;]*)"
    )
    private val extensionRegex = Regex("^[A-Za-z0-9]{1,12}$")

    /**
     * Возвращает наиболее надёжное имя из Content-Disposition, URL или MIME.
     * null означает, что сервер не дал достаточно информации и стоит запросить
     * заголовки отдельным HEAD-запросом.
     */
    fun resolve(url: String?, contentDisposition: String?, mimeType: String?): Result? {
        val mimeExtension = extensionFromMime(mimeType)

        val fromDisposition = dispositionFilename(contentDisposition)
        if (!fromDisposition.isNullOrBlank()) {
            return resultWithExtension(fromDisposition, mimeExtension)
        }

        val fromUrl = urlFilename(url)
        if (!fromUrl.isNullOrBlank() && !isUuidOnly(fromUrl)) {
            return resultWithExtension(fromUrl, mimeExtension)
        }

        if (!mimeExtension.isNullOrBlank()) {
            return Result("download.$mimeExtension", hasExtension = true)
        }

        return null
    }

    /** Имя, пришедшее из атрибута download у blob-ссылки. */
    fun resolveSuggestedName(name: String?, mimeType: String?): Result? {
        if (name.isNullOrBlank()) return resolve(null, null, mimeType)
        return resultWithExtension(name, extensionFromMime(mimeType))
    }

    fun isGenericMime(mimeType: String?): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return mime.isEmpty() || mime == "application/octet-stream" ||
            mime == "binary/octet-stream" || mime == "application/download" ||
            mime == "application/x-download"
    }

    fun extensionFromMime(mimeType: String?): String? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.isEmpty() || isGenericMime(mime)) return null
        return MIME_EXTENSIONS[mime]
    }

    private fun resultWithExtension(raw: String, mimeExtension: String?): Result {
        val clean = sanitize(raw)
        if (clean.isEmpty()) return Result(
            name = "download",
            hasExtension = false,
        )
        if (hasExtension(clean)) return Result(clean, hasExtension = true)
        return if (!mimeExtension.isNullOrBlank()) {
            Result("$clean.$mimeExtension", hasExtension = true)
        } else {
            Result(clean, hasExtension = false)
        }
    }

    private fun dispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = filenameStarRegex.find(header)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) return decodeFilename(encoded)
        val plain = filenameRegex.find(header)?.groupValues?.getOrNull(1)
        return plain?.let(::decodeFilename)
    }

    private fun decodeFilename(value: String): String {
        var clean = value.trim()
        if (clean.length >= 2 && clean.first() == '"' && clean.last() == '"') {
            clean = clean.substring(1, clean.length - 1)
        }
        // RFC 5987: filename*=UTF-8''%D1%84%D0%B0%D0%B9%D0%BB.pdf
        val separator = clean.indexOf("''")
        if (separator >= 0) clean = clean.substring(separator + 2)
        return try {
            // '+' в Content-Disposition — настоящий плюс, а не пробел.
            URLDecoder.decode(clean.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            clean
        }
    }

    private fun urlFilename(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val path = URI(url).path ?: return null
            val segment = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
            decodeFilename(segment)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sanitize(value: String): String {
        val leaf = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F\\\"<>|:*?]"), "_")
            .trim()
            .trim('.')
        return leaf.take(240)
    }

    private fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        return extensionRegex.matches(name.substring(dot + 1))
    }

    private fun isUuidOnly(name: String): Boolean =
        name.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f-]{13,}$")) ||
            name.matches(Regex("^[0-9]{8,}$"))

    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-7z-compressed" to "7z",
        "application/x-rar-compressed" to "rar",
        "application/json" to "json",
        "application/xml" to "xml",
        "application/rtf" to "rtf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/html" to "html",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "audio/mpeg" to "mp3",
        "audio/wav" to "wav",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
    )
}
