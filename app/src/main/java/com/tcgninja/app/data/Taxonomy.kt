package com.tcgninja.app.data

import java.text.Normalizer
import java.util.Locale

/** Versioned suggestions, NEVER a whitelist for persistence. See docs/taxonomy.md. */
object Taxonomy {
    const val version = "2026-10-10.1"
    val categories = listOf("Pokémon", "Trainer", "Energy", "Misc")
    val types = listOf("Colorless", "Darkness", "Dragon", "Fairy", "Fighting", "Fire", "Grass", "Lightning", "Metal", "Psychic", "Water")
    val stages = listOf("Basic", "Stage 1", "Stage 2", "Baby", "BREAK", "Level-Up", "MEGA", "Restored", "V-UNION", "VMAX", "VSTAR")
    val mechanics = listOf("ex", "EX", "GX", "V", "VMAX", "VSTAR", "V-UNION", "BREAK", "MEGA", "LEGEND", "Prime", "SP", "TAG TEAM-GX", "ACE SPEC")
    val finishes = listOf("Unknown", "Normal", "Holofoil", "Reverse Holofoil", "Other")
    val rarities = listOf("Common", "Uncommon", "Rare", "ACE SPEC Rare", "Amazing Rare", "Black White Rare", "Classic Collection", "Double rare", "Full Art Trainer", "Holo Rare", "Holo Rare V", "Holo Rare VMAX", "Holo Rare VSTAR", "Hyper rare", "Illustration rare", "LEGEND", "Mega Hyper Rare", "None", "Promo", "Radiant Rare", "Rare Holo", "Rare Holo LV.X", "Rare PRIME", "Secret Rare", "Shiny Ultra Rare", "Shiny rare", "Shiny rare V", "Shiny rare VMAX", "Special illustration rare", "Ultra Rare")

    fun subtypes(category: String): List<String> = when (categoryLabel(category)) {
        "Pokémon" -> stages
        "Trainer" -> listOf("Item", "Supporter", "Stadium", "Pokémon Tool", "Rocket's Secret Machine", "Technical Machine")
        "Energy" -> listOf("Basic", "Special")
        else -> emptyList()
    }

    /** Unknown raw categories remain stored verbatim, but browse under Misc. */
    fun categoryLabel(raw: String): String = when (fold(raw)) {
        "pokemon" -> "Pokémon"
        "trainer" -> "Trainer"
        "energy" -> "Energy"
        else -> "Misc"
    }

    internal fun subtypeKey(raw: String, category: String): String {
        val value = fold(raw)
        return when {
            categoryLabel(category) == "Energy" && value in listOf("normal", "basic energy", "basic") -> "basic"
            categoryLabel(category) == "Energy" && value == "special energy" -> "special"
            categoryLabel(category) == "Trainer" && value in listOf("tool", "pokemon tool") -> "pokemon tool"
            value == "stage1" -> "stage 1"
            value == "stage2" -> "stage 2"
            value == "level-up" -> "level-up"
            else -> value
        }
    }
}

internal fun fold(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

data class CardFilter(
    val query: String = "",
    val category: String = "",
    val subtype: String = "",
    val finish: String = "",
    val mechanic: String = "",
    val minCents: Long? = null,
    val maxCents: Long? = null,
) {
    init {
        require(minCents == null || minCents >= 0) { "Minimum must be nonnegative" }
        require(maxCents == null || maxCents >= 0) { "Maximum must be nonnegative" }
        require(minCents == null || maxCents == null || minCents <= maxCents) { "Minimum exceeds maximum" }
    }

    fun matches(card: CardRecord): Boolean {
        if (query.isNotBlank() && listOf(card.name, card.species, card.setName, card.number)
                .none { fold(it).contains(fold(query)) }) return false
        if (category.isNotBlank()) {
            val matches = if (fold(category) == "misc") Taxonomy.categoryLabel(card.category) == "Misc"
            else fold(category) == fold(card.category)
            if (!matches) return false
        }
        if (subtype.isNotBlank() && Taxonomy.subtypeKey(subtype, card.category) !=
            Taxonomy.subtypeKey(card.subtype.ifBlank { card.stage }, card.category)) return false
        if (finish.isNotBlank() && fold(finish) != fold(card.finish)) return false
        // Case sensitivity is intentional: legacy Pokémon-ex and Pokémon-EX differ.
        if (mechanic.isNotBlank() && card.mechanics.split(',', ';', '|').none { it.trim() == mechanic.trim() }) return false
        if (minCents != null && (card.priceCents == null || card.priceCents < minCents)) return false
        if (maxCents != null && (card.priceCents == null || card.priceCents > maxCents)) return false
        return true
    }
}
