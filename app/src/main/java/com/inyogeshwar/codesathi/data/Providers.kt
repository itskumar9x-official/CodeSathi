package com.inyogeshwar.codesathi.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** One provider from the bundled catalog (app/src/main/assets/providers.json). */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val kind: String, // "openai" | "anthropic" | "azure" | "bedrock" | "local"
    val baseUrl: String,
    val models: List<String> = emptyList(),
    val topPick: Boolean = false,
    val local: Boolean = false
) {
    /** "providerId/modelId" — the stable key used for favourites and sessions. */
    fun modelKey(modelId: String) = "$id/$modelId"
}

/**
 * Azure OpenAI REST API version pinned for chat completions and deployment
 * listing (Phase 4). 2024-10-21 is a stable Azure version whose chat
 * completions shape is the OpenAI one; verified against Azure's docs and
 * the deployment URL form
 * https://{resource}.openai.azure.com/openai/deployments/{deployment}/
 * chat/completions?api-version=2024-10-21. Shared by [LlmClient] and the
 * deployments list fetch below so the two can never drift apart.
 */
const val AZURE_API_VERSION = "2024-10-21"

@Serializable
private data class CatalogFile(
    val version: Int = 1,
    val providers: List<Provider> = emptyList()
)

/**
 * Loads the bundled provider catalog. The catalog contains only public
 * metadata (ids, names, base URLs, example model ids) — never API keys.
 * Fetched model lists (GET {baseUrl}/models) are merged on top at runtime
 * by [ProviderRepository]; this file only reads the bundled copy.
 */
object ProviderCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<Provider> {
        val text = context.assets.open("providers.json")
            .bufferedReader().use { it.readText() }
        return json.decodeFromString<CatalogFile>(text).providers
    }
}

/** Outcome of a model-list refresh, so the UI can tell fresh / cached / failed apart. */
data class ModelFetchResult(
    val models: List<String>,
    /** True when the list came from the Room cache because the network call failed. */
    val fromCache: Boolean,
    /** Human-readable error when the network call failed, null on success. */
    val error: String? = null
)

class ProviderRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    val providers: List<Provider> by lazy { ProviderCatalog.load(context) }

    fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }

    fun topPicks(): List<Pair<Provider, String>> =
        providers.filter { it.topPick }
            .flatMap { p -> p.models.map { m -> p to m } }

    /** Cached (previously fetched) model ids for a provider, newest first. */
    suspend fun cachedModels(providerId: String): List<String> =
        database.cachedModelDao().getForProvider(providerId).map { it.modelId }

    /**
     * Refresh a provider's model list from GET {baseUrl}/models.
     *
     * Auth: Anthropic-style providers send `x-api-key` + `anthropic-version`;
     * OpenAI-compatible providers send `Authorization: Bearer <key>` when a
     * key is set; local providers (Ollama / LM Studio) send no key.
     * Accepts the OpenAI shape `{"data":[{"id":"..."}]}` and also a plain
     * JSON array of ids or of `{"id":...}` objects.
     * On success the list is cached in Room. On any failure the cached list
     * is returned instead (with [ModelFetchResult.error] set) — the caller
     * must show the error and never pretend the fetch worked. Returns an
     * empty list with an error when there is neither a network result nor
     * a cached list.
     *
     * Azure (Phase 4): there is no /models list — deployments are listed
     * instead, GET https://{resource}.openai.azure.com/openai/deployments?
     * api-version=[AZURE_API_VERSION] with the `api-key` header. Without a
     * resource name there is no URL to call at all, so the fetch refuses
     * with a plain message instead of guessing a host.
     * Bedrock (Phase 4): no /models list exists on the runtime API
     * (ListFoundationModels is a separate SigV4-signed control-plane call
     * this client does not make) — the bundled model and manual entry
     * cover first use, and the error says so plainly.
     */
    suspend fun refreshModels(
        provider: Provider,
        apiKey: String?,
        azureResourceName: String? = null
    ): ModelFetchResult =
        withContext(Dispatchers.IO) {
            val cached = cachedModels(provider.id)
            if (provider.kind == "local") {
                return@withContext ModelFetchResult(
                    models = cached,
                    fromCache = true,
                    error = "On-device models come from a .task file you pick in this card — there is no server list to refresh."
                )
            }
            if (provider.kind == "bedrock") {
                return@withContext ModelFetchResult(
                    models = cached,
                    fromCache = true,
                    error = "Bedrock has no /models list — use the bundled model or type a model id by hand."
                )
            }
            if (provider.kind == "azure" && azureResourceName.isNullOrBlank()) {
                return@withContext ModelFetchResult(
                    models = cached,
                    fromCache = true,
                    error = "Set the Azure resource name first (Providers → Azure OpenAI)."
                )
            }
            try {
                val url = when (provider.kind) {
                    "azure" -> URL(
                        "https://${azureResourceName!!.trim()}.openai.azure.com" +
                            "/openai/deployments?api-version=$AZURE_API_VERSION"
                    )
                    else -> URL(provider.baseUrl.trimEnd('/') + "/models")
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "CodeSathi/1.0 (CodeSathi; Android)")
                    when (provider.kind) {
                        "anthropic" -> {
                            setRequestProperty("anthropic-version", "2023-06-01")
                            if (!apiKey.isNullOrBlank()) setRequestProperty("x-api-key", apiKey)
                        }
                        // Azure API keys go in the `api-key` header, never Bearer.
                        "azure" -> {
                            if (!apiKey.isNullOrBlank()) setRequestProperty("api-key", apiKey)
                        }
                        else -> {
                            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                        }
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                        throw ModelFetchException(
                            "HTTP $code from ${provider.name}: ${err.take(200).ifBlank { "no details" }}"
                        )
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    val ids = parseModelIds(body)
                    if (ids.isEmpty()) {
                        throw ModelFetchException(
                            "${provider.name} answered but listed no models."
                        )
                    }
                    val now = System.currentTimeMillis()
                    database.cachedModelDao().replaceForProvider(
                        provider.id,
                        ids.map { CachedModel(provider.id, it, now) }
                    )
                    ModelFetchResult(models = ids, fromCache = false)
                } finally {
                    conn.disconnect()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ModelFetchException) {
                    ModelFetchResult(models = cached, fromCache = true, error = e.message)
                } else {
                    ModelFetchResult(
                        models = cached,
                        fromCache = true,
                        error = "${provider.name}: ${e.message ?: "network error"}"
                    )
                }
            }
        }

    /**
     * Fetched models for a provider: fresh list when the network works,
     * otherwise the cached list, otherwise null. Prefer [refreshModels]
     * when the UI needs to know which of the three happened.
     */
    suspend fun fetchModels(provider: Provider, apiKey: String?): List<String>? {
        val result = refreshModels(provider, apiKey)
        return result.models.ifEmpty { null }
    }

    /** Pull model ids out of a /models response (object-with-data or plain array). */
    internal fun parseModelIds(body: String): List<String> {
        val json = Json { ignoreUnknownKeys = true }
        return try {
            val el = json.parseToJsonElement(body)
            val array = when {
                el is kotlinx.serialization.json.JsonObject && el.containsKey("data") ->
                    el["data"]!!.jsonArray
                el is kotlinx.serialization.json.JsonArray -> el
                else -> return emptyList()
            }
            array.mapNotNull { item ->
                when (item) {
                    is kotlinx.serialization.json.JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                    is kotlinx.serialization.json.JsonObject ->
                        item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                }
            }.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class ModelFetchException(message: String) : Exception(message)
