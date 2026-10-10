package com.tcgninja.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist: no reflection or provider settings can enter record JSON. */
object CardJson {
    fun toJson(card: CardRecord): JSONObject = JSONObject().apply {
        put("id", card.id)
        put("collectionId", card.collectionId)
        put("name", card.name)
        put("category", card.category)
        put("subtype", card.subtype)
        put("types", card.types)
        put("species", card.species)
        put("hp", card.hp)
        put("stage", card.stage)
        put("setName", card.setName)
        put("number", card.number)
        put("rarity", card.rarity)
        put("finish", card.finish)
        put("mechanics", card.mechanics)
        put("condition", card.condition)
        put("grade", card.grade)
        put("notes", card.notes)
        put("imagePath", card.imagePath)
        put("backImagePath", card.backImagePath)
        put("catalogId", card.catalogId)
        put("tcgplayerUrl", card.tcgplayerUrl)
        put("pricechartingUrl", card.pricechartingUrl)
        put("priceSource", card.priceSource)
        put("priceBasis", card.priceBasis)
        put("priceCents", card.priceCents ?: JSONObject.NULL)
        put("priceUpdatedAt", card.priceUpdatedAt ?: JSONObject.NULL)
        put("createdAt", card.createdAt)
        put("priceQuotes", JSONArray().apply {
            card.priceQuotes.forEach { quote ->
                put(JSONObject().put("cents", quote.cents).put("source", quote.source)
                    .put("basis", quote.basis).put("updatedAt", quote.updatedAt ?: JSONObject.NULL)
                    .put("url", quote.url))
            }
        })
    }

    fun fromJson(json: JSONObject): CardRecord {
        val defaults = CardRecord()
        return CardRecord(
            id = json.string("id", defaults.id),
            collectionId = json.string("collectionId", defaults.collectionId),
            name = json.string("name", defaults.name),
            category = json.string("category", defaults.category),
            subtype = json.string("subtype", defaults.subtype),
            types = json.string("types", defaults.types),
            species = json.string("species", defaults.species),
            hp = json.string("hp", defaults.hp),
            stage = json.string("stage", defaults.stage),
            setName = json.string("setName", defaults.setName),
            number = json.string("number", defaults.number),
            rarity = json.string("rarity", defaults.rarity),
            finish = json.string("finish", defaults.finish),
            mechanics = json.string("mechanics", defaults.mechanics),
            condition = json.string("condition", defaults.condition),
            grade = json.string("grade", defaults.grade),
            notes = json.string("notes", defaults.notes),
            imagePath = json.string("imagePath", defaults.imagePath),
            backImagePath = json.string("backImagePath", defaults.backImagePath),
            catalogId = json.string("catalogId", defaults.catalogId),
            tcgplayerUrl = json.string("tcgplayerUrl", defaults.tcgplayerUrl),
            pricechartingUrl = json.string("pricechartingUrl", defaults.pricechartingUrl),
            priceSource = json.string("priceSource", defaults.priceSource),
            priceBasis = json.string("priceBasis", defaults.priceBasis),
            priceCents = json.longOrNull("priceCents"),
            priceUpdatedAt = json.longOrNull("priceUpdatedAt"),
            createdAt = json.longOrNull("createdAt") ?: defaults.createdAt,
            priceQuotes = json.arrayOrEmpty("priceQuotes").objects().map {
                PriceQuote(
                    cents = requireNotNull(it.longOrNull("cents")) { "Quote requires cents" },
                    source = it.string("source"), basis = it.string("basis", "Ungraded"),
                    updatedAt = it.longOrNull("updatedAt"), url = it.string("url"),
                )
            },
        ).also { validateCard(it) }
    }
}

internal fun JSONObject.string(key: String, default: String = ""): String {
    if (!has(key) || isNull(key)) return default
    return opt(key) as? String ?: throw IllegalArgumentException("$" + key + " must be text")
}

internal fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    require(value is Number && Regex("-?[0-9]+").matches(value.toString())) { "$" + key + " must be an integer" }
    return value.toString().toLongOrNull() ?: throw IllegalArgumentException("$" + key + " overflows a Long")
}

internal fun JSONObject.arrayOrEmpty(key: String): JSONArray {
    if (!has(key)) return JSONArray()
    return opt(key) as? JSONArray ?: throw IllegalArgumentException("$" + key + " must be an array")
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map {
    opt(it) as? JSONObject ?: throw IllegalArgumentException("Expected object at index $it")
}

internal fun validateCard(card: CardRecord) {
    require(card.id.isNotBlank()) { "Card ID is required" }
    require(card.priceCents == null || card.priceCents >= 0) { "Price must be nonnegative" }
    require(card.priceUpdatedAt == null || card.priceUpdatedAt >= 0) { "Invalid price timestamp" }
    require(card.createdAt >= 0) { "Invalid card timestamp" }
    require(card.priceQuotes.size <= 100) { "Too many quotes" }
    card.priceQuotes.forEach {
        require(it.cents >= 0 && (it.updatedAt == null || it.updatedAt >= 0)) { "Invalid quote" }
    }
}

internal fun collectionJson(record: CollectionRecord): JSONObject = JSONObject()
    .put("id", record.id).put("name", record.name).put("createdAt", record.createdAt)

internal fun collectionFromJson(json: JSONObject): CollectionRecord = CollectionRecord(
    id = json.string("id"), name = json.string("name"),
    createdAt = requireNotNull(json.longOrNull("createdAt")) { "Collection timestamp required" },
)

internal fun draftJson(record: DraftRecord): JSONObject = JSONObject().put("id", record.id)
    .put("collectionId", record.collectionId).put("card", CardJson.toJson(record.card))
    .put("committedCardId", record.committedCardId ?: JSONObject.NULL)
    .put("position", record.position).put("batchId", record.batchId)

internal fun draftFromJson(json: JSONObject): DraftRecord {
    val position = json.longOrNull("position") ?: 0
    require(position in 0..Int.MAX_VALUE.toLong()) { "Invalid draft position" }
    return DraftRecord(
        id = json.string("id"), collectionId = json.string("collectionId"),
        card = CardJson.fromJson(json.getJSONObject("card")),
        committedCardId = if (!json.has("committedCardId") || json.isNull("committedCardId")) null else json.string("committedCardId"),
        position = position.toInt(), batchId = json.string("batchId"),
    )
}
