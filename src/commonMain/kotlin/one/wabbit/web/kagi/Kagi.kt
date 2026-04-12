// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.kagi

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.responseBodySampleOrNull
import one.wabbit.web.common.retryingIdempotentHttpCall

sealed class KagiApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : KagiApiError(message)

    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        cause: Throwable? = null,
    ) :
        KagiApiError(
            buildString {
                append("HTTP ")
                append(status)
                append(" from ")
                append(url)
                if (!bodySample.isNullOrBlank()) {
                    append(", body sample: ")
                    append(bodySample.take(256))
                }
            },
            cause,
        )

    class Network(val url: String, cause: Throwable) :
        KagiApiError(
            "Network failure talking to $url: ${cause::class.simpleName}: ${cause.message}",
            cause,
        )

    class Parse(val url: String, val bodySample: String, cause: Throwable) :
        KagiApiError(
            "Failed to parse Kagi response from $url: ${cause::class.simpleName}: ${cause.message}; body sample: ${bodySample.take(256)}",
            cause,
        )
}

object Kagi {
    private const val MAX_BILLED_CONSUMER_TOKENS = 10_000

    enum class SummaryType(private val apiValue: String) {
        Summary("summary"),
        KeyPoints("takeaway"),
        Takeaway("takeaway");

        override fun toString(): String = apiValue

        companion object {
            fun fromString(value: String): SummaryType =
                when (value.lowercase()) {
                    "summary" -> Summary
                    "key_points" -> KeyPoints
                    "takeaway" -> Takeaway
                    else -> throw IllegalArgumentException("Invalid summary type: $value")
                }
        }
    }

    @Serializable data class Response(val meta: MetaData, val data: SummaryData)

    @Serializable data class MetaData(val id: String, val node: String, val ms: Int)

    @Serializable data class SummaryData(val output: String, val tokens: Int)

    enum class Model {
        agnes,
        cecil,
        daphne,
        muriel,
    }

    data class Request(
        val url: String,
        val summaryType: SummaryType = SummaryType.Summary,
        val model: Model = Model.cecil,
        val targetLanguage: String? = null,
        val cache: Boolean? = null,
    )

    fun computeCost(model: Model, tokens: Int): Double {
        require(tokens >= 0) { "tokens must be non-negative" }

        return when (model) {
            Model.agnes,
            Model.cecil,
            Model.daphne -> {
                val billedTokens = minOf(tokens, MAX_BILLED_CONSUMER_TOKENS)
                0.030 * billedTokens / 1000.0
            }
            Model.muriel -> 1.0
        }
    }

    suspend fun execute(
        req: String,
        summaryType: SummaryType,
        model: Model,
        httpClient: HttpClient,
        kagiKey: String,
        targetLanguage: String? = null,
        cache: Boolean? = null,
    ): Response =
        KtorKagiApi(httpClient = httpClient, config = KagiApi.Config(apiKey = kagiKey))
            .summarize(
                Request(
                    url = req,
                    summaryType = summaryType,
                    model = model,
                    targetLanguage = targetLanguage,
                    cache = cache,
                )
            )
}

interface KagiApi {
    data class Config(
        val apiKey: String,
        val baseUrl: String = "https://kagi.com/api/v0/summarize",
        val etiquette: Etiquette = Etiquette("one.wabbit.web.kagi/2.0"),
        val timeouts: Timeouts =
            Timeouts(request = 30.seconds, connect = 30.seconds, socket = 30.seconds),
    ) {
        init {
            require(apiKey.isNotBlank()) { "apiKey must not be blank" }
            require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        }
    }

    suspend fun summarize(request: Kagi.Request): Kagi.Response
}

class KtorKagiApi(private val httpClient: HttpClient, val config: KagiApi.Config) : KagiApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    override suspend fun summarize(request: Kagi.Request): Kagi.Response {
        val normalized = request.normalized()
        val response =
            try {
                retryingIdempotentHttpCall {
                    httpClient.get(config.baseUrl) {
                        expectSuccess = true
                        applyEtiquette(config.etiquette)
                        applyTimeouts(config.timeouts)
                        accept(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bot ${config.apiKey}")
                        parameter("url", normalized.url)
                        parameter("summary_type", normalized.summaryType.toString())
                        parameter("engine", normalized.model.name)
                        normalized.targetLanguage?.let { parameter("target_language", it) }
                        normalized.cache?.let { parameter("cache", it.toString()) }
                    }
                }
            } catch (t: Throwable) {
                throw t.toKagiError(config.baseUrl)
            }

        return response.decodeResponse(config.baseUrl)
    }

    private suspend fun HttpResponse.decodeResponse(url: String): Kagi.Response {
        val body =
            try {
                bodyAsText()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw KagiApiError.Network(url, t)
            }

        return try {
            json.decodeFromString<Kagi.Response>(body)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw KagiApiError.Parse(url, body.take(2048), t)
        }
    }
}

private fun Kagi.Request.normalized(): Kagi.Request {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isEmpty()) {
        throw KagiApiError.InvalidInput("url must not be blank")
    }

    val normalizedLanguage = targetLanguage?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

    return copy(url = normalizedUrl, targetLanguage = normalizedLanguage)
}

private suspend fun Throwable.toKagiError(url: String): KagiApiError {
    if (this is CancellationException) throw this
    return if (this is ResponseException) {
        val sample = responseBodySampleOrNull()
        KagiApiError.Http(url, response.status.value, sample, this)
    } else {
        KagiApiError.Network(url, this)
    }
}
