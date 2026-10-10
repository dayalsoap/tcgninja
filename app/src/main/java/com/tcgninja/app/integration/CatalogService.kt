package com.tcgninja.app.integration

import com.tcgninja.app.data.CardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** English TCGdex REST v2. Search returns briefs; select one and call details before committing. */
class CatalogService {
    private val http = IntegrationHttp()

    suspend fun search(query: String): List<CardRecord> = withContext(Dispatchers.IO) {
        val value = query.trim()
        if (value.isEmpty()) return@withContext emptyList()
        if (value.length > 120) throw IntegrationException(IntegrationException.Kind.SETTINGS, "Use a shorter card search.")
        val url = "$BASE/cards".toHttpUrl().newBuilder()
            .addQueryParameter("name", "like:$value")
            .addQueryParameter("pagination:page", "1")
            .addQueryParameter("pagination:itemsPerPage", "50").build()
        val text = http.text(Request.Builder().url(url).build(), 500_000, "application/json")
        try {
            val items = JSONArray(text)
            if (items.length() > 50) invalidResponse()
            (0 until items.length()).map { CatalogJson.parse(items.getJSONObject(it), brief = true) }
        } catch (e: IntegrationException) { throw e }
        catch (_: Exception) { invalidResponse() }
    }

    suspend fun details(id: String): CardRecord = withContext(Dispatchers.IO) {
        if (!Regex("[A-Za-z0-9._-]{1,100}").matches(id)) throw IntegrationException(
            IntegrationException.Kind.SETTINGS, "Select a valid catalog card first."
        )
        val text = http.text(Request.Builder().url("$BASE/cards/$id").build(), 500_000, "application/json")
        try {
            val result = CatalogJson.parse(JSONObject(text), brief = false)
            if (result.catalogId != id) invalidResponse()
            result
        } catch (e: IntegrationException) { throw e }
        catch (_: Exception) { invalidResponse() }
    }

    private companion object { const val BASE = "https://api.tcgdex.net/v2/en" }
}

internal object CatalogJson {
    fun parse(json: JSONObject, brief: Boolean): CardRecord {
        val id = json.text("id")
        val name = json.text("name")
        if (id.isBlank() || name.isBlank()) invalidResponse()
        val rawCategory = json.text("category")
        val category = when (rawCategory) { "Pokemon", "Pokémon" -> "Pokémon"; "" -> "Misc"; else -> rawCategory }
        val stage = when (val value = json.text("stage")) { "Stage1" -> "Stage 1"; "Stage2" -> "Stage 2"; else -> value }
        val subtype = when (category) {
            "Trainer" -> when (val value = json.text("trainerType")) { "Tool" -> "Pokémon Tool"; else -> value }
            "Energy" -> when (val value = json.text("energyType")) { "Normal" -> "Basic"; else -> value }
            "Pokémon" -> stage
            else -> ""
        }
        val dex = json.optJSONArray("dexId")?.let { array ->
            if (array.length() > 12) invalidResponse()
            (0 until array.length()).mapNotNull { i ->
                (array.opt(i) as? Number)?.toInt()?.takeIf { it > 0 }?.let { "National Pokédex #$it" }
            }.joinToString(", ")
        }.orEmpty()
        // A dex ID is not an English species name; preserve the supplied identities without guessing
        // away owner prefixes, forms, or multiple species. Name search still finds printed names.
        val species = json.strings("species").ifBlank { dex }
        val set = json.optJSONObject("set")
        val localId = json.text("localId")
        val count = set?.optJSONObject("cardCount")?.optInt("official", 0) ?: 0
        val number = if (localId.isNotBlank() && count > 0) "$localId/$count" else localId
        val mechanic = json.text("suffix")
        val notes = buildString {
            append("Catalog: TCGdex (English), ").append(id).append(".")
            if (brief) append(" Search result only; load full details before committing.")
            if (dex.isNotEmpty()) append("\n").append(dex)
            append("\nConfirm the physical copy's finish, edition, condition, and grade. Catalog variants are possibilities, not a detected finish.")
            if (!brief) append(catalogText(json))
        }
        return CardRecord(name = name, category = category, subtype = subtype,
            types = json.strings("types"), species = species, hp = json.scalar("hp"), stage = stage,
            setName = set?.text("name").orEmpty(), number = number, rarity = json.text("rarity"),
            mechanics = mechanic,
            catalogId = id, notes = notes, imagePath = imageUrl(json.text("image")),
            // Only supplied URLs are accepted. Product IDs alone may refer to multiple variants.
            tcgplayerUrl = productUrl(json.optJSONObject("tcgplayer")?.text("url").orEmpty(), "tcgplayer.com"),
            pricechartingUrl = productUrl(json.optJSONObject("pricecharting")?.text("url").orEmpty(), "pricecharting.com")
        )
    }

