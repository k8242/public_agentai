// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.bookmark

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BookmarkTitleFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: BookmarkTitleFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        fetcher = BookmarkTitleFetcher(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `extracts title case-insensitively and collapses whitespace`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <!doctype html>
                    <html>
                      <head>
                        <TITLE data-test="1">
                          ThreadLog   Journal
                          Architecture
                        </TITLE>
                      </head>
                      <body>Hello</body>
                    </html>
                    """.trimIndent(),
                ),
        )

        val title = fetcher.fetchTitle(server.url("/article").toString())

        assertThat(title).isEqualTo("ThreadLog Journal Architecture")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("User-Agent")).contains("ThreadLog")
    }

    @Test
    fun `decodes named decimal and hex html entities without double decoding`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(
                    "<html><head><title>Tom &amp; Jerry&#39;s &quot;Adventure&quot; &lt;1&gt; &#x27;ok&#x27; &#8212; &amp;lt;raw&amp;gt;</title></head></html>",
                ),
        )

        val title = fetcher.fetchTitle(server.url("/entities").toString())

        assertThat(title).isEqualTo("Tom & Jerry's \"Adventure\" <1> 'ok' \u2014 &lt;raw&gt;")
    }

    @Test
    fun `supports xhtml responses`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xhtml+xml; charset=utf-8")
                .setBody("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>XHTML Doc</title></head></html>"),
        )

        val title = fetcher.fetchTitle(server.url("/xhtml").toString())

        assertThat(title).isEqualTo("XHTML Doc")
    }

    @Test
    fun `ignores non-html content types`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"title": "<title>Should Not Parse</title>"}"""),
        )

        val title = fetcher.fetchTitle(server.url("/api/data").toString())

        assertThat(title).isNull()
    }

    @Test
    fun `ignores non-2xx responses such as 404`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>404 Not Found</title></head></html>"),
        )

        val title = fetcher.fetchTitle(server.url("/missing").toString())

        assertThat(title).isNull()
    }

    @Test
    fun `caps overly long titles to at most 160 characters`() = runTest {
        val longTitle = "A".repeat(250)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>$longTitle</title></head></html>"),
        )

        val title = fetcher.fetchTitle(server.url("/long").toString())

        assertThat(title).isNotNull()
        assertThat(title!!.length).isEqualTo(BookmarkTitleFetcher.MAX_TITLE_LENGTH)
    }

    @Test
    fun `bounded read returns null if title appears past 64 KiB`() = runTest {
        val padding = "x".repeat(BookmarkTitleFetcher.MAX_READ_BYTES + 1024)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><!-- $padding --><title>Too Late</title></head></html>"),
        )

        val title = fetcher.fetchTitle(server.url("/huge").toString())

        assertThat(title).isNull()
    }

    @Test
    fun `returns null for invalid or non-http url`() = runTest {
        assertThat(fetcher.fetchTitle("not a url")).isNull()
        assertThat(fetcher.fetchTitle("ftp://example.com/file")).isNull()
        assertThat(fetcher.fetchTitle("javascript:alert(1)")).isNull()
    }
}
