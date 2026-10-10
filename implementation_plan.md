# TCG Ninja implementation plan

Status: First personal Android build implemented; validation and remaining integration limitations are recorded in section 11.
Updated: 2026-10-10 (America/Los_Angeles).
Project directory: `/Users/jaidayal/tcgninja`.
Requirements source: `AGENTS.md` in this directory.

## 1. How to use this plan

Build an Android application for organizing physical Pokémon TCG cards into named collections. The owner authorized implementation after this plan was drafted and subsequently resumed the interrupted build. The accepted answers and implementation status below supersede the original pre-implementation gates; unresolved external integrations are not represented as complete.

- **Required** means stated in the project brief.
- **Proposed** means a recommendation awaiting agreement.
- **Open** means the responsible agent must resolve the named dependency before implementing that part.
- Phase ordering describes development dependencies, not an agreed reduction in launch scope.
- Preserve `AGENTS.md`. The verified corrections below qualify its taxonomy examples; they do not replace its product requirements.
- The project began with the brief and an empty Git history. Implementation is now present. Parent-directory instructions describe a separate shopping-list application; do not import that application's product features or styling into this project.

## 2. Product decisions to resolve

Owner answers are recorded below. Remaining questions are limited to decisions that affect implementation; do not reopen settled choices.

| ID | Decision | Current status | Blocks |
| --- | --- | --- | --- |
| D01 | Full requested launch scope, or smaller initial release? | Confirmed: full feature set, delivered in phases. API-key-first exception is recorded in D05. | Milestone definition only; scope settled. |
| D02 | Device-only collections or account-backed cloud backup/sync? | Confirmed: device-only first release; no collection account/cloud sync. Owner confirmed local backup export/import. | Settled; bounded backup v1 implemented. |
| D03 | Android implementation stack? | Implemented: native Kotlin, Jetpack Compose and CameraX; native transactional SQLite instead of proposed Room. See docs/taxonomy.md for the compiler-dependency decision. | Settled for this build. |
| D04 | Card languages, currency, and duplicate-copy behavior? | Confirmed: English cards, USD, separate records for each physical copy. | Settled. |
| D05 | Which Copilot product, and is API-key-first delivery acceptable? | Confirmed: Microsoft Copilot; API-key-first delivery acceptable. Do not substitute GitHub Copilot. Microsoft consumer API/vision support remains unverified. | Microsoft integration feasibility; subscription support can follow later. |
| D06 | Existing pricing API access, paid-data budget, and fallback preference? | Owner has neither API access nor a paid PriceCharting subscription; asks to investigate HTML extraction. PriceCharting HTML is technically promising; final data-access choice remains open. | Automated price integration. |
| D07 | Personal installation or public Play release; desired visual style? | Confirmed: personal installation for now, no particular style. Recommend standard Material styling with system light/dark theme. | Play publishing deferred; no custom art required for initial implementation. |
| D08 | Meaning of the displayed estimated value? | Owner confirmed raw and graded prices. Build uses the selected PriceCharting grade column or a clearly labeled manual USD estimate; stores all retrieved grade columns separately. | Other provider estimates remain deferred until supported access. |
| D09 | Supported card eras and products? | English is settled. Open: older sets/promos, oversized cards, graded slabs, and other nonstandard products. | Coverage promises and recognition evaluation. |
| D10 | Scan workflow details? | Open: binder layouts, maximum batch size, draft retention/resume, front/back requirements, keeping original photos. | Capture/review UX and image storage. |
| D11 | Device and quality targets? | Open: oldest Android version, representative phones, collection sizes, acceptable accuracy/latency/cost. | Performance tests and release gates. |

Resolve remaining D02/D04/D06 questions, then D08–D11 as focused follow-ups. Do not infer agreement from unanswered questions. Device-only storage does not mean offline AI: recognition still sends selected images to the configured provider.

## 3. Confirmed feature scope and traceability

