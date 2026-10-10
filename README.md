# TCG Ninja

A personal Android Pokémon TCG collection manager. English cards, USD estimates, and one record per physical copy. Collections are stored on the phone; no app account or cloud backend is required.

> **Vibe-coded project:** TCG Ninja is entirely vibe-coded with AI assistance. It is a personal project, and the code, architecture, and implementation should be treated as experimental and reviewed before use with valuable collection data.

## Build and install

Requires JDK 17, Android SDK platform/build tools 36, and an Android 8.0+ device (API 26). Gradle 9.3.1 is provided by the wrapper. Set `sdk.dir` in your untracked `local.properties` or configure `ANDROID_HOME` for your SDK.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./installtophone.sh
```

The JDK path above is for this Mac's Homebrew installation; use your installed JDK path on another machine. The APK is `app/build/outputs/apk/debug/app-debug.apk`. Installation requires a connected, authorized USB-debugging device. The app ID is `com.tcgninja.app`.

## Use

1. Create a named collection from Home.
2. In Settings, select OpenAI or Anthropic, enter your own API key, and save. Model IDs are configurable. API charges are separate from chat subscriptions. No key is needed for manual entry, collection management, or catalog search.
3. Choose Add cards → scan a card, scan a binder page, search the English TCGdex catalog, or enter a card manually. Photos are sent to your chosen AI provider only when you request recognition.
4. Review each draft; correct the name, set, number, type, finish, and grade. Previous/next arrows navigate drafts. Crop tools can recover an incorrect card crop or crop from the additional/original binder image. Creating another draft from the same photo lets you recover a missed card. Only **Commit to collection** adds a physical copy.
5. Use **Find a matching PriceCharting product** during review or **Find / change price match** on a saved profile. Confirm the exact printing, edition, and finish. You can also paste a product URL in Edit. Initial retrieval occurs at commit when a confirmed link is present, or when a saved card is linked. Individual and category refresh are explicit actions. Failed refreshes retain previous estimates.
6. Filter a collection by typed name, category, subtype, finish, mechanic, or arbitrary inclusive USD bounds. Each profile includes a selected saved estimate and any retrieved raw/graded columns. Source search links are labeled separately from matched product links.
7. Export/import from Settings for a portable local backup. Existing records win during merge so an older backup does not overwrite local corrections. API keys never enter backups.

## Implemented boundaries and limitations

- Native Kotlin/Jetpack Compose and CameraX. Storage uses transactional Android SQLite rather than the originally proposed Room; see `docs/taxonomy.md` for the schema and decision.
- OpenAI Responses and Anthropic Messages image adapters use schema-validated results. A finish, condition, or grade is not automatically claimed from an image. Review is required. Recognition requires the user's API key; paid live model calls are not exercised by the automated suite.
- Microsoft Copilot consumer image access and subscription sign-in are **not implemented**. No supported consumer Copilot route was verified. There is no hidden GitHub Copilot/Azure replacement. API-key-first delivery was approved.
- PriceCharting HTML retrieval is an experimental personal-use adapter. It validates USD, identity and table labels and fails if ambiguous or if source markup changes. It cannot guarantee access or estimate coverage. TCGplayer scraping is not implemented; product/reference links remain available. A manual estimate is supported.
- The TCGdex catalog is fetched on demand, not bundled exhaustively offline. Unknown/new card labels survive storage and can be reclassified from Misc. Catalog data availability does not establish exhaustive current card coverage.
- Binder recognition accepts up to 24 detected cards per request; missed or ambiguous cards need manual correction. Images are resized to at most 2200 pixels on their longest edge. Physical-camera accuracy, glare, and binder layouts need validation on the user's device.
- Backup v1 is a bounded JSON archive: 8 MiB per image, 32 MiB of unique embedded photos, 48 MiB total JSON, and 20,000 records including drafts. Limits produce an error rather than silently omitting cards. Catalog artwork URLs remain remote references. Exports are not encrypted by this app.
- Images not referenced by records may remain in private storage after deletion. Automatic orphan cleanup is deferred to avoid deleting an unsaved capture. Android automatic cloud backup is disabled.
- No benchmark or release claim is made for very large collections. Filtering currently runs over local records in memory. Public Play distribution is not configured.

## Verification

Tests cover exact money conversion and inclusive filters, taxonomy aliases and unknown labels, separate physical copies, idempotent commits, SQLite restart/isolation, atomic backup merge/rollback, image portability, malformed AI output, catalog mapping, price/source validation, and Compose collection/search/review flows.

The regular unit suite uses local fixtures. Opt-in public-network integration checks are isolated in `PublicIntegrationSmokeTest`. Run `./gradlew :app:testDebugUnitTest --tests '*PublicIntegrationSmokeTest' -PlivePublicSmoke=true` to enable the three public reads. They do not make paid AI requests.

Initial verification: debug APK built successfully; 65 local tests passed and two opt-in public tests skipped in the normal suite. The two public tests also passed separately in the integration harness. Android lint completed with zero errors and dependency/style warnings. A successful APK build is not a physical-camera or live paid-recognition test.

No real phone was connected during the initial build. Hardware camera behavior, actual API-key storage/sign-in, and live recognition need device verification. The in-app Privacy & sources screen explains local storage and explicit external requests.

## Project references

- `AGENTS.md`: original project brief.
- `implementation_plan.md`: agent work packages, accepted choices, and current handoff status.
- `docs/taxonomy.md`: researched taxonomy, persistence contracts, and backup format.
- `app/src/main/java/com/tcgninja/app/integration/`: provider adapters and deterministic parsers.

TCG Ninja is an independent personal project. Card names and artwork belong to their respective owners; catalog metadata/art references come from TCGdex and estimates are attributed to PriceCharting.
