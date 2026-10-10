package com.tcgninja.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local inventory. Mutations run on IO, transact, then publish immutable snapshots. */
class AppRepository internal constructor(context: Context, databaseName: String) {
    constructor(context: Context) : this(context, "tcgninja.db")

    private val store = synchronized(stores) {
        val app = context.applicationContext
        stores.getOrPut(app.getDatabasePath(databaseName).absolutePath) { Store(app, databaseName) }
    }
    val collections: StateFlow<List<CollectionRecord>> = store.collections.asStateFlow()
    val cards: StateFlow<List<CardRecord>> = store.cards.asStateFlow()
    val drafts: StateFlow<List<DraftRecord>> = store.drafts.asStateFlow()

    /** Optional readiness hook; also propagates an initial database failure to callers. */
    suspend fun awaitReady() { store.ready.await() }

    suspend fun createCollection(name: String): String {
        require(name.isNotBlank()) { "Collection name is required" }
        val collection = CollectionRecord(name = name.trim())
        return mutate { db -> putCollection(db, collection); collection.id }
    }

    suspend fun deleteCollection(id: String) {
        mutate { db -> db.delete("collections", "id = ?", arrayOf(id)); Unit }
    }

    suspend fun saveCard(card: CardRecord) {
        validateCard(card)
        mutate { db ->
            requireCollection(db, card.collectionId)
            val old = cardById(db, card.id)
            require(old != null || !isDeletedCommit(db, card.id)) { "This committed card was deleted" }
            require(old == null || old.collectionId == card.collectionId) { "Moving owned cards between collections is not supported" }
            putCard(db, invalidateChangedIdentity(old, card))
        }
    }

    suspend fun deleteCard(id: String) {
        // Retain the draft's commit ID as a tombstone to make retries idempotent after deletion.
        mutate { db -> db.delete("cards", "id = ?", arrayOf(id)); Unit }
    }

    suspend fun saveDraft(draft: DraftRecord) {
        validateCard(draft.card)
        require(draft.id.isNotBlank() && draft.position >= 0) { "Invalid draft" }
        require(draft.card.collectionId.isEmpty() || draft.card.collectionId == draft.collectionId) { "Draft collection mismatch" }
        mutate { db ->
            requireCollection(db, draft.collectionId)
            val old = draftById(db, draft.id)
            require(old == null || old.collectionId == draft.collectionId) { "Cannot move a draft between collections" }
            require(draft.committedCardId == old?.committedCardId) { "Only commitDraft can set a commit ID" }
            require(old?.committedCardId == null || old == draft) { "Committed drafts cannot be edited; edit the owned card" }
            putDraft(db, draft.copy(card = draft.card.copy(collectionId = draft.collectionId)))
        }
    }

    suspend fun deleteDraft(id: String) {
        mutate { db -> db.delete("drafts", "id = ?", arrayOf(id)); Unit }
    }

    suspend fun commitDraft(id: String): String = mutate { db ->
        val draft = requireNotNull(draftById(db, id)) { "Draft does not exist" }
        draft.committedCardId ?: run {
            require(draft.card.name.isNotBlank()) { "Enter a card name before committing" }
            requireCollection(db, draft.collectionId)
            // New physical copy regardless of the recognition adapter's provisional card ID.
            val owned = draft.card.copy(id = UUID.randomUUID().toString(), collectionId = draft.collectionId)
            putCard(db, owned)
            putDraft(db, draft.copy(committedCardId = owned.id))
            owned.id
        }
    }

    suspend fun addDrafts(collectionId: String, cards: List<CardRecord>) {
        cards.forEach(::validateCard)
        val batch = UUID.randomUUID().toString()
        mutate { db ->
            requireCollection(db, collectionId)
            cards.forEachIndexed { index, card ->
                putDraft(db, DraftRecord(collectionId = collectionId,
                    card = card.copy(collectionId = collectionId), position = index, batchId = batch))
            }
        }
    }

    suspend fun exportBackup(): String = access { db ->
        BackupCodec.encode(BackupImages(store.context).export(readInventory(db)))
    }

