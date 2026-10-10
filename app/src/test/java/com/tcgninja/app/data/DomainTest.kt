package com.tcgninja.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun moneyIsExactAndNeverTreatsUnknownAsZero() {
        assertNull(Money.parseCents("  "))
        assertEquals(501L, Money.parseCents("$5.01"))
        assertEquals(123456L, Money.parseCents("1,234.56"))
        assertEquals(5L, Money.parseCents(".05"))
        assertEquals(Long.MAX_VALUE, Money.parseCents("92233720368547758.07"))
        assertEquals("$0.00", Money.format(0))
        listOf("-1", "$", "1,23", "1e3", "NaN", "1.001", "92233720368547758.08").forEach {
            assertThrows(IllegalArgumentException::class.java) { Money.parseCents(it) }
        }
    }

    @Test fun filtersComposeAndBoundsAreInclusive() {
        val card = CardRecord(name = "M Charizard EX", category = "Pokemon", species = "Charizard",
            subtype = "Stage2", finish = "Holofoil", mechanics = "MEGA, EX", priceCents = 500)
        assertTrue(CardFilter(query = "charIZARD", category = "Pokémon", subtype = "Stage 2",
            finish = "Holofoil", mechanic = "EX", minCents = 500, maxCents = 500).matches(card))
        assertFalse(CardFilter(mechanic = "ex").matches(card))
        assertFalse(CardFilter(maxCents = 500).matches(card.copy(priceCents = null)))
        assertTrue(CardFilter(query = "pokemon").matches(CardRecord(name = "Pokémon")))
        assertThrows(IllegalArgumentException::class.java) { CardFilter(minCents = 600, maxCents = 500) }
    }

    @Test fun historicalTaxonomyAndUnknownValuesSurvive() {
        assertTrue("Fairy" in Taxonomy.types)
        assertTrue("Pokémon Tool" in Taxonomy.subtypes("Trainer"))
        assertFalse("ACE SPEC" in Taxonomy.subtypes("Trainer"))
        assertTrue(CardFilter(subtype = "Basic").matches(CardRecord(category = "Energy", subtype = "Normal")))
        assertTrue(CardFilter(subtype = "Pokémon Tool").matches(CardRecord(category = "Trainer", subtype = "Tool")))
        val unknown = CardRecord(category = "Future category", subtype = "Future subtype", species = "New species",
            rarity = "Future rarity", finish = "Future foil", mechanics = "Future mechanic", number = "001/102")
        assertEquals(unknown, CardJson.fromJson(CardJson.toJson(unknown)))
        assertTrue(CardFilter(category = "Misc").matches(unknown))
        assertTrue(CardFilter(subtype = "Future subtype").matches(unknown))
    }

    @Test fun representativeCardsAndMultipleSpeciesRemainDistinct() {
        val first = CardRecord(name = "Charizard", species = "Charizard", catalogId = "base1-4", setName = "Base Set", number = "4")
        val second = CardRecord(name = "Charizard", species = "Charizard", catalogId = "base4-4", setName = "Base Set 2", number = "4")
        assertNotEquals(first.id, second.id)
        val tagTeam = CardRecord(name = "Pikachu & Zekrom GX", species = "Pikachu, Zekrom", catalogId = "sm9-33", mechanics = "TAG TEAM-GX")
        assertTrue(CardFilter(query = "zekrom").matches(tagTeam))
        val aceEnergy = CardRecord(name = "Neo Upper Energy", category = "Energy", subtype = "Special", mechanics = "ACE SPEC")
        assertTrue(CardFilter(category = "Energy", subtype = "Special", mechanic = "ACE SPEC").matches(aceEnergy))
    }

    @Test fun explicitJsonRoundTripsEveryFieldAndRawAndGradedQuotes() {
        val card = CardRecord(id = "one", collectionId = "c", name = "Pikachu", category = "Pokémon", subtype = "Basic",
            types = "Lightning", species = "Pikachu", hp = "60", stage = "Basic", setName = "Jungle", number = "60/64",
            rarity = "Common", finish = "Normal", mechanics = "Unknown tag", condition = "Near Mint", grade = "Ungraded",
            notes = "line 1\nline 2", imagePath = "front", backImagePath = "back", catalogId = "base2-60",
            tcgplayerUrl = "https://example.com/one", pricechartingUrl = "https://example.com/two",
            priceCents = 155, priceSource = "Manual", priceUpdatedAt = 99, createdAt = 10,
            priceQuotes = listOf(PriceQuote(155, "Manual"), PriceQuote(10000, "Manual", "PSA 10", 99)))
        assertEquals(card, CardJson.fromJson(CardJson.toJson(card)))
        assertEquals("Misc", CardJson.fromJson(JSONObject()).category)
        assertThrows(IllegalArgumentException::class.java) { CardJson.fromJson(JSONObject().put("priceCents", 1.5)) }
        assertThrows(IllegalArgumentException::class.java) { CardJson.fromJson(JSONObject().put("priceCents", -1)) }
        assertThrows(IllegalArgumentException::class.java) { CardJson.fromJson(JSONObject().put("name", 42)) }
    }

    @Test fun identityEditsInvalidateQuotesButNotesDoNot() {
        val card = CardRecord(name = "Charizard", priceCents = 500, tcgplayerUrl = "https://example.com/one",
            priceQuotes = listOf(PriceQuote(500)))
        assertEquals(500L, invalidateChangedIdentity(card, card.copy(notes = "my copy")).priceCents)
        val changed = invalidateChangedIdentity(card, card.copy(finish = "Reverse Holofoil"))
        assertNull(changed.priceCents)
        assertTrue(changed.priceQuotes.isEmpty())
        assertEquals("", changed.tcgplayerUrl)
    }

    @Test fun backupsAreVersionedAndRejectDanglingReferencesAndDuplicateIds() {
        val collection = CollectionRecord(id = "c", name = "Binder")
        val card = CardRecord(id = "one", collectionId = "c")
        val inventory = Inventory(listOf(collection), listOf(card))
        val backup = BackupCodec.encode(BackupArchive(inventory, emptyMap()))
        assertEquals(inventory, BackupCodec.decode(backup).inventory)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(JSONObject(backup).put("version", 99).toString()) }
        val missingId = JSONObject(backup).apply { getJSONArray("cards").getJSONObject(0).remove("id") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(missingId.toString()) }
        assertThrows(IllegalArgumentException::class.java) { inventory.copy(cards = listOf(card, card)).validate() }
        assertThrows(IllegalArgumentException::class.java) { inventory.copy(collections = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(BackupArchive(inventory.copy(cards = listOf(card.copy(imagePath = "/private/image.jpg"))), emptyMap()))
        }
    }
}
