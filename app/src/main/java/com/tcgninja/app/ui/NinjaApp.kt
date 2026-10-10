@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tcgninja.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tcgninja.app.NinjaViewModel
import com.tcgninja.app.data.*
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

private val LightColors = lightColorScheme(primary = Color(0xFF326844), secondary = Color(0xFF56634E),
    tertiary = Color(0xFF42656F), background = Color(0xFFF8FAF5), surface = Color(0xFFF8FAF5))
private val DarkColors = darkColorScheme(primary = Color(0xFFACD9A0), secondary = Color(0xFFC0CCB3),
    background = Color(0xFF101611), surface = Color(0xFF101611))

@Composable
fun NinjaApp(vm: NinjaViewModel = viewModel()) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        val collections by vm.collections.collectAsStateWithLifecycle()
        val cards by vm.cards.collectAsStateWithLifecycle()
        val drafts by vm.drafts.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        var dialog by remember { mutableStateOf("") }
        var newName by rememberSaveable { mutableStateOf("") }
        var bulkCategory by remember { mutableStateOf("All categories") }
        var settingsReturn by remember { mutableStateOf("home") }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importBackup) }
        val collection = collections.find { it.id == vm.collectionId }
        val owned = cards.filter { it.collectionId == vm.collectionId }
        val card = cards.find { it.id == vm.cardId }
        val pending = drafts.filter { it.collectionId == vm.collectionId && it.committedCardId == null }
        fun back() {
            when (vm.screen) {
                "home" -> Unit
                "collection" -> vm.screen = "home"
                "new" -> vm.screen = "home"
                "settings", "privacy" -> vm.screen = settingsReturn
                "edit" -> if (vm.editorDirty) dialog = "discard" else vm.screen = "profile"
                "review" -> vm.leaveReview()
                "catalog" -> if (vm.catalogForEditor) { vm.catalogForEditor = false; vm.screen = if (vm.editorDraftId.isBlank()) "edit" else "review" } else vm.screen = "collection"
                "prices" -> vm.screen = vm.priceReturn
                else -> vm.screen = "collection"
            }
        }
        BackHandler(vm.screen != "home") { if (!vm.busy) back() }
        LaunchedEffect(vm.notice) {
            vm.notice?.let { val message = it; vm.notice = null; snackbar.showSnackbar(message) }
        }
        if (vm.screen == "camera") {
            CameraScreen(vm.binder, { path -> vm.screen = "collection"; vm.recognize(path) }, { vm.screen = "collection" })
        } else if (vm.screen == "crop") {
            CropScreen(vm.cropPath, { path -> vm.changeEditor(vm.editorCard.copy(imagePath = path)); vm.screen = vm.cropReturn }, { vm.screen = vm.cropReturn })
        } else Scaffold(
            topBar = {
                TopAppBar(title = { Column {
                    Text(when (vm.screen) {
                        "home" -> "TCG Ninja"
                        "new" -> "New collection"
                        "collection" -> collection?.name ?: "Collection"
                        "profile" -> "Card profile"
                        "edit" -> "Edit card"
                        "review" -> "Review cards"
                        "settings" -> "Settings"
                        "catalog" -> "Find a card"
                        "prices" -> "Match a price source"
                        else -> "Privacy & sources"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (vm.screen == "home") Text("YOUR COLLECTION, IN FOCUS", style = MaterialTheme.typography.labelSmall)
                } }, navigationIcon = {
                    if (vm.screen != "home") IconButton(onClick = ::back, enabled = !vm.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }, actions = {
                    when (vm.screen) {
                        "home" -> {
                            IconButton(onClick = { settingsReturn = "home"; vm.screen = "privacy" }) { Icon(Icons.Default.Info, "Privacy and data sources") }
                            IconButton(onClick = { settingsReturn = "home"; vm.screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                        "collection" -> {
                            IconButton(onClick = { bulkCategory = "All categories"; dialog = "refresh" }, enabled = owned.isNotEmpty() && !vm.busy) { Icon(Icons.Default.Refresh, "Refresh category prices") }
                            IconButton(onClick = { dialog = "deleteCollection" }, enabled = !vm.busy) { Icon(Icons.Default.DeleteOutline, "Delete collection") }
                        }
                        "profile" -> if (card != null) {
                            IconButton(onClick = { vm.edit(card) }, enabled = !vm.busy) { Icon(Icons.Default.Edit, "Edit card") }
                            IconButton(onClick = { dialog = "deleteCard" }, enabled = !vm.busy) { Icon(Icons.Default.DeleteOutline, "Remove card") }
                        }
                    }
                })
            }, snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                when (vm.screen) {
                    "home" -> ExtendedFloatingActionButton(onClick = { newName = ""; vm.screen = "new" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New collection") })
                    "collection" -> ExtendedFloatingActionButton(onClick = { dialog = "add" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add cards") })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (vm.screen) {
                    "new" -> Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("A place for your cards.", style = MaterialTheme.typography.headlineMedium)
                        Text("Name your binder, deck, or group of favorites. Each card will have its own record.")
                        OutlinedTextField(newName, { newName = it.take(100) }, label = { Text("Collection name") }, placeholder = { Text("My first binder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.createCollection(newName) }, enabled = newName.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Create") }
                    }
                    "home" -> LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { Hero("Make room for your favorites.", "Scan, organize, and rediscover the cards you own.", "${collections.size} collections  ·  ${cards.size} cards") }
                        if (collections.isEmpty()) item { EmptyState("Your collection starts here", "Create a collection for a binder, a deck, or your favorite Pokémon.", Icons.Default.CollectionsBookmark) }
                        items(collections, key = { it.id }) { c ->
                            val count = cards.count { it.collectionId == c.id }
                            ElevatedCard(onClick = { vm.openCollection(c.id) }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                                        Text("$count physical cards", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open collection")
                                }
                            }
                        }
                        item { TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Restore a local backup") } }
                    }
                    "collection" -> CollectionScreen(vm, owned, pending.size)
                    "profile" -> if (card != null) ProfileScreen(card, { vm.refreshPrices(listOf(card)) }, { vm.edit(card) }, { vm.findPrices(card) }) else EmptyState("Card no longer exists", "Return to your collection.", Icons.Default.SearchOff)
                    "edit", "review" -> CardEditor(vm, drafts)
                    "settings" -> SettingsScreen(vm, { exportLauncher.launch("tcgninja-backup.json") }, { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    "catalog" -> CatalogScreen(vm)
                    "prices" -> PriceMatchesScreen(vm)
                    "privacy" -> PrivacyScreen()
                }
                if (vm.busy) Surface(Modifier.fillMaxSize().clickable(enabled = true, onClick = {}), color = MaterialTheme.colorScheme.scrim.copy(alpha = .45f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(Modifier.padding(32.dp)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text(vm.progress)
                            TextButton(onClick = vm::cancelTask) { Text("Cancel") }
                        } }
                    }
                }
            }
        }
        when (dialog) {
            "add" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Add to ${collection?.name.orEmpty()}") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Photos are sent only when you choose a scan. Configure your AI key in Settings first.")
                    FilledTonalButton(onClick = { dialog = ""; vm.binder = false; vm.screen = "camera" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Scan one card") }
                    FilledTonalButton(onClick = { dialog = ""; vm.binder = true; vm.screen = "camera" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.GridView, null); Spacer(Modifier.width(8.dp)); Text("Scan a binder page") }
                    OutlinedButton(onClick = { dialog = ""; vm.catalogForEditor = false; vm.catalogResults = emptyList(); vm.screen = "catalog" }, modifier = Modifier.fillMaxWidth()) { Text("Search English catalog") }
                    OutlinedButton(onClick = { dialog = ""; vm.newCard() }, modifier = Modifier.fillMaxWidth()) { Text("Enter a card manually") }
                    TextButton(onClick = { dialog = ""; settingsReturn = "collection"; vm.screen = "settings" }) { Text("AI settings") }
                }
            }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } })
            "deleteCard", "deleteCollection", "discard" -> AlertDialog(onDismissRequest = { dialog = "" },
                title = { Text(when (dialog) { "deleteCard" -> "Remove this card?"; "deleteCollection" -> "Delete this collection?"; else -> "Discard unsaved edits?" }) },
                text = { Text(when (dialog) { "deleteCard" -> "This removes this physical copy only."; "deleteCollection" -> "All cards and scan drafts in this collection will be removed. Other collections are unaffected. Export a backup first if you want to restore it later."; else -> "Your saved card will remain unchanged." }) },
                confirmButton = { TextButton(onClick = { val action = dialog; dialog = ""; when (action) { "deleteCard" -> vm.deleteCard(); "deleteCollection" -> vm.deleteCollection(); else -> vm.screen = "profile" } }) { Text(if (dialog == "discard") "Discard" else "Delete") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
            "refresh" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Refresh saved estimates") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Only cards with a verified PriceCharting product link can update. Existing estimates stay saved if a refresh fails.")
                    ChoiceField("Category", bulkCategory, listOf("All categories") + owned.map { Taxonomy.categoryLabel(it.category) }.distinct()) { bulkCategory = it }
                }
            }, confirmButton = { TextButton(onClick = { dialog = ""; vm.refreshPrices(owned.filter { bulkCategory == "All categories" || Taxonomy.categoryLabel(it.category) == bulkCategory }) }) { Text("Update prices") } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun Hero(title: String, subtitle: String, caption: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Style, null, modifier = Modifier.size(36.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge)
            Text(caption, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CollectionScreen(vm: NinjaViewModel, cards: List<CardRecord>, pending: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filterError: String? = null
    val filtered = try {
        val min = Money.parseCents(vm.minPrice)
        val max = Money.parseCents(vm.maxPrice)
        require(min == null || max == null || min <= max) { "Minimum value must not exceed maximum value." }
        val f = CardFilter(query = vm.query, category = vm.category, subtype = vm.subtype, finish = vm.finish,
            mechanic = vm.mechanic, minCents = min, maxCents = max)
        cards.filter(f::matches)
    } catch (e: IllegalArgumentException) { filterError = e.message; emptyList() }
    val available = cards.map { Taxonomy.categoryLabel(it.category) }.distinct().sorted()
    LaunchedEffect(available) { if (vm.category.isNotBlank() && vm.category !in available) { vm.category = ""; vm.subtype = "" } }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${cards.size} cards", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text(if (expanded) "Hide filters" else "Filters") }
            }
            OutlinedTextField(vm.query, { vm.query = it }, label = { Text("Search Pokémon or card name") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item { ChoiceField("Category", vm.category.ifBlank { "All categories" }, listOf("All categories") + available) { vm.category = if (it == "All categories") "" else it; vm.subtype = "" } }
        if (expanded) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val subtypes = cards.filter { vm.category.isBlank() || Taxonomy.categoryLabel(it.category) == vm.category }.map { it.subtype.ifBlank { it.stage } }.filter { it.isNotBlank() }.distinct().sorted()
                ChoiceField("Subtype", vm.subtype.ifBlank { "All subtypes" }, listOf("All subtypes") + subtypes) { vm.subtype = if (it == "All subtypes") "" else it }
                ChoiceField("Finish", vm.finish.ifBlank { "All finishes" }, listOf("All finishes") + Taxonomy.finishes) { vm.finish = if (it == "All finishes") "" else it }
                ChoiceField("Mechanic", vm.mechanic.ifBlank { "All mechanics" }, listOf("All mechanics") + Taxonomy.mechanics) { vm.mechanic = if (it == "All mechanics") "" else it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(vm.minPrice, { vm.minPrice = it }, modifier = Modifier.weight(1f), label = { Text("Min USD") }, singleLine = true)
                    OutlinedTextField(vm.maxPrice, { vm.maxPrice = it }, modifier = Modifier.weight(1f), label = { Text("Max USD") }, singleLine = true)
                }
                Text("Bounds include the entered amounts. Unpriced cards are excluded when a value bound is set.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.query = ""; vm.category = ""; vm.subtype = ""; vm.finish = ""; vm.mechanic = ""; vm.minPrice = ""; vm.maxPrice = "" }) { Text("Clear filters") }
            }
        }
        if (filterError != null) item { Text(filterError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        if (pending > 0) item {
            FilledTonalButton(onClick = { vm.loadDraft(vm.collectionDrafts().indexOfFirst { it.committedCardId == null }) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Inbox, null); Spacer(Modifier.width(8.dp)); Text("Resume $pending uncommitted cards") }
        }
        item { Text("${filtered.size} results", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (filtered.isEmpty()) item { EmptyState(if (cards.isEmpty()) "A fresh collection" else "No matching cards", if (cards.isEmpty()) "Tap Add cards to scan, search the catalog, or enter your first card." else "Try another name or adjust the filters.", Icons.Default.Style) }
        items(filtered, key = { it.id }) { card -> CardRow(card, { vm.openCard(card.id) }) }
    }
}

@Composable
fun CardRow(card: CardRecord, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CardImage(card.imagePath, Modifier.width(60.dp).height(84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.name.ifBlank { "Unidentified card" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(card.setName, card.number).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { card.category }, style = MaterialTheme.typography.bodySmall)
                Text(listOf(card.finish, card.grade).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(card.priceCents), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "View card", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun CardImage(path: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
        if (path.isBlank()) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Style, "No card photo", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        else AsyncImage(model = if (path.startsWith("https://")) path else File(path), contentDescription = "Card photo", contentScale = ContentScale.Fit)
    }
}

@Composable
private fun ProfileScreen(card: CardRecord, refresh: () -> Unit, edit: () -> Unit, findPrices: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" && uri.host in setOf("www.pricecharting.com", "pricecharting.com", "www.tcgplayer.com", "tcgplayer.com")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }
    val search = URLEncoder.encode(listOf(card.name, card.setName, card.number).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CardImage(card.imagePath, Modifier.fillMaxWidth().height(290.dp)) }
        item {
            Text(card.name, style = MaterialTheme.typography.headlineMedium)
            Text(listOf(card.setName, card.number).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        }
        item { Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SAVED ESTIMATE · USD", style = MaterialTheme.typography.labelSmall)
            Text(Money.format(card.priceCents), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (card.priceCents == null) "Add a verified PriceCharting link or enter your own estimate." else "${card.priceSource} · ${card.priceBasis}", style = MaterialTheme.typography.bodyMedium)
            card.priceUpdatedAt?.let { Text("Updated ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = refresh, enabled = card.pricechartingUrl.isNotBlank()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Update price") }
            TextButton(onClick = findPrices) { Text("Find / change price match") }
            if (card.priceQuotes.isNotEmpty()) {
                HorizontalDivider()
                Text("Other saved price columns", style = MaterialTheme.typography.labelMedium)
                card.priceQuotes.forEach { quote ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(quote.basis); Text(Money.format(quote.cents)) }
                }
            }
        } } }
        item { Column {
            TextButton(onClick = { open(card.pricechartingUrl.ifBlank { "https://www.pricecharting.com/search-products?type=prices&q=$search&go=Go" }) }) { Text(if (card.pricechartingUrl.isBlank()) "Search PriceCharting ↗" else "PriceCharting product ↗") }
            TextButton(onClick = { open(card.tcgplayerUrl.ifBlank { "https://www.tcgplayer.com/search/pokemon/product?q=$search&view=grid" }) }) { Text(if (card.tcgplayerUrl.isBlank()) "Search TCGplayer ↗" else "TCGplayer product ↗") }
        } }
        item { HorizontalDivider() }
        val details = listOf("Category" to card.category, "Subtype" to card.subtype, "Pokémon" to card.species,
            "Types" to card.types, "HP" to card.hp, "Stage" to card.stage, "Rarity" to card.rarity,
            "Finish" to card.finish, "Mechanics" to card.mechanics, "Condition" to card.condition, "Grade" to card.grade,
            "Notes & card text" to card.notes)
        items(details.filter { it.second.isNotBlank() }) { (label, value) ->
            Column { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) }
        }
        if (card.backImagePath.isNotBlank()) item { Text("Additional photo"); CardImage(card.backImagePath, Modifier.fillMaxWidth().height(200.dp)) }
        item { OutlinedButton(onClick = edit, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit this physical copy") } }
    }
}

@Composable
private fun PriceMatchesScreen(vm: NinjaViewModel) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<com.tcgninja.app.integration.PriceCandidate?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(vm.priceTarget?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Text("Match the exact set, collector number, edition, and finish. Similar-looking cards can have different values. Prices come from PriceCharting.")
        }
        if (vm.priceCandidates.isEmpty()) item {
            Text("No candidates loaded. Try again, or open PriceCharting from the profile and paste the exact product link in Edit.")
            OutlinedButton(onClick = { vm.priceTarget?.let(vm::findPrices) }) { Text("Search again") }
        }
        items(vm.priceCandidates, key = { it.url }) { candidate ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                Text(candidate.url.substringAfter("/game/"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.url))) } }) { Text("View source ↗") }
                    OutlinedButton(onClick = { selected = candidate }) { Text("Use this product") }
                }
            } }
        }
    }
    selected?.let { candidate ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Confirm exact printing") }, text = { Text("${candidate.title}\n\nDoes this product match your card's set, collector number, edition, and finish? A mismatch would give the wrong estimate.") },
            confirmButton = { TextButton(onClick = { selected = null; vm.choosePrice(candidate) }) { Text("Confirm match") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
    }
}

@Composable
private fun CatalogScreen(vm: NinjaViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Search English printings by name. Select a result to review before adding it. Catalog: TCGdex.") }
        item { OutlinedTextField(vm.catalogQuery, { vm.catalogQuery = it }, singleLine = true, label = { Text("Card name, e.g. Charizard") }, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = vm::searchCatalog, enabled = vm.catalogQuery.trim().length >= 2 && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Search catalog") } }
        items(vm.catalogResults, key = { it.catalogId }) { CardRow(it, { vm.useCatalog(it) }) }
        if (vm.catalogResults.isEmpty()) item { Text("Results will appear here. You can also add an unknown card manually from your collection.", style = MaterialTheme.typography.bodyMedium) }
    }
}
