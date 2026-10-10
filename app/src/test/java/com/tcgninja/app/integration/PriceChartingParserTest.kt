package com.tcgninja.app.integration

import com.tcgninja.app.data.CardRecord
import org.junit.Assert.*
import org.junit.Test

class PriceChartingParserTest {
    private val url = "https://www.pricecharting.com/game/pokemon-base-set/charizard-4"
    private val card = CardRecord(name = "Charizard", setName = "Base Set", number = "004/102", finish = "Holofoil", pricechartingUrl = url)

    // Synthetic fixture mirrors the inspected public page's table structure; no live requests.
    private fun html(title: String = "Charizard #4", currency: String = "USD"): String = """
        <link rel="canonical" href="$url">
        <a id="dropdown_selected_currency">$currency</a>
        <h1 id="product_name">$title <a href="/console/pokemon-base-set">Pokemon Base Set</a></h1>
        <div>Advertisement price ${'$'}0.01</div>
        <table id="price_data"><thead><tr>
        <th>Ungraded</th><th>Grade 7</th><th>Grade 8</th><th>Grade 9</th><th>Grade 9.5</th><th>PSA 10</th>
        </tr></thead><tbody><tr>
        <td id="used_price"><span class="price js-price">${'$'}394.23</span><span class="change">-<span class="js-price">${'$'}52.06</span></span></td>
        <td id="complete_price"><span class="price js-price">${'$'}787.50</span></td>
        <td id="new_price"><span class="price js-price">${'$'}1,342.50</span></td>
        <td><span class="price js-price">${'$'}3,309.38</span></td>
        <td><span class="price js-price">${'$'}7,888.50</span></td>
        <td><span class="price js-price">${'$'}24,077.50</span></td>
        </tr><tr><td>volume: 3 sales</td></tr></tbody></table>
        <div id="full-prices"><table><tr><td>Ungraded</td><td class="price js-price">${'$'}394.23</td></tr></table></div>
    """.trimIndent()

    @Test fun exactLabelsNotGenericCellIdsOrFirstDollar() {
        val quotes = PriceChartingParser.parse(html(), card, 123L)
        assertEquals(listOf(39423L, 78750L, 134250L, 330938L, 788850L, 2407750L), quotes.map { it.cents })
        assertEquals(PriceChartingParser.SUPPORTED_BASES, quotes.map { it.basis })
        assertTrue(quotes.all { it.url == url && it.updatedAt == 123L && it.source == "PriceCharting" })
        assertFalse(quotes.any { it.basis == "PSA 9" })
    }

    @Test fun labelsControlMappingEvenIfGenericIdsMove() {
        val changed = html().replace("<th>Grade 7</th><th>Grade 8</th>", "<th>Grade 8</th><th>Grade 7</th>")
        val quotes = PriceChartingParser.parse(changed, card, 0)
        assertEquals(134250L, quotes.single { it.basis == "Grade 7" }.cents)
    }

    @Test fun realPageMobileDuplicateHeadersDoNotShiftPrimaryGradeColumns() {
        val mobile = """<thead><tr class="additional_data extra_row"><td></td></tr>
          <tr class="additional_data"><th>Grade 9</th><th>Grade 9.5</th><th>PSA 10</th></tr></thead>
          <tbody><tr class="additional_data"><td><span class="price js-price">${'$'}3,309.38</span></td>
          <td><span class="price js-price">${'$'}7,888.50</span></td><td><span class="price js-price">${'$'}24,077.50</span></td></tr></tbody>"""
        val page = html().replace("</tbody></table>", "</tbody>$mobile</table>")
        assertEquals(6, PriceChartingParser.parse(page, card, 0).size)
        assertEquals(2407750L, PriceChartingParser.parse(page, card, 0).single { it.basis == "PSA 10" }.cents)
    }

    @Test fun aPsaNineColumnIsNeverRelabeledGradeNine() {
        val quotes = PriceChartingParser.parse(html().replace("<th>Grade 9</th>", "<th>PSA 9</th>"), card, 0)
        assertFalse(quotes.any { it.basis == "Grade 9" || it.basis == "PSA 9" })
    }

    @Test fun missingSelectedGradeIsNotZeroOrAnotherGrade() {
        val quotes = PriceChartingParser.parse(html().replace("${'$'}24,077.50", "-"), card, 0)
        assertFalse(quotes.any { it.basis == "PSA 10" })
        assertEquals(5, quotes.size)
    }

