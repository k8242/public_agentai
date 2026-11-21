// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.bookmark

import io.github.k8242.threadlog.feature.chat.di.ChatOkHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches an HTTP/HTTPS web page with bounded size and timeout, and extracts a cleaned HTML `<title>`.
 *
 * Network [IOException]s are propagated to callers (such as [BookmarkTitleWorker]) so transient
 * connectivity failures can be retried; non-HTML responses, non-2xx HTTP status codes, and
 * malformed URLs return `null`.
 */
@Singleton
class BookmarkTitleFetcher @Inject constructor(
    @ChatOkHttpClient private val okHttpClient: OkHttpClient,
) {

    @Throws(IOException::class)
    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return@withContext null
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return@withContext null
        }

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
            .get()
            .build()

        val call = okHttpClient.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentTypeHeader = response.header("Content-Type")
                    ?: body.contentType()?.toString()
                    ?: return@withContext null

                if (!isHtmlContentType(contentTypeHeader)) {
                    return@withContext null
                }

                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val htmlSnippet = readBoundedText(body.source(), MAX_READ_BYTES, charset)
                extractTitleFromHtml(htmlSnippet)
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        }
    }

    internal fun extractTitleFromHtml(html: String): String? {
        val match = TITLE_TAG_REGEX.find(html) ?: return null
        val rawTitle = match.groupValues.getOrNull(1) ?: return null
        val decoded = decodeHtmlEntities(rawTitle)
        val collapsed = WHITESPACE_REGEX.replace(decoded, " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > MAX_TITLE_LENGTH) {
            collapsed.take(MAX_TITLE_LENGTH).trimEnd()
        } else {
            collapsed
        }
    }

    internal fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        return HTML_ENTITY_REGEX.replace(input) { match ->
            val hexValue = match.groupValues[1]
            val decValue = match.groupValues[2]
            val namedValue = match.groupValues[3]
            when {
                hexValue.isNotEmpty() -> codePointToString(hexValue.toIntOrNull(16)) ?: match.value
                decValue.isNotEmpty() -> codePointToString(decValue.toIntOrNull(10)) ?: match.value
                namedValue.isNotEmpty() -> NAMED_ENTITIES[namedValue.lowercase()] ?: match.value
                else -> match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int?): String? {
        if (codePoint == null || !Character.isValidCodePoint(codePoint)) return null
        return String(Character.toChars(codePoint))
    }

    private fun isHtmlContentType(contentType: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return mediaType == "text/html" || mediaType == "application/xhtml+xml"
    }

    private fun readBoundedText(source: BufferedSource, maxBytes: Int, charset: Charset): String {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = Buffer()
        var remaining = maxBytes.toLong()
        while (remaining > 0L) {
            val read = source.read(buffer, minOf(remaining, CHUNK_SIZE))
            if (read == -1L) break
            val bytes = buffer.readByteArray()
            out.write(bytes)
            remaining -= bytes.size
        }
        return out.toByteArray().toString(charset)
    }

    companion object {
        const val MAX_READ_BYTES = 64 * 1024 // 64 KiB bounded read
        const val MAX_TITLE_LENGTH = 160
        private const val CHUNK_SIZE = 4096L
        private const val USER_AGENT = "ThreadLog/1.0 (+https://threadlog.local; BookmarkTitleFetcher)"

        private val TITLE_TAG_REGEX = Regex(
            pattern = "<title(?:\\s[^>]*)?>(.*?)</title>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val HTML_ENTITY_REGEX = Regex("&(?:#[xX]([0-9a-fA-F]{1,6})|#(\\d{1,7})|([a-zA-Z]{2,12}));")

        private val NAMED_ENTITIES = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "mdash" to "\u2014",
            "ndash" to "\u2013",
            "hellip" to "\u2026",
            "copy" to "\u00A9",
            "lsquo" to "\u2018",
            "rsquo" to "\u2019",
            "ldquo" to "\u201C",
            "rdquo" to "\u201D",
        )
    }
}
