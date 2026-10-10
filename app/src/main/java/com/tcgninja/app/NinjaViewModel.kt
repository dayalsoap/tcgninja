package com.tcgninja.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tcgninja.app.data.*
import com.tcgninja.app.integration.*
import kotlinx.coroutines.*

class NinjaViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AppRepository(application)
    val settings = ProviderSettings(application)
    val collections = repository.collections
    val cards = repository.cards
    val drafts = repository.drafts
    var screen by mutableStateOf("home")
    var collectionId by mutableStateOf("")
    var cardId by mutableStateOf("")
    var editorCard by mutableStateOf(CardRecord())
    var editorOriginal: CardRecord? = null
    var editorDraftId by mutableStateOf("")
    var editorDirty by mutableStateOf(false)
    var reviewIndex by mutableStateOf(0)
    var binder by mutableStateOf(false)
    var query by mutableStateOf("")
    var category by mutableStateOf("")
    var subtype by mutableStateOf("")
    var finish by mutableStateOf("")
    var mechanic by mutableStateOf("")
    var minPrice by mutableStateOf("")
    var maxPrice by mutableStateOf("")
    var catalogQuery by mutableStateOf("")
    var catalogResults by mutableStateOf<List<CardRecord>>(emptyList())
    var catalogForEditor by mutableStateOf(false)
    var cropPath by mutableStateOf("")
    var cropReturn by mutableStateOf("review")
    var priceCandidates by mutableStateOf<List<PriceCandidate>>(emptyList())
    var priceReturn by mutableStateOf("profile")
    var priceTarget by mutableStateOf<CardRecord?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")
    var notice by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var draftWrite: Job? = null

    init {
        viewModelScope.launch {
            try { repository.awaitReady() }
            catch (e: Exception) { notice = "Unable to open local collection storage. ${e.message.orEmpty()}" }
        }
    }

    fun runTask(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        progress = label
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message?.take(220) ?: "Something went wrong. Please try again." }
            finally { busy = false; progress = "" }
        }
    }

    fun cancelTask() { job?.cancel(); notice = "Stopped. Any already saved cards or prices are retained." }
    fun openCollection(id: String) {
        collectionId = id
        query = ""; category = ""; subtype = ""; finish = ""; mechanic = ""; minPrice = ""; maxPrice = ""
        screen = "collection"
    }
    fun createCollection(name: String) = runTask("Creating collection…") {
        openCollection(repository.createCollection(name.trim()))
    }
    fun deleteCollection() = runTask("Deleting collection…") {
        repository.deleteCollection(collectionId)
        screen = "home"
    }
    fun deleteCard() = runTask("Removing card…") {
        repository.deleteCard(cardId)
        screen = "collection"
    }
    fun openCard(id: String) { cardId = id; screen = "profile" }
    fun edit(card: CardRecord) {
        editorOriginal = card; editorCard = card; editorDraftId = ""; editorDirty = false; screen = "edit"
    }
    fun newCard() = runTask("Preparing card…") {
        val card = CardRecord(collectionId = collectionId)
        repository.addDrafts(collectionId, listOf(card))
        val items = collectionDrafts()
        loadDraft(items.indexOfFirst { it.card.id == card.id })
    }
    fun collectionDrafts() = drafts.value.filter { it.collectionId == collectionId }
    fun loadDraft(index: Int) {
        val items = collectionDrafts()
        if (items.isEmpty()) { screen = "collection"; return }
        reviewIndex = index.coerceIn(items.indices)
        val item = items[reviewIndex]
        editorCard = item.card; editorDraftId = item.id; editorOriginal = null; editorDirty = false
        screen = "review"
    }
    fun changeEditor(card: CardRecord) {
        editorCard = card
        editorDirty = true
        if (editorDraftId.isNotEmpty()) {
            draftWrite?.cancel()
            val draft = drafts.value.find { it.id == editorDraftId } ?: return
            if (draft.committedCardId != null) return
            draftWrite = viewModelScope.launch {
                delay(200)
                try { repository.saveDraft(draft.copy(card = card)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "Could not save draft. Please try again before leaving." }
            }
        }
    }
    suspend fun flushDraft() {
        draftWrite?.cancelAndJoin()
        val draft = drafts.value.find { it.id == editorDraftId }
        if (draft != null && draft.committedCardId == null) repository.saveDraft(draft.copy(card = editorCard))
    }
    fun stepDraft(delta: Int) = runTask("Saving draft…") { flushDraft(); loadDraft(reviewIndex + delta) }
    fun leaveReview() = runTask("Saving draft…") { flushDraft(); screen = "collection" }
    fun discardDraft() = runTask("Removing draft…") {
        draftWrite?.cancelAndJoin()
        repository.deleteDraft(editorDraftId)
        loadDraft(reviewIndex)
    }
    fun duplicateDraft() = runTask("Creating another draft…") {
        flushDraft()
        val duplicate = editorCard.copy(id = java.util.UUID.randomUUID().toString(), priceCents = null,
            priceSource = "", priceUpdatedAt = null, priceQuotes = emptyList())
        repository.addDrafts(collectionId, listOf(duplicate))
        loadDraft(collectionDrafts().indexOfFirst { it.card.id == duplicate.id })
    }
    fun saveEditor() = runTask("Saving card…") {
        require(editorCard.name.isNotBlank()) { "Enter a card name before saving." }
        validateProductUrl(editorCard.pricechartingUrl, "pricecharting.com")
        validateProductUrl(editorCard.tcgplayerUrl, "tcgplayer.com")
        val original = editorOriginal
        if (original != null) {
            val changedIdentity = listOf(original.name, original.number, original.setName, original.finish, original.grade, original.condition) !=
                listOf(editorCard.name, editorCard.number, editorCard.setName, editorCard.finish, editorCard.grade, editorCard.condition)
            val updated = if (changedIdentity) editorCard.copy(priceCents = null, priceSource = "", priceUpdatedAt = null,
                pricechartingUrl = "", tcgplayerUrl = "", catalogId = "", priceQuotes = emptyList()) else editorCard
            repository.saveCard(updated)
            editorDirty = false
            openCard(updated.id)
        } else {
            flushDraft()
            val committedId = repository.commitDraft(editorDraftId)
            editorDirty = false
            notice = "Added to collection."
            val added = cards.value.find { it.id == committedId }
            if (added != null && added.priceCents == null && added.pricechartingUrl.isNotBlank()) {
                try { repository.saveCard(PriceService().refresh(added)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "Card saved. Price unavailable; retry from its profile." }
            }
            val next = collectionDrafts().indexOfFirst { it.committedCardId == null }
            if (next >= 0) loadDraft(next) else screen = "collection"
        }
    }
    fun recognize(path: String, extraPath: String? = null) = runTask("Reading ${if (binder) "binder page" else "card"}…") {
        val collection = collectionId
        // Save the capture first so offline/auth failures cannot discard it.
        val fallback = CardRecord(collectionId = collection, imagePath = path, backImagePath = extraPath.orEmpty())
        repository.addDrafts(collection, listOf(fallback))
        val placeholder = drafts.value.last { it.card.id == fallback.id }
        try {
            val recognized = RecognitionService(settings).recognize(path, extraPath, binder)
            require(recognized.isNotEmpty()) { "No cards found. You can edit the saved capture or try another photo." }
            repository.addDrafts(collection, recognized.map { it.copy(collectionId = collection,
                imagePath = it.imagePath.ifBlank { path }, backImagePath = it.backImagePath.ifBlank { extraPath.orEmpty() }) })
            repository.deleteDraft(placeholder.id)
            loadDraft(collectionDrafts().indexOfFirst { it.committedCardId == null })
        } catch (e: Exception) {
            loadDraft(collectionDrafts().indexOfFirst { it.id == placeholder.id })
            throw e
        }
    }
    fun retryRecognition() = runTask("Reading card…") {
        require(editorCard.imagePath.isNotBlank()) { "Add a front photo first." }
        val original = editorCard
        val result = RecognitionService(settings).recognize(original.imagePath, original.backImagePath.takeIf { it.isNotBlank() }, false)
        val extracted = result.firstOrNull() ?: error("No card identified.")
        changeEditor(extracted.copy(id = original.id, collectionId = original.collectionId,
            imagePath = original.imagePath, backImagePath = original.backImagePath))
        flushDraft()
    }
    fun searchCatalog() = runTask("Searching English card catalog…") {
        require(catalogQuery.trim().length >= 2) { "Enter at least two characters." }
        catalogResults = CatalogService().search(catalogQuery.trim())
    }
    fun matchCatalog() {
        catalogForEditor = true
        catalogQuery = editorCard.name
        catalogResults = emptyList()
        screen = "catalog"
    }
    fun useCatalog(card: CardRecord) = runTask("Loading card details…") {
        val detail = CatalogService().details(card.catalogId)
        if (catalogForEditor) {
            val current = editorCard
            changeEditor(detail.copy(id = current.id, collectionId = current.collectionId,
                imagePath = current.imagePath.ifBlank { detail.imagePath }, backImagePath = current.backImagePath,
                condition = current.condition, grade = current.grade, finish = current.finish, notes = current.notes + "\n" + detail.notes))
            flushDraft()
            screen = if (editorDraftId.isBlank()) "edit" else "review"
            catalogForEditor = false
        } else {
            repository.addDrafts(collectionId, listOf(detail.copy(collectionId = collectionId)))
            loadDraft(collectionDrafts().indexOfFirst { it.card.id == detail.id && it.committedCardId == null })
        }
    }
    fun refreshPrices(targets: List<CardRecord>) = runTask("Updating prices…") {
        var success = 0
        var failure = 0
        val cache = mutableMapOf<String, CardRecord>()
        for ((index, card) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress = "Updating ${index + 1} of ${targets.size}…"
            try {
                val key = "${card.pricechartingUrl}|${card.grade}|${card.finish}|${card.name}|${card.number}|${card.setName}"
                val quote = cache[key] ?: PriceService().refresh(card).also { cache[key] = it; delay(1100) }
                val current = cards.value.find { it.id == card.id }
                if (current == card) repository.saveCard(card.copy(priceCents = quote.priceCents, priceSource = quote.priceSource,
                    priceBasis = quote.priceBasis, priceUpdatedAt = quote.priceUpdatedAt, priceQuotes = quote.priceQuotes))
                success++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure++ }
        }
        notice = "$success price(s) updated; $failure unavailable. Previous estimates retained."
    }
    fun findPrices(card: CardRecord) {
        priceTarget = card
        priceReturn = screen
        priceCandidates = emptyList()
        screen = "prices"
        runTask("Finding PriceCharting products…") { priceCandidates = PriceService().search(card) }
    }
    fun choosePrice(candidate: PriceCandidate) = runTask("Saving confirmed product…") {
        val target = priceTarget ?: error("Choose a card first.")
        if (priceReturn in listOf("edit", "review")) {
            changeEditor(editorCard.copy(pricechartingUrl = candidate.url, priceCents = null, priceSource = "", priceUpdatedAt = null, priceQuotes = emptyList()))
            flushDraft()
        } else {
            val updated = target.copy(pricechartingUrl = candidate.url, priceCents = null, priceSource = "", priceUpdatedAt = null, priceQuotes = emptyList())
            repository.saveCard(updated)
            try { repository.saveCard(PriceService().refresh(updated)); notice = "Product linked and estimate saved." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Link saved. ${e.message.orEmpty()}" }
        }
        screen = priceReturn
    }
    fun exportBackup(uri: Uri) = runTask("Exporting local backup…") {
        val text = repository.exportBackup()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error("Could not write backup file.")
        }
        notice = "Backup exported. API keys are not included."
    }
    fun importBackup(uri: Uri) = runTask("Importing local backup…") {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    require(output.size().toLong() + read <= 64 * 1024 * 1024) { "Backup exceeds 64 MB." }
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            } ?: error("Could not read backup.")
        }
        repository.importBackup(text)
        notice = "Backup imported."
    }
}

private fun validateProductUrl(value: String, domain: String) {
    if (value.isBlank()) return
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && uri.host in setOf(domain, "www.$domain") &&
        uri.userInfo == null && uri.port in setOf(-1, 443)) { "Use a valid HTTPS $domain product link." }
    require(if (domain == "pricecharting.com") uri.path.startsWith("/game/") else uri.path.startsWith("/product/")) {
        "Paste the actual $domain product page, not a search link."
    }
}