| Requirement | Behavior | Work packages |
| --- | --- | --- |
| R01 Collections | Home lists named collections; create, open, and delete a collection. | W03, W09 |
| R02 Single-card capture | Camera capture, AI-assisted identification, review/edit, explicit Commit. Additional side capture if needed. | W04, W05 |
| R03 Binder-page capture | Identify separate cards, navigate with previous/next arrows, edit and commit each independently. | W06 |
| R04 Card metadata | Name, category/subtype, printed type, HP where applicable, identifiers, special characteristics, image, and relevant catalog information. | W01, W02, W04 |
| R05 Pricing | Capture estimated value once; store provider links; explicitly refresh one card or selected categories. | W07 |
| R06 Search | Category/subcategory dropdowns, typed character search, user-entered value bounds, special-card/finish filters. | W03, W08 |
| R07 Results and profile | Thumbnail list; select a card to view/edit its information and pricing. Trash icon removes an owned card. | W03, W08 |
| R08 Available categories | Collection browsing offers categories present in that collection, with a way to view all cards. | W03, W08 |
| R09 Unknown data | Misc category; user can reclassify using controlled category/subtype choices. Catalog/taxonomy can grow. | W01, W02, W03 |
| R10 AI choices | OpenAI, Anthropic, and Microsoft Copilot where supported. API-key-first delivery approved; subscription integration follows supported availability. Extensible provider design. | W00, W04 |

An unavailable provider is an unresolved requirement, not a completed feature. Any launch deferral requires an explicit decision recorded under D01/D05/D06.

## 4. Taxonomy and catalog findings

The brief is a useful starting point but not a complete historical taxonomy. Before application code, W01 must verify coverage for the agreed card scope and produce a versioned taxonomy with representative real-card fixtures.

Verified corrections and modeling implications:

- Keep Pokémon, Trainer, and Energy as the main card categories; Misc is the application's fallback for unknown/unclassified cards.
- Trainer subtypes include Item, Supporter, Stadium, and **Pokémon Tool**. Tools became a separate subtype, including errata for older cards. Preserve printed classification separately when useful. [S1]
- **ACE SPEC is a separate characteristic**, not an exclusive Trainer subtype: official examples include Trainer and Special Energy cards. [S2]
- Preserve all eleven printed TCG Pokémon types, including historical Fairy. Do not confuse these with available Basic Energy cards; historical Fairy Energy must also be represented when older cards are supported. [S3, S4]
- Use the type printed on the card or established by its catalog entry. Do not derive TCG type from a Pokémon species' video-game typing; the brief's fixed Poison-to-Psychic shortcut must not become a classification rule.
- Keep evolution/stage labels and mechanic tags separate. Basic/Stage 1/Stage 2 and EX/GX/V/VMAX/VSTAR/ex are examples, not a complete hard-coded universe. W01 must verify additional historic/current labels for the chosen scope, including distinctions between similarly named mechanics.
- Keep rarity, finish, and artwork treatment as separate attributes. A card can have a rarity plus a holo/reverse-holo variant. Avoid using a single `isSpecial` boolean as the entire model.
- “Characters” generally means Pokémon species such as Charizard. Distinguish species identity from the exact printed card name, forms, owner prefixes, and mechanics. Allow multiple species on a printing and none for Trainer/Energy cards.

Proposed catalog approach: use an updateable, source-attributed card catalog and species/alias dataset behind adapters. Source selection remains open; W01 must evaluate language/set coverage, images, update cadence, stable identifiers, and permitted use. No catalog supplier or exhaustive species count is assumed here. Unknown cards must remain saveable without a catalog match.

## 5. Integration feasibility findings

These are documentation findings as of the date above, not successful Android integration tests.

| Integration | Evidence | Required next step |
| --- | --- | --- |
| OpenAI subscription | Official documentation describes Sign in with ChatGPT and eligible plan-backed inference in participating applications. [S5, S6] | Prove supported Android registration/callback flow, eligibility, image input, model access, refresh/logout, and billing behavior. Do not equate identity-only login with inference authorization. |
| Anthropic subscription | Anthropic directs developers building applications for others to API-key authentication or supported cloud providers; subscription usage must not be assumed available. [S7] | Plan an API-key adapter; treat subscription integration as gated on an explicitly supported path and D05. |
| Microsoft Copilot | The official Microsoft 365 Copilot APIs reviewed require enterprise licensing; they do not establish a usable consumer Copilot subscription API for this app. [S17] | Verify a supported consumer integration and image input before scheduling implementation. Do not substitute Microsoft Foundry, Azure OpenAI, or GitHub Copilot without an explicit product decision. API-key-first OpenAI/Anthropic delivery can proceed. |
| TCGplayer | Developer documentation says new API access is not currently being granted. [S9] | Determine whether the owner has access or an authorized data provider can meet requirements. |
| PriceCharting | API access requires a paid subscription. A direct unauthenticated fetch of a sample card page returned a structured price table. [S10, S14] | Evaluate personal-use HTML extraction as requested; establish reliable product/variant matching and permitted access before adopting it. |

