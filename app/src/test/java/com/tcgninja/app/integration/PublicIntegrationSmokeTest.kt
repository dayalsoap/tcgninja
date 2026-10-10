package com.tcgninja.app.integration

import com.tcgninja.app.data.CardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Explicit opt-in only: three public reads, no AI provider, no API keys, no price assertions. */
class PublicIntegrationSmokeTest {
    @Before fun requireOptIn() { assumeTrue(System.getProperty("tcgninja.livePublicSmoke") == "true") }

    @Test fun englishCatalogSearchAndDetails() = runBlocking {
        val catalog = CatalogService()
        val matches = catalog.search("Charizard")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.size <= 50)
        val card = catalog.details("base1-4")
        assertEquals("Charizard", card.name)
        assertEquals("Base Set", card.setName)
        assertEquals("4/102", card.number)
        assertEquals("Pokémon", card.category)
        assertNull(card.priceCents)
        println("Public smoke: TCGdex /v2/en/cards; /v2/en/cards/base1-4; catalogId=${card.catalogId}; results=${matches.size}")
    }

    @Test fun confirmedCharizardProductHasAttributableUsdQuotes() = runBlocking {
        val url = "https://www.pricecharting.com/game/pokemon-base-set/charizard-4"
        val card = CardRecord(name = "Charizard", setName = "Base Set", number = "4/102",
            finish = "Holofoil", grade = "Ungraded", pricechartingUrl = url)
        val result = PriceService().refresh(card)
        assertTrue(result.priceCents!! > 0)
        assertEquals("PriceCharting", result.priceSource)
        assertEquals("Ungraded", result.priceBasis)
        assertNotNull(result.priceUpdatedAt)
        assertTrue(result.priceQuotes.any { it.basis == "PSA 10" })
        assertTrue(result.priceQuotes.all { it.url == url && it.source == "PriceCharting" })
        println("Public smoke: $url; USD; labels=${result.priceQuotes.map { it.basis }}; fetchedAt=${result.priceUpdatedAt}")
    }
}
