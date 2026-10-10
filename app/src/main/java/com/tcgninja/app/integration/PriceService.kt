package com.tcgninja.app.integration

import com.tcgninja.app.data.CardRecord
import com.tcgninja.app.data.PriceQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** A source-supplied candidate, NOT a confirmed product match. */
data class PriceCandidate(val title: String, val url: String)

class PriceService {
    private val http = IntegrationHttp()

    /** One small public search request; never follows candidates or bypasses a browser challenge. */
    suspend fun search(card: CardRecord): List<PriceCandidate> = withContext(Dispatchers.IO) {
        if (card.name.isBlank()) throw IntegrationException(IntegrationException.Kind.SETTINGS,
            "Enter a card name before searching for a price match.")
        val url = pricechartingSearchUrl(card)
        val html = http.text(Request.Builder().url(url).header("Accept", "text/html")
            .header("User-Agent", "TCGNinja/0.1 (personal collection; user-requested product search)")
            .build(), 1_500_000, "text/html")
        PriceChartingParser.search(html, card)
    }

    /**
     * Caller must obtain confirmation of this exact product URL/edition/finish before saving it.
     * One request captures available raw AND graded quotes; grade chooses the primary quote.
     * Failure throws without modifying the original card or its previous quotes. No retries.
     */
    suspend fun refresh(card: CardRecord): CardRecord = withContext(Dispatchers.IO) {
        val url = PriceChartingParser.productUrl(card.pricechartingUrl)
        PriceChartingParser.requireIdentity(card)
        val basis = card.grade.ifBlank { "Ungraded" }
        if (basis !in PriceChartingParser.SUPPORTED_BASES) throw IntegrationException(
            IntegrationException.Kind.UNSUPPORTED,
            "Choose an exact supported PriceCharting grade label. Grading companies are not interchangeable."
        )
        val html = http.text(Request.Builder().url(url)
            .header("Accept", "text/html").header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "TCGNinja/0.1 (personal collection; user-requested price lookup)")
            .build(), 3_000_000, "text/html")
        val quotes = PriceChartingParser.parse(html, card, System.currentTimeMillis())
        val selected = quotes.singleOrNull { it.basis == basis } ?: throw IntegrationException(
            IntegrationException.Kind.NOT_FOUND,
            "PriceCharting has no verified USD estimate for the selected grade. Your previous estimate is unchanged."
        )
        card.copy(priceCents = selected.cents, priceSource = selected.source,
            priceUpdatedAt = selected.updatedAt, priceBasis = selected.basis,
            pricechartingUrl = url.toString(),
            priceQuotes = card.priceQuotes.filterNot { it.source == "PriceCharting" } + quotes)
    }
}

/** Search links are suggestions, never matched product links and never fetched by PriceService. */
fun pricechartingSearchUrl(card: CardRecord): String = "https://www.pricecharting.com/search-products".toHttpUrl()
    .newBuilder().addQueryParameter("type", "prices").addQueryParameter("q", searchTerms(card)).addQueryParameter("go", "Go").build().toString()

fun tcgplayerSearchUrl(card: CardRecord): String = "https://www.tcgplayer.com/search/pokemon/product".toHttpUrl()
    .newBuilder().addQueryParameter("productLineName", "pokemon").addQueryParameter("q", searchTerms(card))
    .addQueryParameter("view", "grid").build().toString()

private fun searchTerms(card: CardRecord) = listOf(card.name, card.setName, card.number, card.finish.takeUnless { it == "Unknown" }.orEmpty())
    .filter(String::isNotBlank).joinToString(" ")

/** Deterministic parser. HTML is source data only; scripts and instructions are never executed. */
internal object PriceChartingParser {
    val SUPPORTED_BASES = listOf("Ungraded", "Grade 7", "Grade 8", "Grade 9", "Grade 9.5", "PSA 10")