AI may help extract card details and suggest catalog/provider matches. A stored provider price must come from retrieved, attributable source data, never an invented model estimate. Verify external product links before treating them as matches. If a link is only a search link, label it as such rather than a matched product profile.

### HTML price extraction investigation

The owner asked whether fetching a page and parsing its HTML can avoid an API key. Technically, yes for the PriceCharting example checked: the unauthenticated response contained `table#price_data` and price cells such as `td#used_price`. Validate the surrounding labels rather than treating these generic IDs as a universal card-grade mapping. The same page includes multiple grades, price changes, and sales/listing amounts; selecting the first dollar amount would be incorrect. [S14]

TCGplayer's terms explicitly require permission for scraping. PriceCharting's terms summary mentions personal use and restricts third-party app redistribution; it does not explicitly establish automated scraping permission. Its current robots file does not disallow product pages, but robots rules are not a data-use license. [S15, S16, S18]

Proposed W00 experiment: validate a small representative sample of PriceCharting product/variant pages and clarify the access conditions. If selected, W07 should use deterministic HTML parsing, source attribution, saved USD snapshots, conservative request rates, and user-triggered refresh. Treat missing/changed markup, blocking, or ambiguous identity as unavailable data; preserve old values. No browser challenge bypass is part of the plan. Keep paid API access, a permitted alternate source, and manual values as options pending D06; none is silently selected.

## 6. Proposed architecture and contracts

Recommended in response to D03: a native Android app using Kotlin, Compose UI, Room for local structured storage, and CameraX for capture. These components are documented Android options [S11–S13]; versions and minimum SDK will be selected during W02. Use standard Material components and system light/dark styling as the design starting point.

Separate UI, domain behavior, persistence, catalog, AI recognition, and pricing adapters. Start with feature/package boundaries; introduce Gradle modules only where useful. Collections remain local with no account/cloud-sync backend. Prefer direct provider calls using the owner's configured credentials for this personal app; if a later supported authentication flow requires a backend, surface that change rather than adding one implicitly. Store credentials separately using Android Keystore-backed protection; exclude secrets from logs and any backup exports.

Proposed boundaries to finalize jointly before parallel development:

| Boundary | Responsibilities and outcomes |
| --- | --- |
| CollectionRepository | Create/list/open/delete collections; persist owned records; execute scoped searches. |
| CardCatalog | Search printings/species aliases, return candidates and source IDs, support versioned updates. |
| RecognitionProvider | Accept images and hints; return structured observations, candidates, field uncertainty, and typed errors. |
| ProviderAuth | Declare supported auth modes; connect/disconnect; store credential references; never expose secrets to UI/domain records. |
| ScanDraftRepository | Persist batch/item identity, crop/order, edits, review status, and commit result. |
| PricingProvider | Resolve exact product/variant and retrieve attributed quotes with amount, currency, basis, timestamp, URL, and errors. |
| ImageStore | Manage originals/crops/thumbnails, access boundaries, retention, and cleanup. |

Shared error vocabulary should distinguish offline, unauthenticated, permission denied, quota/rate limit, unsupported capability, ambiguous match, not found, invalid response, and unavailable source. Do not automatically switch providers or send images to another provider without an agreed user setting.

### Conceptual data model

This is intentionally not a committed database schema; D02/D04/D08 must settle ownership and valuation details first.

| Entity | Suggested data and invariants |
| --- | --- |
| Collection | Stable local ID, name, timestamps; no account fields or cloud ownership for the first release. |
| CardPrinting | Exact name, set identifiers, collector number as text, language, category, subtypes, types, species references, applicable HP/stage/rules, rarity, catalog provenance. Preserve numbers such as `001/102` and alphanumeric promo codes. |
| OwnedCard | Collection membership, optional printing reference, confirmed/editable fields, finish/variant, optional condition/grade, image references. Individual-copy versus quantity behavior is D04. |
| Species | Stable source identity, names/aliases, forms/relationships as supported by the selected catalog. |
| TaxonomyEntry | Stable code, display label, parent/axis, version, active/legacy state, source. Preserve unmapped raw labels. |
| ScanBatch / ScanItem | Captured image, per-card crop/order, raw observations, edits, status, committed owned-record ID. |
| ExternalProductMatch | Provider, product/variant ID, URL, matching evidence, confirmation state. |
| PriceSnapshot | Provider match, amount in currency minor units, currency, raw/graded/condition basis, retrieval time and source URL. History retention is open. |

