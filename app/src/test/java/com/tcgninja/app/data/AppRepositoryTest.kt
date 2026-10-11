package com.tcgninja.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AppRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()
    private fun repository(name: String = "test-${UUID.randomUUID()}.db"): AppRepository {
        databases += name
        return AppRepository(context, name)
    }

    @After fun closeDatabases() {
        databases.distinct().forEach { AppRepository.closeForTest(context, it); context.deleteDatabase(it) }
    }

    @Test fun crudPersistsAcrossReopeningAndCollectionsAreIsolated() = runBlocking {
        val name = "restart-${UUID.randomUUID()}.db"
        val repo = repository(name)
        val a = repo.createCollection("A")
        val b = repo.createCollection("B")
        val first = CardRecord(collectionId = a, name = "Charizard")
        val second = CardRecord(collectionId = b, name = "Charizard")
        repo.saveCard(first); repo.saveCard(second)
        repo.addDrafts(a, listOf(first))
        AppRepository.closeForTest(context, name)
        val reopened = repository(name)
        reopened.awaitReady()
        assertEquals(2, reopened.cards.value.size)
        reopened.saveCard(first.copy(notes = "my copy"))
        assertEquals("my copy", reopened.cards.value.first { it.id == first.id }.notes)
        reopened.deleteCollection(a)
        assertEquals(listOf(second), reopened.cards.value)
        assertTrue(reopened.drafts.value.isEmpty())
        assertEquals(listOf(b), reopened.collections.value.map { it.id })
    }

    @Test fun parallelCommitIsIdempotentAndPhysicalCopiesRemainDistinct() = runBlocking {
        val repo = repository()
        val collection = repo.createCollection("Binder")
        val recognized = CardRecord(name = "Pikachu")
        repo.addDrafts(collection, listOf(recognized, recognized))
        val drafts = repo.drafts.value
        val results = coroutineScope { (1..12).map { async { repo.commitDraft(drafts[0].id) } }.awaitAll() }
        assertEquals(1, results.toSet().size)
        assertEquals(1, repo.cards.value.size)
        val other = repo.commitDraft(drafts[1].id)
        assertNotEquals(results.first(), other)
        assertEquals(2, repo.cards.value.size)
        repo.deleteCard(results.first())
        assertEquals(results.first(), repo.commitDraft(drafts[0].id))
        assertEquals(1, repo.cards.value.size)
    }

    @Test fun newerDraftBatchesSortAfterOlderOnes() = runBlocking {
        val repo = repository()
        val collection = repo.createCollection("Order")
        repo.addDrafts(collection, listOf(CardRecord(name = "First scan")))
        Thread.sleep(5)
        repo.addDrafts(collection, listOf(CardRecord(name = "Second scan A"), CardRecord(name = "Second scan B")))
        assertEquals(listOf("First scan", "Second scan A", "Second scan B"), repo.drafts.value.map { it.card.name })
    }

    @Test fun twoRepositoryInstancesSharePublishedState() = runBlocking {
        val name = "shared-${UUID.randomUUID()}.db"
        val a = repository(name); val b = repository(name)
        val id = a.createCollection("Shared")
        assertEquals(id, b.collections.value.single().id)
        b.saveCard(CardRecord(collectionId = id))
        assertEquals(1, a.cards.value.size)
    }

    @Test fun malformedAndPartiallyInvalidImportsLeaveDatabaseUnchanged() = runBlocking {
        val repo = repository()
        val collection = repo.createCollection("Keep")
        repo.saveCard(CardRecord(collectionId = collection, name = "Original"))
        val before = repo.exportBackup()
        val broken = JSONObject(before).put("collections", JSONArray())
        try { repo.importBackup(broken.toString()); fail("Expected invalid reference") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.exportBackup())
        // A late SQLite payload-size failure rolls back earlier inserted collections/cards.
        val incomingCollection = CollectionRecord(name = "New")
        val valid = CardRecord(collectionId = incomingCollection.id, name = "Small")
        val tooBig = CardRecord(collectionId = incomingCollection.id, notes = "x".repeat(300_000))
        val archive = BackupCodec.encode(BackupArchive(Inventory(listOf(incomingCollection), listOf(valid, tooBig)), emptyMap()))
        try { repo.importBackup(archive); fail("Expected oversized record") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.exportBackup())
    }

    @Test fun backupMergeIsRepeatableAndKeepsLocalEdits() = runBlocking {
        val source = repository()
        val collection = source.createCollection("Original")
        val card = CardRecord(collectionId = collection, name = "Charizard")
        source.saveCard(card)
        val backup = source.exportBackup()
        val target = repository()
        target.importBackup(backup)
        target.saveCard(card.copy(notes = "local edit"))
        target.importBackup(backup)
        assertEquals(1, target.collections.value.size)
        assertEquals("local edit", target.cards.value.single().notes)
    }

    @Test fun portableBackupRestoresOwnedImageBytesAndExcludesPreferences() = runBlocking {
        val source = repository()
        val collection = source.createCollection("Photos")
        context.getSharedPreferences("provider", Context.MODE_PRIVATE).edit().putString("api_key", "secret-token").commit()
        val image = File(context.filesDir, "test-photo.png")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val originalBytes = image.readBytes()
        source.saveCard(CardRecord(collectionId = collection, imagePath = image.absolutePath, backImagePath = image.absolutePath))
        val backup = source.exportBackup()
        assertFalse(backup.contains(image.absolutePath))
        assertFalse(backup.contains("secret-token"))
        assertEquals(1, JSONObject(backup).getJSONObject("images").length())
        image.delete()
        val target = repository()
        target.importBackup(backup)
        val restored = target.cards.value.single()
        assertTrue(restored.imagePath.startsWith(context.filesDir.absolutePath))
        assertArrayEquals(originalBytes, File(restored.imagePath).readBytes())
        assertEquals(restored.imagePath, restored.backImagePath)
    }

    @Test fun corruptAttachmentsAreRejectedBeforeAnyMutation() = runBlocking {
        val repo = repository()
        repo.createCollection("Safe")
        val before = repo.exportBackup()
        val malicious = JSONObject(before).put("images", JSONObject().put("../../escape", "eA=="))
        try { repo.importBackup(malicious.toString()); fail("Expected attachment rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.exportBackup())
    }

    @Test fun blankDraftCannotCommitAndCanBeCorrected() = runBlocking {
        val repo = repository()
        val collection = repo.createCollection("Review")
        val draft = DraftRecord(collectionId = collection, card = CardRecord(name = "  "))
        repo.saveDraft(draft)
        try { repo.commitDraft(draft.id); fail("Expected missing name") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.cards.value.isEmpty())
        assertNull(repo.drafts.value.single().committedCardId)
        repo.saveDraft(draft.copy(card = draft.card.copy(name = "Unknown Pokémon", category = "Pokemon")))
        val id = repo.commitDraft(draft.id)
        assertEquals(id, repo.cards.value.single().id)
        assertEquals("Pokémon", repo.cards.value.single().category)
    }

    @Test fun oldBackupAndStaleSaveCannotResurrectDeletedCommittedCard() = runBlocking {
        val repo = repository()
        val collection = repo.createCollection("Binder")
        repo.addDrafts(collection, listOf(CardRecord(name = "Pikachu")))
        val draft = repo.drafts.value.single()
        val id = repo.commitDraft(draft.id)
        val card = repo.cards.value.single()
        val backup = repo.exportBackup()
        repo.deleteCard(id)
        repo.importBackup(backup)
        assertTrue(repo.cards.value.isEmpty())
        assertEquals(id, repo.commitDraft(draft.id))
        try { repo.saveCard(card); fail("Expected deleted commit protection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun remoteCatalogArtIsPreservedAsAReferenceWithoutDownloading() = runBlocking {
        val source = repository()
        val collection = source.createCollection("Catalog")
        val url = "https://assets.tcgdex.net/en/base/base1/4/high.png"
        source.saveCard(CardRecord(collectionId = collection, imagePath = url))
        val backup = source.exportBackup()
        assertEquals(0, JSONObject(backup).getJSONObject("images").length())
        val target = repository()
        target.importBackup(backup)
        assertEquals(url, target.cards.value.single().imagePath)
    }
}