    fun search(html: String, card: CardRecord): List<PriceCandidate> {
        if (html.length > 1_500_000) invalidResponse()
        val document = Jsoup.parse(html, "https://www.pricecharting.com")
        val table = document.select("table#games_table").singleOrNull()
            ?: throw IntegrationException(IntegrationException.Kind.UNAVAILABLE,
                "PriceCharting search is unavailable or requires a browser check. Open the search link in your browser and paste the confirmed product URL.")
        val candidates = linkedMapOf<String, PriceCandidate>()
        val requestedName = norm(card.name.replace(Regex("\\[[^]]+]"), ""))
        if (requestedName.isBlank()) return emptyList()
        for (row in table.select("tr")) {
            for (link in row.select("td.title a[href]")) {
                val url = runCatching { productUrl(link.absUrl("href")) }.getOrNull() ?: continue
                val name = link.text().trim()
                val normalized = norm(name)
                // Search candidates can differ in set/variant; the user must review those explicitly.
                if (!(" $normalized ").contains(" $requestedName ")) continue
                val console = row.select("td.console").text().trim()
                val title = listOf(name, console).filter(String::isNotBlank).joinToString(" — ").take(300)
                candidates.putIfAbsent(url.toString(), PriceCandidate(title, url.toString()))
                if (candidates.size == 20) return candidates.values.toList()
            }
        }
        return candidates.values.toList()
    }

    fun productUrl(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: badUrl()
        if (!url.isHttps || url.host !in setOf("www.pricecharting.com", "pricecharting.com") || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.query != null ||
            !Regex("/game/pokemon-[a-z0-9-]+/[a-z0-9-]+").matches(url.encodedPath)) badUrl()
        // Normalize the naked host in advance. Automatic HTTP redirects are disabled.
        return url.newBuilder().host("www.pricecharting.com").build()
    }

    private fun badUrl(): Nothing = throw IntegrationException(IntegrationException.Kind.SETTINGS,
        "Paste and confirm an HTTPS PriceCharting Pokémon product URL (/game/…), without a query or fragment. Search URLs cannot be priced.")

    fun requireIdentity(card: CardRecord) {
        if (listOf(card.name, card.setName, card.number).any { it.isBlank() || norm(it) == "unknown" }) mismatch(
            "Enter the card name, set, and collector number before confirming a price match."
        )
        if (card.finish.isBlank() || norm(card.finish) in setOf("unknown", "other")) mismatch(
            "Confirm the card's finish and the matching product URL before retrieving an estimate."
        )
    }

    fun parse(html: String, card: CardRecord, timestamp: Long): List<PriceQuote> {
        if (html.length > 3_000_000) invalidResponse()
        requireIdentity(card)
        val url = productUrl(card.pricechartingUrl)
        val document = Jsoup.parse(html, url.toString())
        val canonical = document.select("link[rel=canonical]")
        if (canonical.size != 1 || productUrl(canonical.single().attr("href")) != url) mismatch()
        val currencies = document.select("#dropdown_selected_currency")
        if (currencies.size != 1 || currencies.single().text().trim() != "USD") throw IntegrationException(
            IntegrationException.Kind.INVALID_RESPONSE, "The source did not confirm USD prices. No estimate was changed."
        )
        validateProduct(document, card)
        val tables = document.select("table#price_data")
        if (tables.size != 1) invalidResponse()
        val table = tables.single()
        // The actual public page repeats Grade 9/9.5/PSA 10 in a second, mobile-only
        // thead/tbody marked additional_data. Bind the main header to its own tbody.
        val headerRows = table.children().filter { it.tagName() == "thead" }
            .flatMap { it.children() }.filter { it.tagName() == "tr" && !it.hasClass("additional_data") }
        if (headerRows.size != 1) invalidResponse()
        val headers = headerRows.single().children()
        val mainBody = headerRows.single().parent()?.nextElementSibling() ?: invalidResponse()
        if (mainBody.tagName() != "tbody") invalidResponse()
        val dataRows = mainBody.children().filter { it.tagName() == "tr" && !it.hasClass("additional_data") }
        if (dataRows.isEmpty()) invalidResponse()
        val cells = dataRows.first().children()
        if (cells.size < headers.count { it.tagName() == "th" }) invalidResponse()
        val found = linkedMapOf<String, Long>()
        headers.forEachIndexed { i, heading ->
            val label = heading.text().trim()
            if (heading.tagName() != "th" || label !in SUPPORTED_BASES) return@forEachIndexed
            if (headers.count { it.text().trim() == label } != 1) invalidResponse()
            val cell = cells.getOrNull(i) ?: invalidResponse()
            if (cell.tagName() != "td" || cell.attr("colspan").let { it.isNotEmpty() && it != "1" }) invalidResponse()
            val prices = cell.children().filter { it.hasClass("price") && !it.hasClass("change") }
            if (prices.size > 1) invalidResponse()
            if (prices.isEmpty()) {
                if (cell.select(".change").isNotEmpty() || cell.text().trim() !in setOf("", "-", "--", "N/A", "n/a")) invalidResponse()
                return@forEachIndexed
            }
            val text = prices.single().text().trim()
            if (text in setOf("", "-", "--", "N/A", "n/a")) return@forEachIndexed
            val cents = dollars(text)
            // Some product pages duplicate the guide below the chart. If it disagrees, fail closed.
            verifyFullGuide(document, label, cents)
            found[label] = cents
        }
        if (found.isEmpty()) throw IntegrationException(IntegrationException.Kind.NOT_FOUND,
            "No verified USD price estimates were available for this product.")
        return found.map { (basis, cents) -> PriceQuote(cents, "PriceCharting", basis, timestamp, url.toString()) }
    }