Use nullable/not-applicable fields instead of fabricated zero values; Trainer cards do not need HP. User corrections belong to the owned record and must survive later recognition/catalog/price refreshes. Editing card identity or variant must invalidate incompatible external matches and estimates. Whether one physical card can belong to multiple collections remains part of D04.

### Scan behavior

Capture → identify/crop → recognize → review/edit → explicit Commit → collection.

- Persist collection changes only on Commit. Retrying or double-tapping the same draft's Commit must not create another copy; intentionally scanning a second physical copy is a separate action governed by D04.
- Front images identify the printing; optional back/additional-angle captures may help review. Do not promise that a flat photo or generic card back establishes foil finish, exact condition, or grade.
- Ambiguous variants stay unresolved until the user confirms. Offer recapture/edit/skip without losing other reviewed items.
- Binder detection must not assume exactly nine occupied pockets. Preserve stable per-card navigation and commit state; allow correction of missed/incorrect crops under the agreed UX.
- Save a card even if pricing fails. The profile shows a missing/failed estimate honestly and supports later retry.
- Proposed: manual card creation as an offline/error fallback. Confirm launch inclusion under D01.

### Search and pricing behavior

- Category dropdowns reflect the collection's contents. The edit form must still offer all maintained categories/subtypes so a Misc card can be moved into a previously empty category.
- Search is typed text, not a species dropdown. Proposed: case-insensitive matching against printed names and species aliases, composed with other filters using AND.
- Proposed: inclusive optional lower/upper price bounds. Validate malformed/reversed bounds; unknown price is not zero. D08 must specify the source/basis used for filtering and behavior when estimates disagree. Never compare different currencies as if equivalent.
- Initial price retrieval happens once when a card is added/matched. Opening a profile or searching must not silently refresh it.
- Explicit bulk refresh is scoped to the chosen collection/categories. Deduplicate identical provider lookups, show progress and per-item failures, support cancellation, and retain the previous valid estimate on failure.
- Display source, currency, valuation basis, and last-updated time. Do not portray a general market estimate as a guaranteed sale price or an individual condition appraisal.

## 7. Agent work packages

Each package must return its changes, decisions, validation evidence, unresolved issues, and the next dependency it unblocks. Suggested paths are ownership boundaries to establish in W02, not existing files.

### W00 — Product and integration feasibility

**Owner:** planning/integration agent. **Dependencies:** owner answers D01–D07; investigate independent findings while awaiting answers.

Deliver a decision log; provider/auth/capability matrix; pricing access decision including the HTML investigation; and phased milestones covering the full requested scope. Respect the local/personal architecture and API-key-first decision. For each required external path, record official sources and prove the essential flow with a narrowly scoped experiment once implementation is authorized. Obtain sample responses without committing credentials.

**Acceptance:** each launch integration is demonstrated or explicitly deferred by the owner; image-input and price-data access are distinguished from login success. No guessed API endpoints, borrowed app credentials, or unapproved fallback providers.

### W01 — Taxonomy and catalog

**Owner:** domain/data agent. **Dependencies:** D04/D09 for final coverage; research may begin earlier.

Own taxonomy fixtures and catalog contracts/data. Verify categories, evolution/mechanic labels, historical cases, finish/rarity axes, and species/form/name matching. Compare candidate catalog sources and record an update strategy and source permissions.

**Acceptance:** a coverage matrix with real examples for each supported class; tests for Tool, ACE SPEC Energy, historical Fairy, unknown subtype, multi-species printing, and distinct printings sharing a name. Unknown/unmapped data round-trips without loss. This package satisfies the brief's pre-code taxonomy requirement.

### W02 — Android foundation and shared contracts

**Owner:** Android foundation agent. **Dependencies:** W00 architecture decisions, W01 taxonomy baseline, D02/D03/D04.

Own build configuration, navigation shell, shared domain interfaces, persistence schema/migrations, image/credential storage contracts, and test setup. Choose package/application ID with owner input if needed. Establish fake adapters and fixture-based development so UI work does not require paid API calls.