    /** Source-attributed readable metadata, not executable HTML or a completeness guarantee. */
    private fun catalogText(json: JSONObject): String {
        val lines = mutableListOf<String>()
        fun clean(value: String) = Jsoup.parseBodyFragment(value).text().take(1200)
        fun add(label: String, value: String) { if (value.isNotBlank()) lines += "$label: ${clean(value)}" }
        add("Description", json.text("description"))
        add("Evolves from", json.text("evolveFrom"))
        add("Effect", json.text("effect"))
        for (field in listOf("attacks", "abilities")) {
            val array = json.optJSONArray(field) ?: continue
            for (i in 0 until minOf(array.length(), 16)) {
                val item = array.optJSONObject(i) ?: continue
                val content = listOf(item.text("name"), item.text("type"),
                    item.strings("cost").takeIf(String::isNotBlank)?.let { "Cost: $it" }.orEmpty(),
                    item.scalar("damage").takeIf(String::isNotBlank)?.let { "Damage: $it" }.orEmpty(), item.text("effect"))
                    .filter(String::isNotBlank).joinToString(" — ")
                add(if (field == "attacks") "Attack" else "Ability", content)
            }
            if (array.length() > 16) lines += "Additional $field omitted."
        }
        val rules = json.opt("rules")
        if (rules is String) add("Rules", rules)
        else if (rules is JSONArray) for (i in 0 until minOf(rules.length(), 16)) {
            (rules.opt(i) as? String)?.let { add("Rule", it) }
        }
        for (field in listOf("weaknesses", "resistances")) {
            val array = json.optJSONArray(field) ?: continue
            for (i in 0 until minOf(array.length(), 12)) {
                val item = array.optJSONObject(i) ?: continue
                add(if (field == "weaknesses") "Weakness" else "Resistance", listOf(item.text("type"), item.scalar("value"))
                    .filter(String::isNotBlank).joinToString(" "))
            }
        }
        add("Retreat cost", json.scalar("retreat"))
        val result = lines.joinToString("\n")
        return if (result.isEmpty()) "" else "\n" + if (result.length <= 8000) result
            else result.take(7900) + "\n[Additional catalog text truncated.]"
    }

    private fun imageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return ""
        if (!url.isHttps || url.host != "assets.tcgdex.net" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return ""
        if (!url.encodedPath.startsWith("/en/") || url.query != null || url.fragment != null) return ""
        return if (Regex(".*\\.(?:png|jpg|jpeg|webp)$").matches(url.encodedPath)) url.toString() else "${url.toString().trimEnd('/')}/high.webp"
    }

    private fun productUrl(raw: String, host: String): String {
        val url = raw.toHttpUrlOrNull() ?: return ""
        if (!url.isHttps || url.host !in setOf(host, "www.$host") || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return ""
        if (host == "pricecharting.com") return runCatching { PriceChartingParser.productUrl(raw) }.getOrNull()?.toString().orEmpty()
        return if (Regex("/product/[0-9]+(?:/[^?#]*)?").matches(url.encodedPath)) url.toString() else ""
    }

    private fun JSONObject.text(key: String): String {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return ""
        if (value !is String || value.length > 2000) invalidResponse()
        return value.trim()
    }
    private fun JSONObject.scalar(key: String): String = when (val value = opt(key)) {
        null, JSONObject.NULL -> ""
        is Number -> value.toString()
        is String -> value.take(30)
        else -> invalidResponse()
    }
    private fun JSONObject.strings(key: String): String {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return ""
        if (value !is JSONArray || value.length() > 12) invalidResponse()
        return (0 until value.length()).map {
            val item = value.get(it) as? String ?: invalidResponse()
            if (item.length > 150) invalidResponse()
            item
        }.joinToString(", ")
    }
}
