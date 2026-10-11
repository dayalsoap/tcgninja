# TCG Ninja domain and storage handoff

Baseline version: `2026-10-10.1`. Scope: English physical cards, USD, one record per physical copy. These are extensible suggestions, not a claim of exhaustive historical/current coverage. No species dropdown or finite species allowlist exists.

## Verification performed before implementation

Read the root `AGENTS.md` and `implementation_plan.md`. Queried all seven TCGdex endpoints below directly before coding. The [official TCGdex endpoint documentation](https://tcgdex.dev/rest/other-fields) also uses compact aliases `trainertypes`/`energytypes`; the hyphenated URLs below returned HTTP success and the recorded data.

| Axis | Source and observed values | Application treatment |
| --- | --- | --- |
| Categories | [categories](https://api.tcgdex.net/v2/en/categories): Energy, Pokemon, Trainer | Display/store recognized categories as Pokémon, Trainer, Energy. Add application fallback Misc. |
| Printed types | [types](https://api.tcgdex.net/v2/en/types): Colorless, Darkness, Dragon, Fairy, Fighting, Fire, Grass, Lightning, Metal, Psychic, Water | Retain all eleven including Fairy. Never infer these from species or video-game typing. |
| Stages | [stages](https://api.tcgdex.net/v2/en/stages): BREAK, Baby, Basic, LEVEL-UP, MEGA, RESTORED, Stage1, Stage2, V-UNION, VMAX, VSTAR | Display Stage 1/Stage 2/Level-Up/Restored. Preserve unknown raw labels. Catalog stages include mechanics; keep `stage` distinct from `mechanics`. |
| Suffixes | [suffixes](https://api.tcgdex.net/v2/en/suffixes): EX, GX, Legend, Prime, SP, TAG TEAM-GX, V, ex | Mechanic suggestions preserve the distinction between `EX` and `ex`; supplement with observed stage mechanics and ACE SPEC. |
| Trainer subtypes | [trainer-types](https://api.tcgdex.net/v2/en/trainer-types): Item, Rocket's Secret Machine, Stadium, Supporter, Technical Machine, Tool | Tool maps to Pokémon Tool for UI/filtering. Keep historical printed labels available; not all catalog labels are mutually exclusive rules categories. |
| Energy subtypes | [energy-types](https://api.tcgdex.net/v2/en/energy-types): Normal, Special | Normal means Basic Energy, **not** Normal finish. Filter accepts Normal/Basic/Basic Energy as aliases; Special/Special Energy as aliases. |
| Rarities | [rarities](https://api.tcgdex.net/v2/en/rarities) | Physical-card suggestions below; provider list mixes physical and Pocket data. Rarity is never inferred from foil finish. |

Observed rarity endpoint, verbatim labels: ACE SPEC Rare; Amazing Rare; Black White Rare; Classic Collection; Common; Crown; Double rare; Four Diamond; Full Art Trainer; Futuristic Rare; Holo Rare; Holo Rare V; Holo Rare VMAX; Holo Rare VSTAR; Hyper rare; Illustration rare; LEGEND; Mega Hyper Rare; None; One Diamond; One Shiny; One Star; Pikachu Rare; Promo; Radiant Rare; Rare; Rare Holo; Rare Holo LV.X; Rare PRIME; Secret Rare; Shiny Ultra Rare; Shiny rare; Shiny rare V; Shiny rare VMAX; Special illustration rare; Three Diamond; Three Star; Two Diamond; Two Shiny; Two Star; Ultra Rare; Uncommon.

The initial physical-card dropdown omits the Pocket-style Crown, Diamond, Star and Shiny-count tiers and omits Futuristic Rare/Pikachu Rare pending physical English coverage verification. These values are still accepted and round-trip unchanged. List membership does not enforce legality, authenticity, or language coverage. `Normal`, `Holofoil`, `Reverse Holofoil`, `Other`, and `Unknown` are finish suggestions; specific foil patterns, stamps, textures, and new treatments remain saveable as text. There is currently no dedicated artwork-treatment field; preserve such details in notes until a coordinated schema extension.

Official cross-checks from the plan:

- [Pokémon Tool errata](https://www.pokemon.com/us/pokemon-news/2023-pokemon-tcg-standard-format-rotation-and-pokemon-tool-errata) confirms four Trainer classes including Pokémon Tool. Older Item classifications must not override current Tool classification.
- [Temporal Forces announcement](https://press.pokemon.com/en-CA/MEDIA-ALERT-Pokemon-Trading-Card-Game-Scarlet-VioletTemporal-Forces-Ex) confirms ACE SPEC can occur on Trainer and Special Energy cards. ACE SPEC is a mechanic tag, not a mutually exclusive Trainer subtype.
- The plan's [rulebook](https://www.pokemon.com/static-assets/content-assets/cms2/pdf/trading-card-game/rulebook/par_rulebook_en.pdf) and [glossary](https://www.pokemon.com/us/play-pokemon/about/pokemon-tcg-glossary) were opened again but returned no useful extractable content in this run. Do not count them as newly verified evidence; current Tool errata takes precedence over older glossary wording.

## Representative coverage

These TCGdex card records were fetched during implementation; fixtures retain the relevant facts, not changing market prices or copyrighted artwork.

| Case | Real example | Verified behavior |
| --- | --- | --- |
| Same name, distinct printing | [Charizard Base Set](https://api.tcgdex.net/v2/en/cards/base1-4), [Charizard Base Set 2](https://api.tcgdex.net/v2/en/cards/base4-4) | Separate catalog IDs/sets; no name-based uniqueness. |
| Pokémon Tool | [Rescue Board](https://api.tcgdex.net/v2/en/cards/sv05-159) | Trainer + Tool, independently of rarity/finish. |
| ACE SPEC Tool | [Hero's Cape](https://api.tcgdex.net/v2/en/cards/sv05-152) | Tool subtype plus ACE SPEC characteristic. |
| ACE SPEC Item | [Prime Catcher](https://api.tcgdex.net/v2/en/cards/sv05-157) | Item subtype plus ACE SPEC characteristic. |
| ACE SPEC Energy | [Neo Upper Energy](https://api.tcgdex.net/v2/en/cards/sv05-162) | Energy + Special + ACE SPEC; not Trainer. |
| Historical Basic Energy | [Fairy Energy](https://api.tcgdex.net/v2/en/cards/xy1-140) | Fairy retained; provider energyType Normal means Basic. |
| Multiple species | [Pikachu & Zekrom GX](https://api.tcgdex.net/v2/en/cards/sm9-33) | Two species/Pokédex IDs, one printed name, TAG TEAM-GX. |
| Stage and variant independence | [Furret](https://api.tcgdex.net/v2/en/cards/swsh3-136) | Stage1, Uncommon, normal and reverse variants. |
| Future/unmapped data | Synthetic fixture | Unknown category, subtype, species, rarity, finish and mechanic round-trip without loss. |

Species are free text independently of printed name; multiple names can be comma-separated. Search matches the stored names, not an invented comprehensive alias database. Catalog/Pokédex adapters should supply known species/aliases and preserve printed form/owner names. No network taxonomy refresh or catalog import is implemented in this package. Future updates should re-fetch axes and representative cards, review upstream changes, update suggestions/version/tests, and never rewrite saved unknown labels automatically. TCGdex is a data source, not authority for game-rule classification; image redistribution/licensing is not approved by this research.

## Exact public API and integration notes

All public classes are in `com.tcgninja.app.data`.

- `CardRecord`: requested string fields/defaults, nullable `Long` cents/timestamp fields, UUID ID and creation timestamp. One owned physical copy; duplicate names/catalog IDs are allowed. `number`, `hp`, types, species, and mechanics remain text. Unknown taxonomy values are not rejected.
- Additive `CardRecord.priceQuotes: List<PriceQuote> = emptyList()` supports raw **and** multiple graded estimates. `PriceQuote(cents: Long, source: String = "", basis: String = "Ungraded", updatedAt: Long? = null, url: String = "")`. All amounts are USD minor units; at most 100 quotes/card. Existing `priceCents`, `priceSource`, `priceBasis`, `priceUpdatedAt` remain the explicitly selected display/filter quote, not a sum. The UI/pricing agent must populate/select quotes; storage does not fetch or fabricate them.
- `CollectionRecord(id: String = UUID, name: String = "", createdAt: Long = now)`.
- `DraftRecord(id: String = UUID, collectionId: String, card: CardRecord, committedCardId: String? = null, position: Int = 0, batchId: String = "")`.
- `AppRepository(context: Context)` exposes `collections`, `cards`, `drafts` as `StateFlow<List<...>>`. Initialization is asynchronous; call `suspend awaitReady()` before interpreting initial empty lists or to surface initialization failure. Instances for the same DB path share a store/flows within one process.
- Suspend methods: `createCollection(name): String`, `deleteCollection(id): Unit`, `saveCard(card): Unit`, `deleteCard(id): Unit`, `saveDraft(draft): Unit`, `deleteDraft(id): Unit`, `commitDraft(id): String`, `addDrafts(collectionId, cards): Unit`, `exportBackup(): String`, `importBackup(json): Unit`.
- `CardJson.toJson(CardRecord): JSONObject` / `fromJson(JSONObject): CardRecord` use explicit allowed fields. Omitted fields receive model defaults for recognition integrations; backup imports separately require stable identities/timestamps. Unknown **field values** survive, but unknown JSON **keys** are intentionally not persisted.
- `Taxonomy.categories/types/finishes/stages/mechanics/rarities: List<String>`; `subtypes(category): List<String>`; `categoryLabel(raw): String`; `version: String`. Categories are exactly `Pokémon`, `Trainer`, `Energy`, `Misc`.
- `CardFilter(query/category/subtype/finish/mechanic: String = "", minCents/maxCents: Long? = null).matches(card): Boolean`. Filters combine with AND, bounds are inclusive, unpriced cards are excluded if either bound is set, negative/reversed bounds throw. Text search is case/accent insensitive over name/species/set/number. Mechanic tokens are comma/semicolon/pipe separated and case-sensitive to distinguish EX/ex. This predicate **does not scope collections**; caller must first filter by `collectionId`.
- `Money.parseCents(String): Long?`: blank → null; optional `$`, correctly grouped commas, up to two decimals; rejects negative/malformed/overflow values with `IllegalArgumentException`. Exact decimal arithmetic. `Money.format(Long?): String`: US currency or `Not priced`.

Known `Pokemon`/`Pokémon` input is persisted canonically as `Pokémon`. CardJson itself preserves raw values for lossless integration round-trips. `CardFilter` accepts either spelling. Unknown categories stay verbatim in storage; **browsing UI should group with `Taxonomy.categoryLabel(card.category)`**, including raw unknown categories under Misc. Editor choices must include the current unknown value as well as suggestions to avoid silently replacing it. Bulk/category/subtype grouping should use the same normalized category logic, not raw equality. Source Trainer `Tool` and Energy `Normal` are accepted aliases in subtype filtering; raw subtype spelling is retained.

## Persistence and lifecycle decisions

Architecture deviation: use native `SQLiteOpenHelper`, not proposed Room. This uses the permitted fallback and avoids adding a Room/KSP compiler-plugin matrix while AGP 9/built-in Kotlin integration is being assembled. SQLite schema version is 1 with three tables, collection foreign keys/cascading record deletion, collection indexes, and a unique committed-card ID in drafts. JSON payloads preserve open-ended metadata; typed relational keys enforce ownership. Future schema versions must add explicit nondestructive migrations; upgrades never drop user tables automatically.

All IO/mutations run on `Dispatchers.IO` under a shared mutex. Mutations and imports transact before publishing flow snapshots; separate flows are not a single atomic combined emission. The application is single-process. Queries currently materialize all records; no FTS/indexed search or large-library performance target has been established. Rows are limited to 256 KiB of JSON to avoid oversized CursorWindow entries.

Collection names must be nonblank. Drafts may be incomplete; Commit requires a nonblank card name, existing collection, and valid prices. Draft edits cannot move collections or forge/change committed IDs. A commit creates a fresh owned ID even when two drafts share the same provisional recognition ID. Repeated/concurrent Commit returns that same owned ID. A deleted committed card leaves its draft's commit ID as a tombstone: retry returns the old ID without recreating it. Deleting that draft explicitly removes that tombstone. Arbitrary manual-card deletions do not retain tombstones. Save is otherwise an upsert; callers must avoid submitting stale asynchronous whole-record updates. Draft sorting is batch ID/position/ID. New batch IDs begin with a zero-padded creation time so newer scans sort later; older UUID-only batch IDs have no time order, so callers still identify a new batch by its card IDs. Review navigation lists only batches that still contain an uncommitted draft; fully committed batches remain stored as tombstones.

Changing name, set, collector number, catalog ID or finish clears external product links, catalog ID (unless only the finish changed), selected estimate and quote list — except values supplied in the same update (a newly pasted link, a catalog match, a typed or freshly retrieved estimate), which are kept. Changing only the grade keeps the product link and re-selects the saved quote for the new grade, or clears the selected estimate if that column was not retrieved. Changing only the PriceCharting link clears a previously retrieved PriceCharting estimate but keeps a manual one. Condition does not select a product or price column and does not invalidate anything. Notes/images edits preserve prices. The actual grade/condition fields are not erased; only obsolete estimates are invalidated. If a provider response is pending while identity is edited, the caller must compare identity before applying it. This fixed whole-record API does not implement optimistic versioned writes.

Image lifetime: deleting a card/collection/draft deletes database records only. Physical files are retained intentionally because in-progress camera/editor images may not yet have repository references. A future cleanup API needs capture/editor ownership or leases, must inspect **both cards and drafts**, and may delete only explicitly owned, unreferenced files. No broad directory deletion is performed. This may retain orphan images until app data is cleared. Export includes only images referenced by current owned cards/drafts.

## Portable backup v1

JSON envelope: `format = tcgninja-backup`, `version = 1`, `currency = USD`, `collections`, `cards`, `drafts`, and `images` object. Image fields become `image:<SHA-256>` references; `images[hash]` contains base64 original image bytes. Repeated front/back/draft references deduplicate by content hash. No absolute source paths are exported. Provider settings, API keys, preferences, Keystore data and database files are never read into the archive. User-written card text and image metadata remain user content and are not automatically redacted.

Owned image sources are app-private files/cache or readable granted `content:` URIs. External arbitrary filesystem paths are rejected. Valid HTTPS catalog-image URLs are preserved as remote references, with no image attachment and no download; such catalog art is not available offline solely because of a backup. URLs with embedded credentials are rejected. Missing/unreadable owned images fail export clearly instead of claiming a complete backup. JPEG/PNG/WebP signatures and decodable dimensions are required. Original owned image bytes are preserved, including any existing EXIF metadata.

Bounds: 8 MiB/image, 32 MiB total unique image bytes, 48 MiB UTF-8 JSON, 20,000 combined collection/card/draft records, 256 KiB/stored record, 16,384 pixels per dimension and 64 megapixels/image. Limits reject oversized backups, never silently omit cards/images. This is a bounded in-memory JSON backup suitable for the initial personal app, not a streaming archive for an arbitrarily large photo collection. UI should disclose these limits and catch validation/IO errors.

Import validates version, USD, IDs, references, duplicate records, hashes, sizes and dimensions before database mutation. Restored images go to `filesDir/restored-images/<sha256>.img`; backup strings never become filesystem paths. New files are staged and fsynced before the SQLite transaction. New files are removed if the transaction fails; existing image files are never deleted on rollback. A crash between image creation and DB commit can leave an unused file, but cannot commit metadata referring to an unwritten image. Recovery/cleanup of such orphans is deferred with the general retention policy.

Merge semantics: existing IDs win, preserving local corrections. New records merge without duplicating existing IDs. Repeated import is idempotent; ambiguous conflicting commit histories are rejected atomically. Current deleted-commit tombstones prevent older backups resurrecting those cards; manual deletions without a retained draft can be restored by an older backup. Import is **not** destructive restore-to-snapshot or sync and does not remap IDs. To recover an old value over a changed existing record requires a future explicit conflict-resolution UI or manual edit.

## Dependencies and tests

Runtime: Android built-in SQLite and `org.json`; `kotlinx-coroutines-core`/Android (the current lifecycle dependency graph provides coroutines). No Room/KSP, Kotlin serialization plugin, external JSON runtime, image-network library, or backend is required by this package. Android API 26+ matches the main build and supplies `java.util.Base64`.

Tests: `junit:junit:4.13.2`, `org.json:json:20240303`, `org.robolectric:robolectric:4.16.1`; Robolectric artifact availability was checked directly on Maven Central. `AppRepositoryTest` uses `RuntimeEnvironment`, SDK 34 and legacy graphics without an app manifest, so it does not require Compose or a device. The main build already declares these dependencies and Android unit-test resources. Tests use `runBlocking`, not coroutines-test APIs. SDK 28 caused a native Robolectric runtime abort on this macOS host; test runtime is pinned to 34 independently of the application's minimum SDK 26.

Files: `app/src/test/java/com/tcgninja/app/data/DomainTest.kt` and `AppRepositoryTest.kt`. They cover money boundaries, composed search, taxonomy aliases, unknown labels, multiple species/printings, JSON round trips, identity invalidation, SQLite reopen/CRUD/isolation, concurrent idempotent commits, blank-name review, merge repeatability, malformed/late-failing import rollback, tombstone protection and portable image bytes/secret exclusion.

Main-agent command after app integration: `./gradlew :app:testDebugUnitTest --tests 'com.tcgninja.app.data.*'`. Main owns the Android build/install workflow; this package is also verified separately using a temporary JVM/Robolectric harness that compiles only the owned data sources/tests, without building the app.

Verification result, 2026-10-10: isolated Kotlin compilation and **17 tests passed** (7 domain tests, 10 SQLite/Robolectric repository tests), no failures or skips, using JDK 17 / Kotlin 2.3.20 / Robolectric 4.16.1 / SDK 34. No whole-app build was run by this package owner. Full app integration, device UI testing, low-memory behavior and future database migrations remain main-agent/release checks.
