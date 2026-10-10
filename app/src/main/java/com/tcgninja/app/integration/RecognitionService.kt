package com.tcgninja.app.integration

import android.util.Base64
import com.tcgninja.app.data.CardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecognitionService(private val settings: ProviderSettings) {
    private val http = IntegrationHttp()

    /** Sends only explicitly supplied photos to the selected provider. Does not commit or price cards. */
    suspend fun recognize(frontPath: String, backPath: String? = null, binder: Boolean = false): List<CardRecord> = withContext(Dispatchers.IO) {
        val provider = settings.provider
        val key = settings.getKey(provider)
        if (key.isBlank()) throw IntegrationException(IntegrationException.Kind.AUTHENTICATION,
            "Add your API key in Settings before recognizing cards.")
        val front = RecognitionImages.load(settings.context, frontPath)
        val generatedCrops = mutableListOf<String>()
        try {
            val images = mutableListOf(Base64.encodeToString(RecognitionImages.jpeg(front), Base64.NO_WRAP))
            if (!backPath.isNullOrBlank()) {
                val back = RecognitionImages.load(settings.context, backPath)
                try { images += Base64.encodeToString(RecognitionImages.jpeg(back), Base64.NO_WRAP) }
                finally { back.recycle() }
            }
            coroutineContext.ensureActive()
            val request = when (provider) {
                ProviderSettings.OPENAI -> RecognitionRequests.openAiRequest(key, settings.openAiModel, images, binder)
                ProviderSettings.ANTHROPIC -> RecognitionRequests.anthropicRequest(key, settings.anthropicModel, images, binder)
                else -> throw IntegrationException(IntegrationException.Kind.UNSUPPORTED, "This recognition provider is not supported.")
            }
            val response = http.text(request, 500_000, "application/json")
            val extracted = try {
                val root = JSONObject(response)
                if (provider == ProviderSettings.OPENAI) RecognitionJson.openAiText(root) else RecognitionJson.anthropicText(root)
            } catch (e: IntegrationException) { throw e }
            catch (_: Exception) { invalidResponse() }
            val cards = RecognitionJson.parse(extracted, binder)
            if (cards.isEmpty()) throw IntegrationException(IntegrationException.Kind.IMAGE,
                "No readable cards were found. Try a clearer photo or enter the card manually.")
            cards.map { observed ->
                coroutineContext.ensureActive()
                val crop = if (binder && observed.bounds != null) RecognitionImages.crop(settings.context, front, observed.bounds, observed.card.id) else null
                if (crop != null) generatedCrops += crop
                val provenance = if (binder) buildString {
                    append(if (backPath.isNullOrBlank()) "\nAdditional photo retains binder original." else "\nAdditional photo retains the supplied back image.")
                    observed.bounds?.let { append("\nSuggested bounds (left,top,right,bottom): ${it.left},${it.top},${it.right},${it.bottom}") }
                    append(if (crop == null) "\nFull photo retained; check the card location manually." else "\nAI crop: verify that it contains this entire card.")
                } else ""
                observed.card.copy(imagePath = crop ?: frontPath,
                    backImagePath = backPath?.takeIf(String::isNotBlank) ?: if (binder) frontPath else "",
                    notes = observed.card.notes + provenance)
            }
        } catch (e: Exception) {
            generatedCrops.forEach { File(it).delete() }
            throw e
        } finally { front.recycle() }
    }
}

internal object RecognitionRequests {
    fun openAiRequest(key: String, model: String, images: List<String>, binder: Boolean): Request {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", RecognitionJson.prompt(binder)))
        images.forEach { content.put(JSONObject().put("type", "input_image").put("detail", "high")
            .put("image_url", "data:image/jpeg;base64,$it")) }
        val body = JSONObject().put("model", model).put("store", false)
            .put("max_output_tokens", if (binder) 12000 else 2500)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema")
                .put("name", "card_observations").put("strict", true).put("schema", RecognitionJson.schema())))
        return Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    fun anthropicRequest(key: String, model: String, images: List<String>, binder: Boolean): Request {
        val content = JSONArray()
        images.forEach { content.put(JSONObject().put("type", "image").put("source", JSONObject()
            .put("type", "base64").put("media_type", "image/jpeg").put("data", it))) }
        content.put(JSONObject().put("type", "text").put("text", RecognitionJson.prompt(binder)))
        val body = JSONObject().put("model", model).put("max_tokens", if (binder) 12000 else 2500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema")
                .put("schema", RecognitionJson.schema())))
        return Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
}