    private fun validateProduct(document: Document, card: CardRecord) {
        val titles = document.select("h1#product_name")
        if (titles.size != 1) mismatch()
        val title = titles.single()
        val sets = title.select("a[href^=/console/]")
        if (sets.size != 1) mismatch()
        val setName = norm(sets.single().text()).removePrefix("pokemon ")
        if (setName != norm(card.setName).removePrefix("pokemon ")) mismatch()
        // English endpoint alone does not imply an English printing.
        if (Regex("\\b(japanese|korean|chinese|german|french|spanish|italian|portuguese)\\b").containsMatchIn(setName)) mismatch()
        val productName = title.clone().apply { select("a").remove() }.text().trim()
        val parts = Regex("^(.+?)\\s+#([A-Za-z0-9.-]+)$").matchEntire(productName) ?: mismatch()
        if (number(parts.groupValues[2]) != number(card.number.substringBefore('/').trim())) mismatch()
        val bracket = Regex("\\[([^]]+)]")
        val pageName = parts.groupValues[1]
        val variants = bracket.findAll(pageName).map { norm(it.groupValues[1]) }.toList()
        if (norm(bracket.replace(pageName, "")) != norm(bracket.replace(card.name, ""))) mismatch()
        val finish = finish(card.finish)
        if (finish == "reverse" && variants.none { finish(it) == "reverse" }) mismatch()
        if (variants.any { finish(it) == "reverse" } && finish != "reverse") mismatch()
        if (variants.any { finish(it) == "holo" } && finish != "holo") mismatch()
        if (variants.any { finish(it) == "normal" } && finish != "normal") mismatch()
        val explicitVariants = bracket.findAll(card.name).map { norm(it.groupValues[1]) }.toSet() +
            card.mechanics.split(',', ';', '|').map(::norm)
        val nonFinishVariants = variants.filter { finish(it) !in setOf("normal", "holo", "reverse") }
        if (nonFinishVariants.any { it !in explicitVariants }) mismatch()
        // Do not silently price an explicitly identified edition/stamp against the generic printing.
        val editionMarkers = setOf("1st edition", "first edition", "shadowless", "unlimited", "cosmos holo", "stamped")
        if (explicitVariants.any { it in editionMarkers && it !in variants }) mismatch()
        // Unqualified product titles are permitted only because the user has explicitly confirmed
        // this URL. They do not themselves prove holo versus normal; no match is auto-discovered.
    }

    private fun verifyFullGuide(document: Document, label: String, cents: Long) {
        val rows = document.select("#full-prices table tr").filter {
            val cells = it.children()
            cells.size >= 2 && cells[0].text().trim() == label
        }
        if (rows.size > 1) invalidResponse()
        if (rows.isNotEmpty()) {
            val cell: Element = rows.single().children()[1]
            if (!cell.hasClass("price") || dollars(cell.text().trim()) != cents) invalidResponse()
        }
    }

    private fun dollars(raw: String): Long {
        if (!Regex("\\$(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)\\.[0-9]{2}").matches(raw)) invalidResponse()
        return try { BigDecimal(raw.removePrefix("$").replace(",", "")).movePointRight(2).longValueExact().also {
            if (it <= 0) throw IllegalArgumentException()
        } } catch (_: Exception) { invalidResponse() }
    }

    private fun number(value: String): String = norm(value).replace(Regex("(?<![0-9])0+(?=[0-9])"), "")
    private fun finish(value: String): String = when (val n = norm(value)) {
        "normal", "non holo", "non holofoil", "non holographic" -> "normal"
        "holo", "holofoil", "holographic" -> "holo"
        "reverse", "reverse holo", "reverse holofoil", "reverse holographic" -> "reverse"
        else -> n
    }
    private fun norm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun mismatch(message: String = "This page does not unambiguously match the card's name, set, number, and variant. Confirm the correct product URL."): Nothing =
        throw IntegrationException(IntegrationException.Kind.AMBIGUOUS_MATCH, message)
}
