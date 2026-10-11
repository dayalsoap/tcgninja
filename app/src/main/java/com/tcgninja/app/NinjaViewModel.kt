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
    /**
     * Review navigation covers batches that still have uncommitted drafts. Fully committed batches stay
     * stored as commit tombstones but no longer pad "Card N of M" with every card ever scanned.
     */
    fun collectionDrafts(): List<DraftRecord> {
        val all = drafts.value.filter { it.collectionId == collectionId }
        val open = all.filter { it.committedCardId == null }.map { it.batchId }.toSet()
        return all.filter { it.batchId in open }
    }
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
        validateProductUrl(editorCard.tcgplayerUrl, "tcgplayer.com")
        // Apply the same URL rules the price refresh uses, so a saved link can actually be priced.
        editorCard = editorCard.copy(pricechartingUrl = editorCard.pricechartingUrl.takeIf { it.isNotBlank() }
            ?.let { PriceChartingParser.productUrl(it).toString() }.orEmpty())
        val original = editorOriginal
        if (original != null) {
            // The repository drops links/estimates that no longer describe the edited printing while
            // keeping a link, catalog match, or estimate the user supplied in this same edit.
            repository.saveCard(editorCard)
            editorDirty = false
            openCard(editorCard.id)
            val saved = cards.value.find { it.id == editorCard.id }
            if (saved != null && saved.priceCents == null && saved.pricechartingUrl.isNotBlank() &&
                saved.pricechartingUrl != original.pricechartingUrl) {
                try { repository.saveCard(PriceService().refresh(saved)); notice = "Card saved and estimate retrieved." }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = "Card saved. Price unavailable: ${e.message.orEmpty()}".take(220) }
            }
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
            val added = recognized.map { it.copy(collectionId = collection,
                imagePath = it.imagePath.ifBlank { path }, backImagePath = it.backImagePath.ifBlank { extraPath.orEmpty() }) }
            repository.addDrafts(collection, added)
            repository.deleteDraft(placeholder.id)
            // Open this scan's first card, not whichever older uncommitted draft happens to sort first.
            loadDraft(collectionDrafts().indexOfFirst { it.card.id == added.first().id })
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
            val merged = detail.copy(id = current.id, collectionId = current.collectionId, createdAt = current.createdAt,
                imagePath = current.imagePath.ifBlank { detail.imagePath }, backImagePath = current.backImagePath,
                condition = current.condition, grade = current.grade, finish = current.finish,
                tcgplayerUrl = detail.tcgplayerUrl.ifBlank { current.tcgplayerUrl },
                pricechartingUrl = detail.pricechartingUrl.ifBlank { current.pricechartingUrl },
                priceCents = current.priceCents, priceSource = current.priceSource, priceUpdatedAt = current.priceUpdatedAt,
                priceBasis = current.priceBasis, priceQuotes = current.priceQuotes,
                notes = listOf(current.notes, detail.notes).filter(String::isNotBlank).joinToString("\n"))
            // Keep links/estimates only if they still describe the matched printing.
            changeEditor(invalidateChangedIdentity(current, merged))
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
        var skipped = 0
        var stopped: String? = null
        var firstError: String? = null
        // One request per distinct product/grade. Failures are cached too, so duplicates are not retried.
        val results = mutableMapOf<String, Result<CardRecord>>()
        var nextRequestAt = 0L
        for ((index, card) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress = "Updating ${index + 1} of ${targets.size}…"
            if (card.pricechartingUrl.isBlank()) { skipped++; continue }
            val key = "${card.pricechartingUrl}|${card.grade}|${card.finish}|${card.name}|${card.number}|${card.setName}"
            val result = results[key] ?: run {
                // Pace every source request, including failed ones, so errors cannot become a burst.
                val wait = nextRequestAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val fetched: Result<CardRecord> = try { Result.success(PriceService().refresh(card)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                nextRequestAt = System.currentTimeMillis() + 1100
                fetched.also { results[key] = it }
            }
            val error = result.exceptionOrNull()
            if (error != null) {
                failure++
                if (firstError == null) firstError = error.message
                if (error is IntegrationException && error.kind == IntegrationException.Kind.RATE_LIMIT) {
                    stopped = error.message
                    break
                }
                continue
            }
            val refreshed = result.getOrThrow()
            // Never overwrite a card that changed after the refresh began.
            if (cards.value.find { it.id == card.id } != card) { skipped++; continue }
            repository.saveCard(card.copy(priceCents = refreshed.priceCents, priceSource = refreshed.priceSource,
                priceBasis = refreshed.priceBasis, priceUpdatedAt = refreshed.priceUpdatedAt,
                // A cached result came from another copy; keep this card's own non-PriceCharting quotes.
                priceQuotes = card.priceQuotes.filterNot { it.source == "PriceCharting" } +
                    refreshed.priceQuotes.filter { it.source == "PriceCharting" }))
            success++
        }
        notice = buildString {
            append("$success price(s) updated; $failure unavailable")
            if (skipped > 0) append("; $skipped skipped (no product link or edited)")
            append(". Previous estimates retained.")
            stopped?.let { append(" Stopped early: ").append(it) }
            // A single-card refresh should say why it failed (e.g. a grade with no source column).
            if (targets.size == 1 && stopped == null) firstError?.let { append(" ").append(it) }
        }.take(400)
    }
    fun findPrices(card: CardRecord) {
        if (busy) return
        priceTarget = card
        // "Search again" runs from the match screen itself. Keep the original return target, otherwise a
        // confirmed match for a draft/editor card would be saved directly as a new owned card.
        if (screen != "prices") priceReturn = screen
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
            val latest = cards.value.find { it.id == target.id } ?: error("This card no longer exists.")
            val updated = latest.copy(pricechartingUrl = candidate.url, priceCents = null, priceSource = "", priceUpdatedAt = null, priceQuotes = emptyList())
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

/** TCGplayer links are reference-only; PriceCharting links are validated by PriceChartingParser.productUrl. */
private fun validateProductUrl(value: String, domain: String) {
    if (value.isBlank()) return
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && uri.host in setOf(domain, "www.$domain") &&
        uri.userInfo == null && uri.port in setOf(-1, 443)) { "Use a valid HTTPS $domain product link." }
    require(if (domain == "pricecharting.com") uri.path.startsWith("/game/") else uri.path.startsWith("/product/")) {
        "Paste the actual $domain product page, not a search link."
    }
}
