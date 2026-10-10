package com.tcgninja.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.net.URI

internal data class Inventory(
    val collections: List<CollectionRecord> = emptyList(),
    val cards: List<CardRecord> = emptyList(),
    val drafts: List<DraftRecord> = emptyList(),
) {
    fun validate() {
        fun unique(ids: List<String>) {
            require(ids.none { it.isBlank() } && ids.toSet().size == ids.size) { "Missing or duplicate IDs" }
        }
        unique(collections.map { it.id }); unique(cards.map { it.id }); unique(drafts.map { it.id })
        require(collections.size + cards.size + drafts.size <= BackupCodec.MAX_RECORDS) { "Too many records" }
        collections.forEach { require(it.name.isNotBlank() && it.createdAt >= 0) { "Invalid collection" } }
        val collectionIds = collections.map { it.id }.toSet()
        val cardMap = cards.associateBy { it.id }
        cards.forEach {
            validateCard(it)
            require(it.collectionId in collectionIds) { "Card refers to missing collection" }
        }
        val commits = drafts.mapNotNull { it.committedCardId }
        unique(commits)
        drafts.forEach {
            validateCard(it.card)
            require(it.collectionId in collectionIds && it.card.collectionId == it.collectionId) { "Draft collection mismatch" }
            require(it.position >= 0) { "Negative draft position" }
            // A missing committed card is a deletion tombstone, not an invitation to resurrect it.
            val owned = cardMap[it.committedCardId]
            require(owned == null || owned.collectionId == it.collectionId) { "Committed card collection mismatch" }
        }
    }

    /** Existing IDs win so importing an older backup cannot overwrite local corrections. */
    fun merge(incoming: Inventory): Inventory {
        incoming.validate()
        val existingDrafts = drafts.associateBy { it.id }
        incoming.drafts.forEach { imported ->
            existingDrafts[imported.id]?.let { existing ->
                require(existing.committedCardId == imported.committedCardId || imported.committedCardId == null) {
                    "Conflicting draft commit; import into an empty collection database instead"
                }
            }
        }
        val ownedIds = cards.map { it.id }.toSet()
        val deletedCommitIds = drafts.mapNotNull { it.committedCardId }.filter { it !in ownedIds }.toSet()
        return Inventory(
            (incoming.collections.associateBy { it.id } + collections.associateBy { it.id }).values.toList(),
            (incoming.cards.filterNot { it.id in deletedCommitIds }.associateBy { it.id } + cards.associateBy { it.id }).values.toList(),
            (incoming.drafts.associateBy { it.id } + drafts.associateBy { it.id }).values.toList(),
        ).also { it.validate() }
    }

    fun mapImages(transform: (String) -> String): Inventory {
        fun card(record: CardRecord) = record.copy(
            imagePath = transform(record.imagePath), backImagePath = transform(record.backImagePath),
        )
        return copy(cards = cards.map(::card), drafts = drafts.map { it.copy(card = card(it.card)) })
    }
}

internal data class BackupArchive(val inventory: Inventory, val images: Map<String, ByteArray>)

internal object BackupCodec {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_IMAGE_BYTES = 32 * 1024 * 1024
    const val MAX_JSON_BYTES = 48 * 1024 * 1024
    const val MAX_RECORDS = 20_000
    private val imageId = Regex("[0-9a-f]{64}")

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(archive: BackupArchive): String {
        archive.inventory.validate()
        validateImages(archive.images)
        validateReferences(archive)
        val root = JSONObject().put("format", "tcgninja-backup").put("version", 1).put("currency", "USD")
            .put("collections", JSONArray().apply { archive.inventory.collections.forEach { put(collectionJson(it)) } })
            .put("cards", JSONArray().apply { archive.inventory.cards.forEach { put(CardJson.toJson(it)) } })
            .put("drafts", JSONArray().apply { archive.inventory.drafts.forEach { put(draftJson(it)) } })
            .put("images", JSONObject().apply {
                archive.images.forEach { (id, bytes) -> put(id, Base64.getEncoder().encodeToString(bytes)) }
            })
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup is too large" } }
    }

    fun decode(text: String): BackupArchive {
        require(text.length <= MAX_JSON_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup is too large" }
        val json = JSONObject(text)
        require(json.string("format") == "tcgninja-backup" && json.longOrNull("version") == 1L) { "Unsupported backup format/version" }
        require(json.string("currency") == "USD") { "Only USD backups are supported" }
        val collections = json.getJSONArray("collections")
        val cards = json.getJSONArray("cards")
        val drafts = json.getJSONArray("drafts")
        require(collections.length().toLong() + cards.length() + drafts.length() <= MAX_RECORDS) { "Too many records" }
        // Record defaults are useful for recognition, but backup identities must never be invented.
        fun storedCard(json: JSONObject): CardRecord {
            require(json.string("id").isNotBlank() && json.longOrNull("createdAt") != null) { "Backup card ID and timestamp required" }
            return CardJson.fromJson(json)
        }
        val inventory = Inventory(collections.objects().map(::collectionFromJson), cards.objects().map(::storedCard), drafts.objects().map {
            storedCard(it.getJSONObject("card"))
            draftFromJson(it)
        })
        inventory.validate()
        val encodedImages = json.getJSONObject("images")
        val images = linkedMapOf<String, ByteArray>()
        var total = 0L
        encodedImages.keys().forEach { id ->
            require(imageId.matches(id)) { "Invalid image ID" }
            val encoded = encodedImages.string(id)
            require(encoded.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "Image is too large" }
            val bytes = Base64.getDecoder().decode(encoded)
            total += bytes.size
            require(total <= MAX_TOTAL_IMAGE_BYTES) { "Attached images exceed 32 MiB" }
            images[id] = bytes
        }
        validateImages(images)
        return BackupArchive(inventory, images).also(::validateReferences)
    }

    private fun validateImages(images: Map<String, ByteArray>) {
        require(images.values.sumOf { it.size.toLong() } <= MAX_TOTAL_IMAGE_BYTES) { "Attached images exceed 32 MiB" }
        images.forEach { (id, bytes) ->
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES && imageId.matches(id) && digest(bytes) == id) { "Invalid or corrupt image attachment" }
            require(isImage(bytes)) { "Only JPEG, PNG, and WebP images are supported" }
        }
    }

    private fun isImage(b: ByteArray): Boolean =
        (b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()) ||
            (b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) ||
            (b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP")

    private fun validateReferences(archive: BackupArchive) {
        val used = mutableSetOf<String>()
        archive.inventory.mapImages { ref ->
            if (ref.isNotEmpty() && !isRemoteImageReference(ref)) {
                require(ref.startsWith("image:") && ref.removePrefix("image:") in archive.images) { "Missing or nonportable image attachment" }
                used += ref.removePrefix("image:")
            }
            ref
        }
        require(used == archive.images.keys) { "Unreferenced image attachments" }
    }
}

/** Catalog art is a remote reference, not an owned photo or a reason to fetch during export. */
internal fun isRemoteImageReference(value: String): Boolean = runCatching {
    if (value.length > 4096) return@runCatching false
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)