    suspend fun importBackup(json: String) {
        // Parse/validate everything before touching disk; malformed backups cannot partially merge.
        val archive = withContext(Dispatchers.IO) { BackupCodec.decode(json) }
        access { db ->
            val current = readInventory(db)
            current.merge(archive.inventory) // Validate conflicts before creating files.
            val created = mutableListOf<File>()
            var committed = false
            try {
                val restored = BackupImages(store.context).restore(archive, created)
                val merged = current.merge(restored)
                val next = transaction(db) {
                    merged.collections.forEach { putCollection(db, it) }
                    merged.cards.forEach { putCard(db, it) }
                    merged.drafts.forEach { putDraft(db, it) }
                    readInventory(db)
                }
                committed = true
                store.publish(next)
                // Existing records won conflicts; discard new attachments that ended up unused.
                val used = mutableSetOf<String>()
                merged.mapImages { used += it; it }
                created.filter { it.absolutePath !in used }.forEach { it.delete() }
            } finally {
                if (!committed) created.forEach { it.delete() }
            }
        }
    }

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        store.ready.await()
        store.mutex.withLock { block(store.helper.writableDatabase) }
    }

    private suspend fun <T> mutate(block: (SQLiteDatabase) -> T): T = access { db ->
        val (result, snapshot) = transaction(db) { block(db) to readInventory(db) }
        store.publish(snapshot)
        result
    }

    private class Store(val context: Context, name: String) {
        val helper = InventoryDatabase(context, name)
        val mutex = Mutex()
        val collections = MutableStateFlow<List<CollectionRecord>>(emptyList())
        val cards = MutableStateFlow<List<CardRecord>>(emptyList())
        val drafts = MutableStateFlow<List<DraftRecord>>(emptyList())
        val ready = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            mutex.withLock { publish(readInventory(helper.writableDatabase)) }
        }
        fun publish(snapshot: Inventory) {
            collections.value = snapshot.collections
            cards.value = snapshot.cards
            drafts.value = snapshot.drafts
        }
    }

    companion object {
        private val stores = mutableMapOf<String, Store>()

        /** Test-only process restart simulation; callers must have completed outstanding work. */
        internal fun closeForTest(context: Context, databaseName: String) {
            synchronized(stores) { stores.remove(context.getDatabasePath(databaseName).absolutePath)?.helper?.close() }
        }
    }
}

private class InventoryDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE collections (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY NOT NULL, collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX cards_collection ON cards(collection_id)")
        db.execSQL("CREATE TABLE drafts (id TEXT PRIMARY KEY NOT NULL, collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE, committed_card_id TEXT UNIQUE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX drafts_collection ON drafts(collection_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A nondestructive migration from $oldVersion to $newVersion must be supplied")
    }
}

private fun <T> transaction(db: SQLiteDatabase, operation: () -> T): T {
    db.beginTransaction()
    try {
        val result = operation()
        db.setTransactionSuccessful()
        return result
    } finally { db.endTransaction() }
}

private fun readInventory(db: SQLiteDatabase): Inventory = Inventory(
    rows(db, "collections").map(::collectionFromJson).sortedWith(compareBy({ it.createdAt }, { it.id })),
    rows(db, "cards").map(CardJson::fromJson).sortedWith(compareByDescending<CardRecord> { it.createdAt }.thenBy { it.id }),
    rows(db, "drafts").map(::draftFromJson).sortedWith(compareBy({ it.batchId }, { it.position }, { it.id })),
)

private fun rows(db: SQLiteDatabase, table: String): List<JSONObject> =
    db.query(table, arrayOf("payload"), null, null, null, null, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }

private fun row(db: SQLiteDatabase, table: String, id: String): JSONObject? =
    db.query(table, arrayOf("payload"), "id = ?", arrayOf(id), null, null, null).use { cursor ->
        if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
    }

private fun cardById(db: SQLiteDatabase, id: String) = row(db, "cards", id)?.let(CardJson::fromJson)
private fun draftById(db: SQLiteDatabase, id: String) = row(db, "drafts", id)?.let(::draftFromJson)
private fun requireCollection(db: SQLiteDatabase, id: String) {
    require(row(db, "collections", id) != null) { "Collection does not exist" }
}

private fun upsert(db: SQLiteDatabase, table: String, id: String, payload: JSONObject, collectionId: String? = null, committedId: String? = null) {
    val text = payload.toString()
    require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Record exceeds 256 KiB" }
    val values = ContentValues().apply {
        put("id", id); put("payload", text)
        if (collectionId != null) put("collection_id", collectionId)
        if (table == "drafts") { if (committedId == null) putNull("committed_card_id") else put("committed_card_id", committedId) }
    }
    // REPLACE deletes then inserts, which would cascade-delete a collection's children.
    if (db.update(table, values, "id = ?", arrayOf(id)) == 0) db.insertOrThrow(table, null, values)
}

private fun putCollection(db: SQLiteDatabase, record: CollectionRecord) = upsert(db, "collections", record.id, collectionJson(record))
private fun canonicalCard(record: CardRecord): CardRecord {
    val known = Taxonomy.categoryLabel(record.category)
    return if (known != "Misc") record.copy(category = known) else record
}

private fun putCard(db: SQLiteDatabase, record: CardRecord) = upsert(db, "cards", record.id, CardJson.toJson(canonicalCard(record)), record.collectionId)
private fun putDraft(db: SQLiteDatabase, record: DraftRecord) = upsert(db, "drafts", record.id,
    draftJson(record.copy(card = canonicalCard(record.card))), record.collectionId, record.committedCardId)

private fun isDeletedCommit(db: SQLiteDatabase, id: String): Boolean =
    db.query("drafts", arrayOf("id"), "committed_card_id = ?", arrayOf(id), null, null, null).use { it.moveToFirst() }

internal fun invalidateChangedIdentity(old: CardRecord?, updated: CardRecord): CardRecord {
    if (old == null) return updated
    fun identity(card: CardRecord) = listOf(card.name, card.setName, card.number, card.catalogId, card.finish, card.grade, card.condition)
    if (identity(old) == identity(updated)) return updated
    return updated.copy(tcgplayerUrl = "", pricechartingUrl = "", priceCents = null,
        priceSource = "", priceUpdatedAt = null, priceBasis = "Ungraded", priceQuotes = emptyList())
}
