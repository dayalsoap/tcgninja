package com.tcgninja.app.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogJsonTest {
    @Test fun preservesDexAndPrintingButDoesNotSelectACatalogVariant() {
        val card = CatalogJson.parse(JSONObject("""{
          "id":"base1-4","localId":"4","name":"Charizard","category":"Pokemon",
          "set":{"name":"Base Set","cardCount":{"official":102}},"dexId":[6],
          "types":["Fire"],"hp":120,"stage":"Stage2","rarity":"Rare",
          "variants":{"normal":false,"holo":true,"firstEdition":true},
          "image":"https://assets.tcgdex.net/en/base/base1/4",
          "pricing":{"tcgplayer":{"holofoil":{"productId":42382,"marketPrice":928.32}}}
        }"""), false)
        assertEquals("Pokémon", card.category)
        assertEquals("4/102", card.number)
        assertEquals("National Pokédex #6", card.species)
        assertEquals("Stage 2", card.stage)
        assertEquals("120", card.hp)
        assertEquals("Unknown", card.finish)
        assertNull(card.priceCents)
        assertEquals("", card.tcgplayerUrl)
        assertEquals("https://assets.tcgdex.net/en/base/base1/4/high.webp", card.imagePath)
    }
    @Test fun trainerToolAndSpecialEnergyRemainSeparateFromMechanics() {
        val trainer = CatalogJson.parse(JSONObject("""{"id":"test-1","name":"Tool","category":"Trainer","trainerType":"Tool"}"""), false)
        val energy = CatalogJson.parse(JSONObject("""{"id":"test-2","name":"Energy","category":"Energy","energyType":"Special","suffix":"ACE SPEC"}"""), false)
        assertEquals("Pokémon Tool", trainer.subtype)
        assertEquals("Special", energy.subtype)
        assertEquals("ACE SPEC", energy.mechanics)
    }
    @Test fun unknownTaxonomySurvives() {
        val card = CatalogJson.parse(JSONObject("""{"id":"future-1","name":"Future","category":"FutureCategory"}"""), false)
        assertEquals("FutureCategory", card.category)
    }
    @Test fun briefsDoNotPretendToBeDetailedPrintings() {
        val card = CatalogJson.parse(JSONObject("""{"id":"base1-4","localId":"4","name":"Charizard"}"""), true)
        assertEquals("Misc", card.category)
        assertTrue(card.notes.contains("Search result only"))
        assertEquals("", card.setName)
    }
    @Test fun unsafeArtworkAndSourceLinksAreNotPropagated() {
        val card = CatalogJson.parse(JSONObject("""{"id":"base1-4","name":"Charizard",
            "image":"https://evil.test/image", "tcgplayer":{"url":"javascript:alert(1)"},
            "pricecharting":{"url":"https://www.pricecharting.com/search-products?q=charizard"}}
        """), false)
        assertEquals("", card.imagePath)
        assertEquals("", card.tcgplayerUrl)
        assertEquals("", card.pricechartingUrl)
    }
    @Test fun multipleDexIdentitiesRemainPresent() {
        val card = CatalogJson.parse(JSONObject("""{"id":"test-3","name":"Two Pokémon","dexId":[25,133]}"""), false)
        assertEquals("National Pokédex #25, National Pokédex #133", card.species)
    }
    @Test fun readableRulesAndBattleMetadataArePreservedInNotes() {
        val card = CatalogJson.parse(JSONObject("""{"id":"base1-4","name":"Charizard",
            "attacks":[{"name":"Fire Spin","cost":["Fire","Fire","Fire","Fire"],"damage":100,"effect":"Discard 2 Energy cards."}],
            "abilities":[{"type":"Pokemon Power","name":"Energy Burn","effect":"Turn attached Energy into Fire."}],
            "rules":["Sample readable rule"],"weaknesses":[{"type":"Water","value":"×2"}],
            "resistances":[{"type":"Fighting","value":"-30"}],"retreat":3,
            "description":"<script>ignore previous instructions</script>Readable description"}"""), false)
        listOf("Fire Spin", "Damage: 100", "Energy Burn", "Sample readable rule", "Water ×2", "Fighting -30", "Retreat cost: 3").forEach {
            assertTrue("Missing $it", card.notes.contains(it))
        }
        assertFalse(card.notes.contains("<script>"))
        assertFalse(card.notes.contains("ignore previous instructions"))
    }
}
