@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tcgninja.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tcgninja.app.NinjaViewModel
import com.tcgninja.app.data.*

@Composable
fun ChoiceField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(value, {}, label = { Text(label) }, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true)
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (options + listOf(value).filter { it.isNotBlank() && it !in options }).distinct().forEach { option ->
                DropdownMenuItem(text = { Text(option.ifBlank { "Not specified" }) }, onClick = { open = false; onChange(option) })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = !multiline,
        minLines = if (multiline) 3 else 1)
}

@Composable
fun CardEditor(vm: NinjaViewModel, drafts: List<DraftRecord>) {
    val context = LocalContext.current
    val c = vm.editorCard
    val draft = drafts.find { it.id == vm.editorDraftId }
    val committed = draft?.committedCardId != null
    val review = vm.screen == "review"
    val count = vm.collectionDrafts().size
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReread by remember { mutableStateOf(false) }
    var priceText by remember(c.id) { mutableStateOf(c.priceCents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty()) }
    var priceError by remember(c.id) { mutableStateOf<String?>(null) }
    val frontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.runTask("Saving front photo…") { vm.changeEditor(vm.editorCard.copy(imagePath = ImageFiles.importUri(context, uri))); vm.flushDraft() } }
    }
    val backPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.runTask("Saving additional photo…") { vm.changeEditor(vm.editorCard.copy(backImagePath = ImageFiles.importUri(context, uri))); vm.flushDraft() } }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard this draft?") }, text = { Text("The card has not been added to your collection. Other drafts stay saved.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discardDraft() }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } })
    if (confirmReread) AlertDialog(onDismissRequest = { confirmReread = false }, title = { Text("Read photos with AI?") }, text = { Text("This sends the selected photos to ${vm.settings.provider}. Extracted details will replace this draft's fields; you can review them before committing.") },
        confirmButton = { TextButton(onClick = { confirmReread = false; vm.retryRecognition() }) { Text("Read photos") } }, dismissButton = { TextButton(onClick = { confirmReread = false }) { Text("Cancel") } })
    LazyColumn(modifier = Modifier.testTag("card-editor"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (review) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.stepDraft(-1) }, enabled = vm.reviewIndex > 0 && !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous card") }
                Text("Card ${vm.reviewIndex + 1} of $count", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.stepDraft(1) }, enabled = vm.reviewIndex < count - 1 && !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next card") }
            }
            Text(if (committed) "Already committed. Open the saved card to edit it." else "Check the exact printing and finish. Changes stay in this draft until you commit.", style = MaterialTheme.typography.bodyMedium)
        }
        item { CardImage(c.imagePath, Modifier.fillMaxWidth().height(220.dp)) }
        if (committed) {
            item { Button(onClick = { draft?.committedCardId?.let(vm::openCard) }, modifier = Modifier.fillMaxWidth()) { Text("Open saved card") } }
        } else {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { frontPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Front photo") }
                OutlinedButton(onClick = { backPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text(if (c.backImagePath.isBlank()) "Add back / angle" else "Change extra photo") }
            } }
            if (c.backImagePath.isNotBlank()) item { CardImage(c.backImagePath, Modifier.fillMaxWidth().height(140.dp)) }
            if (c.imagePath.isNotBlank() && !c.imagePath.startsWith("https://")) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.cropReturn = vm.screen; vm.cropPath = c.imagePath; vm.screen = "crop" }) { Text("Crop front photo") }
                    if (c.backImagePath.isNotBlank() && !c.backImagePath.startsWith("https://")) TextButton(onClick = { vm.cropReturn = vm.screen; vm.cropPath = c.backImagePath; vm.screen = "crop" }) { Text("Crop from extra photo") }
                }
            }
            if (review && c.imagePath.isNotBlank()) item { TextButton(onClick = { confirmReread = true }) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Read photos with AI") } }
            item { Field("Card name *", c.name, { vm.changeEditor(c.copy(name = it)) }) }
            item { TextButton(onClick = vm::matchCatalog) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Match to catalog printing") } }
            item { ChoiceField("Category", c.category, Taxonomy.categories) { vm.changeEditor(c.copy(category = it, subtype = "", hp = if (it == "Pokémon") c.hp else "")) } }
            item { ChoiceField("Subtype", c.subtype, listOf("") + Taxonomy.subtypes(c.category)) { vm.changeEditor(c.copy(subtype = it)) } }
            if (c.category == "Pokémon" || c.category == "Pokemon") {
                item { Field("Pokémon species / names (comma separated)", c.species, { vm.changeEditor(c.copy(species = it)) }) }
                item { Field("Printed types (comma separated)", c.types, { vm.changeEditor(c.copy(types = it)) }) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(c.hp, { if (it.all(Char::isDigit)) vm.changeEditor(c.copy(hp = it)) }, label = { Text("HP") }, singleLine = true, modifier = Modifier.weight(1f))
                    Box(Modifier.weight(2f)) { ChoiceField("Stage", c.stage, listOf("") + Taxonomy.stages) { vm.changeEditor(c.copy(stage = it)) } }
                } }
            }
            item { Field("Set / expansion", c.setName, { vm.changeEditor(c.copy(setName = it)) }) }
            item { Field("Collector number (e.g. 004/102)", c.number, { vm.changeEditor(c.copy(number = it)) }) }
            item { Field("Rarity", c.rarity, { vm.changeEditor(c.copy(rarity = it)) }) }
            item { ChoiceField("Finish", c.finish, Taxonomy.finishes) { vm.changeEditor(c.copy(finish = it)) } }
            item { Field("Mechanics / special tags", c.mechanics, { vm.changeEditor(c.copy(mechanics = it)) }) }
            item { ChoiceField("Physical condition", c.condition, listOf("Unknown", "Near Mint", "Lightly Played", "Moderately Played", "Heavily Played", "Damaged")) { vm.changeEditor(c.copy(condition = it)) } }
            item { ChoiceField("Price grade / basis", c.grade, listOf("Ungraded", "Grade 7", "Grade 8", "Grade 9", "Grade 9.5", "PSA 10")) { vm.changeEditor(c.copy(grade = it)) } }
            item { Text("Grade labels select the source's price column; the app does not grade your card or infer its condition.", style = MaterialTheme.typography.bodySmall) }
            item { HorizontalDivider(); Spacer(Modifier.height(8.dp)); Text("Pricing & source links", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(priceText, { value ->
                    priceText = value
                    try {
                        val cents = Money.parseCents(value)
                        priceError = null
                        vm.changeEditor(c.copy(priceCents = cents, priceSource = if (cents == null) "" else "Manual", priceUpdatedAt = if (cents == null) null else System.currentTimeMillis(), priceBasis = c.grade))
                    } catch (e: IllegalArgumentException) { priceError = e.message }
                }, label = { Text("Manual estimate (USD, optional)") }, isError = priceError != null, supportingText = { priceError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item { Field("Verified PriceCharting product URL", c.pricechartingUrl, { vm.changeEditor(c.copy(pricechartingUrl = it.trim())) }) }
            item { TextButton(onClick = { vm.findPrices(c) }, enabled = c.name.isNotBlank()) { Text("Find a matching PriceCharting product") } }
            item { Text("Use the exact product page for this set, number, and finish. Prices update only on commit or when you press Update. Search links are available from the saved profile.", style = MaterialTheme.typography.bodySmall) }
            item { Field("TCGplayer product URL (optional)", c.tcgplayerUrl, { vm.changeEditor(c.copy(tcgplayerUrl = it.trim())) }) }
            if (!review) item { Text("Changing the card name, set, number, finish, or grade clears old catalog links and estimates to avoid using a different variant's value.", style = MaterialTheme.typography.bodySmall) }
            item { Field("Notes / attacks / rules text", c.notes, { vm.changeEditor(c.copy(notes = it)) }, multiline = true) }
            item { Button(onClick = vm::saveEditor, enabled = c.name.isNotBlank() && priceError == null && !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text(if (review) "Commit to collection" else "Save changes")
            } }
            if (review) item { TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) { Text("Discard this draft", color = MaterialTheme.colorScheme.error) } }
            if (review) item { TextButton(onClick = vm::duplicateDraft, modifier = Modifier.fillMaxWidth()) { Text("Create another draft from this photo") } }
        }
    }
}

@Composable
fun SettingsScreen(vm: NinjaViewModel, export: () -> Unit, import: () -> Unit) {
    var provider by remember { mutableStateOf(vm.settings.provider) }
    var openAiModel by remember { mutableStateOf(vm.settings.openAiModel) }
    var anthropicModel by remember { mutableStateOf(vm.settings.anthropicModel) }
    // Never place secrets in rememberSaveable or display an existing key.
    var newKey by remember { mutableStateOf("") }
    var hasKey by remember(provider) { mutableStateOf(runCatching { vm.settings.getKey(provider).isNotBlank() }.getOrDefault(false)) }
    var confirmImport by remember { mutableStateOf(false) }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("Restore a backup") }, text = { Text("Choose a TCG Ninja backup file. Imported records will be merged with your collections. API keys are never restored.") }, confirmButton = { TextButton(onClick = { confirmImport = false; import() }) { Text("Choose file") } }, dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } })
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("AI recognition", style = MaterialTheme.typography.headlineSmall); Text("Bring your own API key. Scanning sends your selected photos directly to the selected provider and may incur API usage charges.") }
        item { ChoiceField("Provider", provider, listOf("OpenAI", "Anthropic")) { provider = it; newKey = "" } }
        item { OutlinedTextField(newKey, { newKey = it }, label = { Text(if (hasKey) "Replace saved API key" else "API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text(if (hasKey) "A key is saved securely for this provider." else "No key saved for this provider.", style = MaterialTheme.typography.bodySmall) }
        item { Field("OpenAI model", openAiModel, { openAiModel = it.trim() }) }
        item { Field("Anthropic model", anthropicModel, { anthropicModel = it.trim() }) }
        item { Button(onClick = {
            runCatching {
                require(openAiModel.isNotBlank() && anthropicModel.isNotBlank()) { "Enter model IDs for both providers." }
                vm.settings.provider = provider; vm.settings.openAiModel = openAiModel; vm.settings.anthropicModel = anthropicModel
                if (newKey.isNotBlank()) vm.settings.setKey(provider, newKey.trim())
                newKey = ""; hasKey = vm.settings.getKey(provider).isNotBlank()
            }.onSuccess { vm.notice = "AI settings saved." }.onFailure { vm.notice = "Unable to save settings: ${it.message}" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save AI settings") } }
        if (hasKey) item { TextButton(onClick = {
            runCatching { vm.settings.setKey(provider, ""); hasKey = false; newKey = "" }.onFailure { vm.notice = "Could not remove saved key." }
        }) { Text("Remove $provider key") } }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Microsoft Copilot & subscription sign-in", style = MaterialTheme.typography.titleSmall)
            Text("Not connected in this build. A supported consumer Copilot image API has not been verified. ChatGPT/Claude subscriptions are not API keys. OpenAI and Anthropic API-key connections are available above.", style = MaterialTheme.typography.bodyMedium)
        } } }
        item { HorizontalDivider() }
        item { Text("Local backup", style = MaterialTheme.typography.headlineSmall); Text("Export your collections and photos to a file you control. Keep it somewhere safe. Provider keys are excluded. This version supports up to 32 MiB of unique photo data per backup; catalog-image links remain online references.") }
        item { OutlinedButton(onClick = export, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("Export backup") } }
        item { OutlinedButton(onClick = { confirmImport = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import backup") } }
        item { Text("English cards · USD estimates · Stored on this device", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun PrivacyScreen() {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Your cards stay yours.", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Collections, drafts, notes, captured photos, and saved prices are stored locally. There is no app account, collection sync, advertising, or analytics. Android automatic cloud backup is disabled; use local export/import to preserve your collection.") }
        item { Text("When you choose AI scanning, selected photos are sent to OpenAI or Anthropic using your API key. Those providers process the images under their own terms. Review every result; recognition and finish detection can be wrong.") }
        item { Text("Keys are protected using Android Keystore and are excluded from exports. Exported backup files contain your collection information and photos and are not encrypted by this app.") }
        item { Text("Catalog searches contact TCGdex. Remote card artwork is loaded from the catalog's image host. Opening source links contacts the corresponding website. Explicit price refresh contacts PriceCharting and saves the retrieved estimate and timestamp. No automatic periodic price refresh runs.") }
        item { Text("PriceCharting estimates are reference values, not offers to buy your card. Grade and finish must match the source product. TCGplayer is linked for reference; this build does not scrape it. PriceCharting HTML access is an experimental personal-use integration and may stop working if pages change.") }
        item { Text("Data credits", style = MaterialTheme.typography.titleLarge); Text("Card metadata and catalog imagery: TCGdex (tcgdex.net). Pokémon names and card artwork belong to their respective owners. TCG Ninja is an independent personal collection tool and is not affiliated with Pokémon, TCGplayer, or PriceCharting.") }
        item { Text("Deleting a collection removes its owned cards and drafts. Backups you exported are separate files and remain wherever you saved them. Unreferenced photos may remain until local cleanup; uninstalling the app removes its private storage.") }
    }
}