**Acceptance:** reproducible documented build, shell starts on an agreed emulator/device, data persists through restart, baseline migration strategy and repository contract tests pass. Versions are pinned; no secrets in source/build output. Backend work, if selected, receives a separate owned subpackage before parallel implementation.

### W03 — Collections and card management

**Owner:** collection UI agent. **Dependencies:** W02; D04 copy/membership semantics.

Own collection home/list/detail and owned-card profile/edit flows. Implement create/open/delete, thumbnails, explicit category changes, edit/pencil and trash actions, and empty states. Proposed deletion UX: confirmation or undo; select one before implementation.

**Acceptance:** required CRUD works after restart; collection operations cannot affect unrelated collections; the last card's removal updates available categories; Misc can be moved to an otherwise empty category; manual corrections remain intact.

### W04 — AI providers and recognition

**Owner:** AI integration agent. **Dependencies:** W00, W01, W02; D05.

Own recognition/auth adapters and settings. Implement provider capability discovery, agreed subscription/BYOK modes, structured extraction, catalog reconciliation, uncertainty handling, cancellation, bounded retries, and redacted diagnostics. Select concrete models from demonstrated image/structured-output capability, cost, and availability at implementation time.

**Acceptance:** test each agreed provider on the same labeled fixture set; record exact-printing accuracy, ambiguity/false-match rate, latency, and cost against D11 targets. Malformed output, revoked auth, quota exhaustion, and unsupported vision fail recoverably. No fabricated price or unverified product match enters a record.

### W05 — Single-card capture and review

**Owner:** camera/review agent. **Dependencies:** W02, W03 commit interface, W04 recognition contract (fake usable first); D10.

Own camera permissions, capture/crop/rotation, additional-side capture, review/edit/Commit, draft state, and lifecycle recovery. Clarify how incomplete metadata is presented and the minimum commit requirements; unknown classification must be allowed.

**Acceptance:** representative photos work on the target phone; denied permission, poor image, no network, cancellation, rotation, and process restart follow agreed behavior. Double Commit cannot duplicate one draft. Recognition failure permits correction/retry without losing the capture.

### W06 — Binder-page scanning

**Owner:** batch capture agent. **Dependencies:** W05 shared review workflow, D10 batch/layout choices.

Own multi-card detection, crop correction, ordering, previous/next navigation, per-card progress, skip/retry, and independent commits. Share the single-card review implementation; do not fork its data logic.

**Acceptance:** agreed binder fixtures cover empty pockets, rotation, glare, partial crops, and repeated printings. Edits survive navigation; one failing item does not block other commits; navigating back to a committed item cannot recommit accidentally. Resume/discard behavior matches D10.

### W07 — Pricing and external product links

**Owner:** pricing agent. **Dependencies:** W00 source access, W01 printing identifiers, W02 contracts; D06/D08.

Own provider matching, quotes, link validation, cached USD snapshots, and explicit individual/category refresh. Implement the W00-selected API or permitted HTML adapter; keep retrieval interchangeable. Match set/collector number/language/variant before claiming an exact product. Map each source's price fields explicitly; distinguish graded and ungraded data. If HTML extraction is selected, add parser fixtures that detect layout changes and distinguish estimates from price deltas/listing prices.

**Acceptance:** fixtures cover duplicate names across sets, multiple finishes, missing prices, ambiguous products, rate limits, revoked access, partial bulk failure, cancellation, and stale results after a card edit. Previous valid prices survive failed updates. No automatic profile-open refresh. Show both requested provider links only where valid; unavailable matches stay visibly unresolved.

### W08 — Search and filtering

**Owner:** search agent. **Dependencies:** W02/W03, W01 taxonomy, W07 price contract; D08/D11.

Own query/index implementation and filter UI. Implement collection-scoped text, category/subtype, special mechanics/finishes, editable min/max value filters, thumbnail results, and profile navigation.

**Acceptance:** filters compose correctly; exact boundaries, unknown values, accents/aliases, empty categories, and user edits are covered; search never includes another collection's records. Validate performance at the agreed collection size on the agreed device.

### W09 — Integration, privacy, and release verification

**Owner:** integration/QA agent. **Dependencies:** all launch packages and resolved launch decisions.

