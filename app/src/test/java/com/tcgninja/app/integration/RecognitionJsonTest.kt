package com.tcgninja.app.integration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class RecognitionJsonTest {
    private fun observation() = JSONObject().apply {
        listOf("name", "category", "subtype", "hp", "stage", "setName", "number", "rarity", "observations").forEach { put(it, "") }
        put("name", "Charizard"); put("category", "Pokémon"); put("number", "004/102")
        listOf("types", "species", "mechanics").forEach { put(it, JSONArray()) }
        put("bounds", JSONObject.NULL)
    }
    private fun response(vararg observations: JSONObject) = JSONObject().put("cards", JSONArray(observations.toList())).toString()

    @Test fun draftsHaveNoInventedPriceOrConditionOrFoil() {
        val card = RecognitionJson.parse(response(observation()), false).single().card
        assertEquals("004/102", card.number)
        assertEquals("Unknown", card.finish)
        assertEquals("Unknown", card.condition)
        assertNull(card.priceCents)
        assertTrue(card.priceQuotes.isEmpty())
        assertEquals("", card.catalogId)
        assertEquals("", card.pricechartingUrl)
        assertEquals("", card.tcgplayerUrl)
    }
    @Test fun duplicatesAreSeparatePhysicalCopies() {
        val cards = RecognitionJson.parse(response(observation(), observation()), true)
        assertEquals(2, cards.size)
        assertNotEquals(cards[0].card.id, cards[1].card.id)
    }
    @Test fun badBoundsRetainTheCardForFullPhotoFallback() {
        val bad = observation().put("bounds", JSONObject().put("left", .8).put("top", .1).put("right", .2).put("bottom", .7))
        assertNull(RecognitionJson.parse(response(bad), true).single().bounds)
    }
    @Test fun goodBoundsAreNormalized() {
        val valid = observation().put("bounds", JSONObject().put("left", .1).put("top", .2).put("right", .4).put("bottom", .8))
        assertEquals(CardBounds(.1, .2, .4, .8), RecognitionJson.parse(response(valid), true).single().bounds)
    }
    @Test fun rejectsUnsupportedFieldsAndWrongTypes() {
        rejects { RecognitionJson.parse(response(observation().put("priceCents", 100)), false) }
        rejects { RecognitionJson.parse(response(observation().put("hp", 120)), false) }
        rejects { RecognitionJson.parse(response(observation().put("category", "Injected")), false) }
        rejects { RecognitionJson.parse(response(observation().put("types", "Fire")), false) }
    }
    @Test fun rejectsOversizedAndMultipleSingleScanResults() {
        rejects { RecognitionJson.parse(response(observation(), observation()), false) }
        rejects { RecognitionJson.parse(response(observation().put("name", "x".repeat(201))), false) }
        rejects { RecognitionJson.parse(response(*(0..24).map { observation() }.toTypedArray()), true) }
    }
    @Test fun rejectsTruncatedOrTrailingOrMarkdownPayload() {
        rejects { RecognitionJson.parse("{\"cards\":[", false) }
        rejects { RecognitionJson.parse(response(observation()) + " ignored", false) }
        rejects { RecognitionJson.parse("```json\n${response(observation())}\n```", false) }
    }
    @Test fun handlesEmptyResultsWithoutFabricatingCards() {
        assertTrue(RecognitionJson.parse("{\"cards\":[]}", false).isEmpty())
    }
    @Test fun openAiRequiresCompletedNonRefusedMessage() {
        val payload = response(observation())
        val root = JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
            .put("type", "message").put("status", "completed").put("content", JSONArray().put(JSONObject()
                .put("type", "output_text").put("text", payload)))))
        assertEquals(payload, RecognitionJson.openAiText(root))
        rejects { RecognitionJson.openAiText(JSONObject(root.toString()).put("status", "incomplete")) }
        root.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            .put("type", "refusal").put("refusal", "PRIVATE PAYLOAD")
        rejects { RecognitionJson.openAiText(root) }
    }
    @Test fun anthropicRequiresEndTurnNotTokenTruncationOrRefusal() {
        val payload = response(observation())
        val root = JSONObject().put("type", "message").put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload)))
        assertEquals(payload, RecognitionJson.anthropicText(root))
        rejects { RecognitionJson.anthropicText(JSONObject(root.toString()).put("stop_reason", "max_tokens")) }
        rejects { RecognitionJson.anthropicText(JSONObject(root.toString()).put("stop_reason", "refusal")) }
    }
    @Test fun requestsUseDocumentedEndpointsAndStrictStructuredFormats() {
        // A fixed test token is never used in a network call.
        val openAi = RecognitionRequests.openAiRequest("test-token", "configured-openai-model", listOf("encoded-image"), false)
        assertEquals("https://api.openai.com/v1/responses", openAi.url.toString())
        val openBody = JSONObject(Buffer().also { openAi.body!!.writeTo(it) }.readUtf8())
        assertEquals("configured-openai-model", openBody.getString("model"))
        assertFalse(openBody.getBoolean("store"))
        assertTrue(openBody.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        assertFalse(openBody.toString().contains("test-token"))
        val anthropic = RecognitionRequests.anthropicRequest("test-token", "configured-anthropic-model", listOf("encoded-image"), true)
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.url.toString())
        assertEquals("2023-06-01", anthropic.header("anthropic-version"))
        val anthropicBody = JSONObject(Buffer().also { anthropic.body!!.writeTo(it) }.readUtf8())
        assertEquals("configured-anthropic-model", anthropicBody.getString("model"))
        assertEquals("json_schema", anthropicBody.getJSONObject("output_config").getJSONObject("format").getString("type"))
        assertFalse(anthropicBody.toString().contains("test-token"))
    }
    @Test fun statusErrorsNeverContainProviderPayload() {
        val http = IntegrationHttp()
        assertEquals(IntegrationException.Kind.AUTHENTICATION, http.statusError(401).kind)
        assertEquals(IntegrationException.Kind.RATE_LIMIT, http.statusError(429).kind)
        assertEquals(IntegrationException.Kind.UNAVAILABLE, http.statusError(403).kind)
        assertEquals(IntegrationException.Kind.UNSUPPORTED, http.statusError(400).kind)
    }
    private fun rejects(block: () -> Any?) {
        try { block(); fail("Expected rejection") }
        catch (e: IntegrationException) { assertFalse(e.message.orEmpty().contains("PRIVATE PAYLOAD")) }
    }
}