    @Test fun rejectsCurrencyMismatchEvenWithDollarSymbol() = rejects { PriceChartingParser.parse(html(currency = "CAD"), card, 0) }
    @Test fun rejectsUnknownCurrency() = rejects { PriceChartingParser.parse(html().replace("id=\"dropdown_selected_currency\"", "id=\"other\""), card, 0) }
    @Test fun rejectsSetMismatch() = rejects { PriceChartingParser.parse(html(), card.copy(setName = "Base Set 2"), 0) }
    @Test fun rejectsNameMismatch() = rejects { PriceChartingParser.parse(html(), card.copy(name = "Blastoise"), 0) }
    @Test fun rejectsNumberMismatch() = rejects { PriceChartingParser.parse(html(), card.copy(number = "5/102"), 0) }
    @Test fun rejectsReverseOnGenericProduct() = rejects { PriceChartingParser.parse(html(), card.copy(finish = "Reverse Holofoil"), 0) }
    @Test fun rejectsGenericOnReverseProduct() = rejects { PriceChartingParser.parse(html("Charizard [Reverse Holo] #4"), card, 0) }
    @Test fun acceptsExplicitReverseMatch() {
        assertEquals(6, PriceChartingParser.parse(html("Charizard [Reverse Holo] #4"), card.copy(finish = "Reverse Holofoil"), 0).size)
    }
    @Test fun rejectsUnconfirmedEdition() = rejects { PriceChartingParser.parse(html("Charizard [1st Edition] #4"), card, 0) }
    @Test fun rejectsNamedEditionAgainstGeneric() = rejects { PriceChartingParser.parse(html(), card.copy(mechanics = "1st Edition"), 0) }
    @Test fun acceptsEditionOnlyWhenExplicitlyMatched() {
        assertEquals(6, PriceChartingParser.parse(html("Charizard [1st Edition] #4"), card.copy(mechanics = "1st Edition"), 0).size)
    }
    @Test fun requiresKnownFinish() = rejects { PriceChartingParser.parse(html(), card.copy(finish = "Unknown"), 0) }
    @Test fun rejectsCanonicalMismatch() = rejects { PriceChartingParser.parse(html().replace("href=\"$url\"", "href=\"${url}0\""), card, 0) }
    @Test fun rejectsPriceDeltaWithoutEstimate() = rejects {
        PriceChartingParser.parse(html().replace("<span class=\"price js-price\">${'$'}394.23</span>", ""), card, 0)
    }
    @Test fun rejectsDisagreeingDuplicateGuide() = rejects {
        PriceChartingParser.parse(html().replace("<td class=\"price js-price\">${'$'}394.23", "<td class=\"price js-price\">${'$'}394.24"), card, 0)
    }
    @Test fun rejectsMalformedAmountAndOverflow() {
        listOf("${'$'}1,34.50", "- ${'$'}1.00", "${'$'}1.001", "${'$'}0.00", "${'$'}9999999999999999999999.99").forEach { price ->
            rejects { PriceChartingParser.parse(html().replace("${'$'}787.50", price), card, 0) }
        }
    }
    @Test fun rejectsDuplicateGradeAndMissingTable() {
        rejects { PriceChartingParser.parse(html().replace("<th>Grade 8</th>", "<th>Grade 7</th>"), card, 0) }
        rejects { PriceChartingParser.parse(html().replace("id=\"price_data\"", "id=\"new_layout\""), card, 0) }
    }
    @Test fun urlAllowlistRejectsUnsafeOrUnmatchedTargets() {
        listOf("http://www.pricecharting.com/game/pokemon-base-set/charizard-4",
            "https://pricecharting.com.attacker.test/game/pokemon-base-set/charizard-4",
            "https://user:password@www.pricecharting.com/game/pokemon-base-set/charizard-4",
            "$url?currency=CAD", "$url#prices", "https://www.pricecharting.com/search-products?q=charizard",
            "https://www.pricecharting.com/game/pokemon-base-set/charizard-4%2fextra",
            "https://www.pricecharting.com:444/game/pokemon-base-set/charizard-4").forEach { target ->
            rejects { PriceChartingParser.productUrl(target) }
        }
        assertEquals(url, PriceChartingParser.productUrl(url.replace("www.", "")).toString())
    }

    @Test fun searchReturnsOnlySuppliedProductsDeduplicatedAndBounded() {
        val rows = (1..30).joinToString("") {
            "<tr><td class='title'><a href='/game/pokemon-base-set/charizard-$it'>Charizard #$it</a><a href='/game/pokemon-base-set/charizard-$it'>Charizard #$it</a></td><td class='console'>Pokemon Base Set</td></tr>"
        }
        val noise = "<tr><td class='title'><a href='https://evil.test/game/pokemon-base-set/charizard-4'>Charizard</a><a href='/game/pokemon-base-set/pikachu-58'>Pikachu #58</a></td></tr>"
        val results = PriceChartingParser.search("<table id='games_table'>$noise$rows</table>", card)
        assertEquals(20, results.size)
        assertEquals(20, results.map { it.url }.toSet().size)
        assertTrue(results.all { it.title.contains("Pokemon Base Set") })
        assertTrue(results.all { it.url.startsWith("https://www.pricecharting.com/game/pokemon-base-set/charizard-") })
    }
    @Test fun searchChallengeFailsWithSafeMessage() = rejects {
        PriceChartingParser.search("<html><title>Just a moment...</title><script>challenge</script></html>", card)
    }
    @Test fun verifiedEmptySearchReturnsEmpty() {
        assertTrue(PriceChartingParser.search("<table id='games_table'></table>", card).isEmpty())
    }

    private fun rejects(block: () -> Any?) {
        try { block(); fail("Expected safe rejection") }
        catch (e: IntegrationException) { assertFalse(e.message.orEmpty().contains("<html>")) }
    }
}
