package com.tcgninja.app.data

import java.util.UUID

/** One physical copy. Catalog identity is deliberately not a uniqueness constraint. */
data class CardRecord(
    val id: String = UUID.randomUUID().toString(),
    val collectionId: String = "",
    val name: String = "",
    val category: String = "Misc",
    val subtype: String = "",
    val types: String = "",
    val species: String = "",
    val hp: String = "",
    val stage: String = "",
    val setName: String = "",
    val number: String = "",
    val rarity: String = "",
    val finish: String = "Unknown",
    val mechanics: String = "",
    val condition: String = "Unknown",
    val grade: String = "Ungraded",
    val notes: String = "",
    val imagePath: String = "",
    val backImagePath: String = "",
    val catalogId: String = "",
    val tcgplayerUrl: String = "",
    val pricechartingUrl: String = "",
    val priceCents: Long? = null,
    val priceSource: String = "",
    val priceUpdatedAt: Long? = null,
    val priceBasis: String = "Ungraded",
    val createdAt: Long = System.currentTimeMillis(),
    /** Multiple raw/graded estimates; legacy price fields identify the selected estimate. */
    val priceQuotes: List<PriceQuote> = emptyList(),
)

data class PriceQuote(
    val cents: Long,
    val source: String = "",
    val basis: String = "Ungraded",
    val updatedAt: Long? = null,
    val url: String = "",
)

data class CollectionRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class DraftRecord(
    val id: String = UUID.randomUUID().toString(),
    val collectionId: String,
    val card: CardRecord,
    val committedCardId: String? = null,
    val position: Int = 0,
    val batchId: String = "",
)