Own end-to-end checks, accessibility, device QA, recovery checks, personal APK installation, and documentation. Review image/credential retention, backups, provider disclosures, and external transfer consent against the chosen architecture. Public Play distribution is deferred; if later selected, create a separate release work package and recheck platform/data-source requirements then.

**Acceptance:** R01–R10 are demonstrated or explicitly deferred; no unreviewed gate is labeled complete; agreed builds/tests pass. Check connected devices after successful Android build/test runs and use the repository's installation workflow once established; report unavailable/unauthorized devices. No distribution or store submission is implied by this planning document.

## 8. Sequencing and parallel-agent coordination

1. Resolve product decisions and run W00/W01 research in parallel.
2. Finalize taxonomy, architecture, shared interfaces, and fixture formats; complete W02.
3. Run W03, W04, and W07 in parallel against agreed contracts and fakes.
4. Develop W05 once the commit and recognition contracts are stable; W08 can run alongside it.
5. Build W06 on the shared review workflow; finish real-provider integration and W09.

Design prototypes and recognition experiments may overlap after their own inputs are known. Agents must not independently settle conflicting data/auth contracts. The foundation agent owns shared schema/interfaces; package owners request coordinated changes. Use separate branches/worktrees when multiple agents edit concurrently. Do not create agent chats or dispatch work merely from this plan.

Agent handoff template:

> Read the root AGENTS.md and implementation_plan.md. Implement package Wxx only after its named decisions and dependencies are resolved. Own the assigned feature paths, use the agreed shared contracts, and preserve unrelated edits. Report any requirement conflict before choosing a product behavior. Deliver a change summary, relevant tests and results, manual verification where needed, source/credential limitations, and explicit handoff notes. Update package status with evidence; never call an unavailable external integration complete.

## 9. Release verification scenarios

- Create two collections; add/edit/delete cards in one without affecting the other; restart and verify persistence.
- Scan Pokémon, Trainer, Energy, and an unresolved card; review/edit and commit each explicitly.
- Verify a character with several printings resolves by set/number/variant rather than name alone.
- Scan a binder page; move in both directions; correct/skip/retry cards; commit each exactly once.
- Correct Misc to a known category and verify filters/profile update immediately.
- Search by typed name, subtype, finish, and arbitrary value bounds; include exact bounds and unpriced cards.
- Retrieve an initial attributed estimate and valid external links; reopen without fetching again.
- Refresh one card and a selected category; simulate partial failure without losing saved estimates or edits.
- Validate each launch provider/auth mode, disconnect/revocation, missing key, offline behavior, and image handling.
- Verify collection/card deletion behavior, image cleanup, accessibility, large text, camera lifecycle, and interrupted operations.

Numeric accuracy, speed, supported devices, and cost gates remain D11 decisions; do not invent pass thresholds.

## 10. Sources

Research checked 2026-10-10. Recheck access and platform requirements before implementation/release. The brief's product-guide URL was opened, but did not return useful extractable text; the primary sources below support the findings above.

