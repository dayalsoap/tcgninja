# Goal
I want to create a Android pokemon application that will allow the user to organize their pokemon cards.

# Card Categories
The Pokémon Trading Card Game (TCG) divides cards into three main categories:
Pokémon Cards, Trainer Cards, and Energy Cards.

**Instructions for Agent: Before starting the code, please make sure these categories and subcategories are correct and complete! Also, we need to include all the "characters" like Charizard and everything else. What are these characters? We will need to augment it over time. This guide might contain useful information: https://tcg.pokemon.com/en-us/product-guide**

## Pokemon Cards

Pokémon cards represent the creatures you use to battle. They feature an elemental type and a stage of evolution.
- Elemental Types (11 total): Grass, Fire, Water, Lightning, Psychic, Fighting, Darkness, Metal, Dragon, Colorless, and Fairy (retired in 2020). Note that types like Rock and Ground group under Fighting, while Poison groups under Psychic.
- Evolution Stages:
	- Basic: Can be played directly from your hand onto your Bench.
	- Stage 1: Evolves from a Basic Pokémon.
	- Stage 2: Evolves from a Stage 1 Pokémon.
- Special Categories & Mechanics: Includes legacy and modern heavy-hitters like Pokémon EX, GX, V, VMAX, VSTAR, and Pokémon-ex.

## Trainer Cards

Trainer cards give you strategic advantages, draw cards, heal Pokémon, or disrupt your opponent. They split into three sub-types:
- Item Cards: You can play as many Item cards as you want during your turn (e.g., Potions, Ultra Ball).
- Supporter Cards: Stronger effects than Items, but you can only play one Supporter card per turn (e.g., Professor's Research, Boss's Orders).
- Stadium Cards: Represent a battle location and stay in play, affecting both players until a new Stadium card replaces it.
- ACE SPEC Cards: A powerful, game-changing class of Trainer cards where you are restricted to only one ACE SPEC card in your entire deck


## Energy Cards

Energy cards power your Pokémon's attacks.
- Basic Energy: Standard elemental energy cards (Grass, Fire, Water, Lightning, Psychic, Fighting, Darkness, Metal). You can attach one Basic Energy to a Pokémon per turn.
- Special Energy: Provide matching or multi-color energy plus extra utility (such as Double Colorless Energy or attack/defense boosts).


## Rarity and Special Finishes

You can identify a card's baseline tier by checking the bottom corner or looking at its holographic treatment:
- Standard Rarities: Common (black circle), Uncommon (black diamond), and Rare (black star).
- Holographic Styles: Regular Holofoil (shine only in the art box) and Reverse Holofoil (shine across the whole card except the art box).
- Ultra & Secret Rarities: Full Art, Illustration Rare, Special Illustration Rare, and Hyper Rare (gold/textured) cards.

# Features

## Inputting Cards

The user should be able to use their device's camera to scan the picture. The app wil use AI (described below) to determine what the card is and place it accordinly. It should gather all relevant information about the card, including type, HP, character name, any numbers associated with the card, and whether or not it's a special card (e.g., holographic). This might require scanning both sides of the card. 

The cards can be scanned 1 by 1, or the user should be able to capture an entire sheet of cards from a binder. Upon scanning the card, the user can see the card information and manually adjust it if necessary. In the case of scanning a whole sheet, the user should be able to press a forward or backward arrow to move accross the cards individually. Additionally, each screen for the card will have a "commit" button to let the user commit that card to the collection. 

Additionally, I would like to have a "estimated value" field for each card, where it contains the tcgplayer.com or pricecharting.com estimated price. Each card should also contain a link to the tcgplayer and pricecharting.com profile for the card. The price should be captured and stored once, and the player can press a button to "update" the price info. The price can be updated for individual cards, or the user can update specific categories of cards. 

## Searching cards

The user should be able to select from a dropdown the different categories and subcategories of cards. Additionally, they should be able to search for specific characters, e.g., "charizard", but make this something the user has to type and not via a drop down.

The user should also be able to search for cards of a specific value range (e.g., $5 to $10; $10 or greater, $5 or less) etc. The user can enter the dollar amount (don't use fixed dollar amounts). Also, let the user search for special cards, e.g., holographic cards and whatnot. 

When the user selects a cateogory or search term, the different cards should show up as a list, with a small thumbnail of the card's picture. The user can select that card and then it should show the card's "profile" page, which contains all the info we captured from the card as well as the tcgplayer/pricecharting estimated value. 

## Maintaining cards

The user should be able to remove cards from their collection, by simply pressing a "trashcan" icon on the card's profile page. 

The user can also press "edit" on the card's profile page, as depicted by a pencil, to manually change the cards data (in case something is wrong).

## AI

AI should be used to determine the card's information as well as find the appropriate info from tcgplayer and pricecharting. Initially, we will support Copilot, OpenAI and Anthropic subscription accounts, but also we will allow a BYOK option where the user can put their API key. We will have anthropic, copilot, and openai for now, but we will extend this later. 

## Data Model and Screens/Pages

Pages: WHen opening the application, the user should see a list of their "collections". A collection is just something the user names their cards, and a collection can contain any number of cards across all the categories. From that page, the user can open an existing collection, or create a new one. When opening an existing collection, take the user to a new page/screen that shows all the categories for which the user has cards in that collection. For example, if there are no "Energy" cards, "Energy" should not be available from the drop down. There should also be a search bar that can let the user search for characters. 

THere should also be a Misc category in case we come across a card for which we do not know the type. For example, new card types can be created before we, the app maintainers, get it added to the app. In that case, the user can look at the misc cards, and then on the card's profile page, they should be able to change the category of that card (fixed categories via drop down). Same with sub-types under a category. 

When opening an existing collection, the user can then scan cards to add to the collection, search the whole collection as described above, update the pricing for various cards/categories in the collection, and delete the collection.
