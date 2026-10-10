package com.tcgninja.app

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.tcgninja.app.data.CardRecord
import com.tcgninja.app.ui.NinjaApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: NinjaViewModel

    @Before fun start() {
        vm = NinjaViewModel(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            vm.repository.awaitReady()
            vm.collections.value.toList().forEach { vm.repository.deleteCollection(it.id) }
        }
    }

    @Test fun createCollectionThroughHomeForm() {
        compose.setContent { NinjaApp(vm) }
        compose.onNodeWithText("Your collection starts here").assertIsDisplayed()
        compose.onNodeWithText("New collection", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Collection name").performTextInput("Weekend binder")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vm.collections.value.any { it.name == "Weekend binder" } && !vm.busy
        }
        compose.onNodeWithText("Weekend binder").assertIsDisplayed()
        compose.onNodeWithText("Add cards", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun typedSearchFiltersOwnedCardsWithoutCrossCollectionLeakage() {
        val id = runBlocking {
            val a = vm.repository.createCollection("First binder")
            val b = vm.repository.createCollection("Second binder")
            vm.repository.saveCard(CardRecord(collectionId = a, name = "Charizard", category = "Pokémon", priceCents = 500))
            vm.repository.saveCard(CardRecord(collectionId = a, name = "Pikachu", category = "Pokémon"))
            vm.repository.saveCard(CardRecord(collectionId = b, name = "Charizard from other binder", category = "Pokémon"))
            a
        }
        vm.openCollection(id)
        compose.setContent { NinjaApp(vm) }
        compose.onNodeWithText("Search Pokémon or card name").performTextInput("charizard")
        compose.onNodeWithText("1 results").assertIsDisplayed()
        compose.onNodeWithText("Charizard").assertIsDisplayed()
        compose.onNodeWithText("Pikachu").assertDoesNotExist()
        compose.onNodeWithText("Charizard from other binder").assertDoesNotExist()
    }

    @Test fun resumedDraftRequiresExplicitCommit() {
        val id = runBlocking {
            val a = vm.repository.createCollection("Draft binder")
            vm.repository.addDrafts(a, listOf(CardRecord(collectionId = a, name = "Pikachu", category = "Pokémon")))
            a
        }
        vm.openCollection(id)
        compose.setContent { NinjaApp(vm) }
        compose.onNodeWithText("Resume 1 uncommitted cards").performClick()
        compose.onNodeWithText("Card 1 of 1").assertIsDisplayed()
        assertTrue(vm.cards.value.isEmpty())
        compose.onNodeWithTag("card-editor").performScrollToNode(hasText("Commit to collection"))
        compose.onNodeWithText("Commit to collection", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vm.cards.value.size == 1 && !vm.busy
        }
        assertEquals("Pikachu", vm.cards.value.single().name)
        assertNotNull(vm.drafts.value.single().committedCardId)
    }
}