- **S1:** [Pokémon Tool classification and errata](https://www.pokemon.com/us/pokemon-news/2023-pokemon-tcg-standard-format-rotation-and-pokemon-tool-errata).
- **S2:** [Official Temporal Forces announcement: ACE SPEC Trainer and Special Energy cards](https://press.pokemon.com/en-CA/MEDIA-ALERT-Pokemon-Trading-Card-Game-Scarlet-VioletTemporal-Forces-Ex).
- **S3:** [Pokémon TCG rulebook: card and type basics](https://www.pokemon.com/static-assets/content-assets/cms2/pdf/trading-card-game/rulebook/par_rulebook_en.pdf).
- **S4:** [Official Pokémon TCG glossary: historical Basic Energy and terminology](https://www.pokemon.com/us/play-pokemon/about/pokemon-tcg-glossary). Cross-check older glossary descriptions against later errata.
- **S5:** [OpenAI: Sign in with ChatGPT quickstart](https://developers.openai.com/siwc/quickstart).
- **S6:** [OpenAI: Sign in with ChatGPT application integration example](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt).
- **S7:** [Anthropic: subscription authentication and developer guidance](https://support.claude.com/en/articles/13189465-log-in-to-your-claude-account).
- **S8 (not selected):** [GitHub Copilot SDK authentication](https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate). Initial research only; the owner selected Microsoft Copilot.
- **S9:** [TCGplayer API access prerequisites](https://docs.tcgplayer.com/docs/getting-started).
- **S10:** [PriceCharting API documentation](https://www.pricecharting.com/api-documentation).
- **S11:** [Android: Jetpack Compose](https://developer.android.com/compose).
- **S12:** [Android: Room persistence](https://developer.android.com/training/data-storage/room).
- **S13:** [Android: CameraX](https://developer.android.com/media/camera/camerax).
- **S14:** [PriceCharting sample card page](https://www.pricecharting.com/game/pokemon-base-set/charizard-4). Also checked using one direct HTTP fetch; HTML price-table presence verified, production parser not implemented.
- **S15:** [TCGplayer terms: scraping requires permission](https://help.tcgplayer.com/hc/en-us/articles/205004918-Terms-of-Service).
- **S16:** [PriceCharting terms: price-data use](https://www.pricecharting.com/page/terms-of-service).
- **S17:** [Microsoft 365 Copilot API overview and licensing](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/copilot-apis-overview).
- **S18:** [PriceCharting robots directives](https://www.pricecharting.com/robots.txt).

## 11. Implemented build and handoff (2026-10-10)

The owner requested the full feature set in phases and approved API-key-first delivery. This first installable personal build implements collection management, separate physical-card records, camera/photo input, AI single-card/binder drafts, previous/next/commit review, manual crop correction, typed search and composed filters, profile editing/deletion, catalog lookup/reconciliation, raw/graded price snapshots, explicit category refresh, and portable local backup/merge.

| Package | Delivered evidence | Remaining limits |
| --- | --- | --- |
| W00/W01 | Recorded answers; official/provider taxonomy research in docs/taxonomy.md; extensible unknown-label handling. | No exhaustive current-card coverage guarantee. Microsoft consumer integration unavailable. |
| W02/W03 | Gradle Android project, Compose UI, SQLite transactions, separate records, tested CRUD and restart isolation. | SQLite replaces Room; in-memory search has no large-library benchmark. |
| W04 | OpenAI Responses and Anthropic Messages BYOK, encrypted key storage, strict extraction schemas and parser/request tests. | No paid live requests or device Keystore test; subscription/Microsoft Copilot sign-in not implemented. |
| W05/W06 | CameraX, imported photos, EXIF normalization, persistent drafts, idempotent commit, AI crop and manual correction, original binder photo retention. | Hardware camera/vision accuracy remains unverified; 24-card recognition limit. |
| W07 | Actual public PriceCharting product-page refresh passed for six grade columns; local parser tests; exact-product picker and manual link fallback. | Product search received HTTP 403; no successful live search or TCGplayer price retrieval. Confirmed URL entry works independently of search. |
| W08 | Typed text, category/subtype/finish/mechanic and inclusive arbitrary USD filters; Compose collection-isolation test. | No quantified large-library latency target. |
| W09 | Debug APK, 65 passing local tests, lint with zero errors; two separate live public-data smoke tests passed. | No connected Android phone; real-device acceptance and paid AI calls remain open. |

Build artifact: `app/build/outputs/apk/debug/app-debug.apk`. Rebuild with JDK 17 and SDK 36 using `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Install with `./installtophone.sh` when an authorized phone is connected. Minimum Android API 26; application ID `com.tcgninja.app`; development version `0.1.0`.

Implementation choices made under build authorization: Material system light/dark theme; confirmation for destructive UI actions; manual-entry fallback; on-demand English TCGdex catalog; each physical copy belongs to one collection; unknown finish remains Unknown; grade values exactly follow source labels; source/variant changes invalidate obsolete prices. No collector-name dropdown or finite species whitelist is used.

Backup v1 limits: 8 MiB per image, 32 MiB total unique embedded photos, 48 MiB JSON, 20,000 records including drafts. Exports exclude provider credentials and preserve remote catalog-image references without downloading them. Existing IDs win on merge. Unreferenced private images are retained to avoid deleting in-flight captures. See README.md and docs/taxonomy.md before extending these contracts.

Next verification requires a connected phone and a user-configured API key: exercise camera permission denial, rotation, back/angle photos, glare and binder sheets, key persistence, real-image recognition quality, and export/import via the Android document picker. Do not report these as already tested. Public-store distribution and Microsoft/subscription authentication remain separate follow-up work.
