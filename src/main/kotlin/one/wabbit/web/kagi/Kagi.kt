package one.wabbit.web.kagi

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Kagi {
    enum class SummaryType {
        Summary, KeyPoints;

        override fun toString(): String {
            return when (this) {
                Summary -> "summary"
                KeyPoints -> "key_points"
            }
        }

        companion object {
            fun fromString(value: String): SummaryType {
                return when (value) {
                    "summary" -> Summary
                    "key_points" -> KeyPoints
                    else -> error("Invalid summary type")
                }
            }
        }
    }

    // {
    //  "meta": {
    //    "id": "120145af-f057-466d-9e6d-7829ac902adc",
    //    "node": "us-east",
    //    "ms": 7943
    //  },
    //  "data": {
    //    "output": "In this Youtube video, Jonathan Blow discusses the decline of software
    //technology and the potential collapse of civilization. He argues that technology
    //does not automatically improve and that great achievements in technology can be
    //lost due to the fall of civilizations. Blow believes that software technology has
    //not improved in quite a while and that the industry is adding too much complication
    //to everything. He suggests that simplifying software systems is the right short-term
    //play and that removing complexity is still the right approach even if it doesn't
    //seem like it. Blow also emphasizes the importance of developing the aesthetics for
    //things that are not a giant horrible mess and building institutional knowledge about
    //how to simplify.",
    //    "tokens": 11757,
    //  }
    //}

    @Serializable
    data class Response(
        val meta: MetaData,
        val data: SummaryData
    )

    @Serializable
    data class MetaData(
        val id: String,
        val node: String,
        val ms: Int
    )

    @Serializable
    data class SummaryData(
        val output: String,
        val tokens: Int
    )

    enum class Model {
        agnes, cecil, muriel;
    }

    fun computeCost(model: Model, tokens: Int): Double {
        // Consumer models
        //Price for our consumer-grade models (Cecil and Agnes) is $0.030 USD per 1,000 tokens processed. If you are subscribed to the Kagi Ultimate plan, discounted pricing at $0.025 per 1,000 tokens processed is automatically applied.
        //
        //Notes:
        //
        //Tokens include all tokens processed in + out.
        //Any request over 10,000 tokens is billed as 10,000 tokens, regardless of the length of the document.
        //Accessing cached summaries of the same URL is always free.
        //Enterprise models
        //Our enterprise-grade Muriel summarization engine produces even higher quality summaries, especially for long documents. It also provides longer and more detailed summaries than our consumer-grade model. See the difference here.
        //
        //Muriel usage is a flat rate of $1 USD per summary, regardless of the length or type of the document.
        //
        //To use Muriel just use "muriel" as the "engine" parameter in the API call. See examples below.
        return when (model) {
            Model.agnes, Model.cecil -> {
                if (tokens > 10_000) {
                    0.30
                } else {
                    0.030 * tokens / 1000.0
                }
            }
            Model.muriel -> 1.0
        }
    }

    suspend fun execute(req: String, summaryType: SummaryType, model: Model, httpClient: HttpClient, kagiKey: String): Response {
        val response = httpClient.get("https://kagi.com/api/v0/summarize") {
            parameter("url", req)
            parameter("summary_type", summaryType.toString())
            parameter("engine", model.name)
            header("Authorization", "Bot $kagiKey")
        }

        return Json.decodeFromString<Response>(response.bodyAsText())
    }
}
