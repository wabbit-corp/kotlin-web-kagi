// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web.kagi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class KagiSpec {
    @Test
    fun `execute sends summarize request and decodes response`() = runTest {
        val client = testClient { request ->
            assertEquals("kagi.com", request.url.host)
            assertEquals("/api/v0/summarize", request.url.encodedPath)
            assertEquals("https://example.com/post", request.url.parameters["url"])
            assertEquals("summary", request.url.parameters["summary_type"])
            assertEquals("agnes", request.url.parameters["engine"])
            assertEquals("DE", request.url.parameters["target_language"])
            assertEquals("false", request.url.parameters["cache"])
            assertEquals("Bot secret-key", request.headers[HttpHeaders.Authorization])

            respond(
                content =
                    """
                    {
                      "meta": {
                        "id": "120145af-f057-466d-9e6d-7829ac902adc",
                        "node": "us-east",
                        "ms": 7943,
                        "future_meta": "ignored"
                      },
                      "data": {
                        "output": "Short summary",
                        "tokens": 11757,
                        "future_data": true
                      }
                    }
                    """
                        .trimIndent(),
                status = HttpStatusCode.OK,
                headers =
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val result =
            Kagi.execute(
                req = "https://example.com/post",
                summaryType = Kagi.SummaryType.Summary,
                model = Kagi.Model.agnes,
                httpClient = client,
                kagiKey = "secret-key",
                targetLanguage = "de",
                cache = false,
            )

        assertEquals("120145af-f057-466d-9e6d-7829ac902adc", result.meta.id)
        assertEquals("Short summary", result.data.output)
        assertEquals(11757, result.data.tokens)
    }

    @Test
    fun `computeCost caps consumer models and keeps muriel flat`() {
        assertEquals(0.03, Kagi.computeCost(Kagi.Model.cecil, 1000))
        assertEquals(0.30, Kagi.computeCost(Kagi.Model.agnes, 10001))
        assertEquals(0.30, Kagi.computeCost(Kagi.Model.daphne, 10001))
        assertEquals(1.0, Kagi.computeCost(Kagi.Model.muriel, 50))
    }

    @Test
    fun `summaryType fromString accepts compatibility aliases and rejects invalid values`() {
        assertEquals(Kagi.SummaryType.KeyPoints, Kagi.SummaryType.fromString("key_points"))
        assertEquals(Kagi.SummaryType.Takeaway, Kagi.SummaryType.fromString("takeaway"))

        assertFailsWith<IllegalArgumentException> { Kagi.SummaryType.fromString("headline") }
    }

    @Test
    fun `key points compatibility alias uses takeaway request mode`() = runTest {
        val client = testClient { request ->
            assertEquals("takeaway", request.url.parameters["summary_type"])
            respond(
                content =
                    """
                    {
                      "meta": {
                        "id": "compat",
                        "node": "us-east",
                        "ms": 1
                      },
                      "data": {
                        "output": "Compatibility summary",
                        "tokens": 42
                      }
                    }
                    """
                        .trimIndent(),
                status = HttpStatusCode.OK,
                headers =
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val result =
            Kagi.execute(
                req = "https://example.com/post",
                summaryType = Kagi.SummaryType.KeyPoints,
                model = Kagi.Model.cecil,
                httpClient = client,
                kagiKey = "secret-key",
            )

        assertEquals("Compatibility summary", result.data.output)
    }

    @Test
    fun `summarize maps http failures to typed error`() = runTest {
        val api =
            KtorKagiApi(
                httpClient =
                    testClient {
                        respond(
                            content = "insufficient credits",
                            status = HttpStatusCode.PaymentRequired,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Text.Plain.toString(),
                                ),
                        )
                    },
                config = KagiApi.Config(apiKey = "secret-key"),
            )

        val error =
            assertFailsWith<KagiApiError.Http> {
                api.summarize(Kagi.Request(url = "https://example.com/post"))
            }

        assertEquals(402, error.status)
    }

    private fun testClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClient = HttpClient(MockEngine(handler)) { install(HttpTimeout) }
}
