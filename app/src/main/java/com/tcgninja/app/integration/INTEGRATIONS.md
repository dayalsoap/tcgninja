# Integration package handoff

Implemented only integration source/test paths. No application-wide builds, credential-file access,
or paid provider calls were performed by this package's implementation work.

## Public interfaces

- `ProviderSettings(context)`: `provider`, `openAiModel`, `anthropicModel`, `getKey(provider)`,
  `setKey(provider, key)`. Providers are exactly `OpenAI` and `Anthropic`. Empty key disconnects.
  Defaults are `gpt-4.1-mini` and `claude-sonnet-4-6`, both configurable.
- `RecognitionService(settings).recognize(frontPath, backPath = null, binder = false)`:
  suspend function returning uncommitted `List<CardRecord>`. It does not retrieve prices or
  automatically assign catalog identities. Unknown finish/condition require review.
- `CatalogService().search(query)` and `.details(id)`: suspend functions returning English
  TCGdex briefs/full details. Search returns at most 50; refine the search for other results.
  Dex identities are preserved when the source does not supply canonical species names.
- `PriceService().search(card)`: one bounded request returning up to 20 distinct
  `PriceCandidate(title, url)` values from source result rows; it does not fetch candidate pages.
- `PriceService().refresh(card)`: one request returning a copy with available USD `priceQuotes`
  and a primary quote selected by `card.grade`. Failure leaves the original unchanged.
- Top-level `pricechartingSearchUrl(card)` and `tcgplayerSearchUrl(card)` return browser search
  links only. No TCGplayer scraper exists.

`IntegrationException` exposes a safe message and `kind`. Exceptions never include provider
response bodies. Cancellation propagates to OkHttp; there are no automatic billed retries,
redirect following, fallback providers, or HTTP logging interceptors.

## Important caller responsibilities

Only save `pricechartingUrl` as a price match after the user confirms its exact printing,
language, finish, and edition. The current `CardRecord` contract has no separate confirmation
flag: a populated product URL is the caller's assertion of this confirmation. Catalog-supplied
links or search candidates must still go through confirmation. Identity/variant checks catch
conflicts, but an unqualified page title does not independently prove normal versus holo.
Unknown finish is rejected; explicit reverse/edition mismatches are rejected. URLs must be HTTPS
on pricecharting.com's English `/game/pokemon-.../...` path with no query/fragment/credentials.

Supported price bases are exactly `Ungraded`, `Grade 7`, `Grade 8`, `Grade 9`, `Grade 9.5`,
and `PSA 10`. Generic grade labels are never relabeled as PSA grades. Prices come only from
the estimate element under the corresponding header, checked against the full guide when present.
Responsive mobile duplicate headers are recognized. Deltas, advertisements, listings, and
non-USD prices are not quotes. Refresh preserves other sources' quotes. The caller owns
one-second batch pacing, saving results, and invalidating matches on identity edits.

API keys are AES-256-GCM ciphertext in Android's `noBackupFilesDir`, with a nonexportable
Android Keystore key and provider-bound authenticated data. SharedPreferences holds only
model/provider choices. Custom backup must continue to export an explicit allowlist of
card data/photos, never the entire app directory or key files. After reinstall/restore,
the user must re-enter keys. Settings should never log or display an existing key as ordinary text.

Recognition accepts app-private files in `filesDir` or `cacheDir`; import external URIs first.
Photos are decoded with sampling, EXIF orientation applied, resized to 2000 pixels on the long
edge, and re-encoded to JPEG without EXIF/GPS. Originals are unchanged. Bounds use this oriented
image's normalized coordinates. Binder batches are capped at 24 cards; larger pages require
multiple photos. Crop files are in `filesDir/recognition-crops`; drafts/backup/deletion own their
subsequent lifecycle. Without an explicit back image, binder `backImagePath` retains the original
page so backup can remap it. Notes contain no filesystem path. If a back image is supplied, that
path is retained instead. Invalid/unusable bounds fall back to the original photograph.

Recognition notes request readable battle text within 1500 characters; they are not a promise
of exhaustive transcription. Catalog notes preserve available battle metadata within bounded
text limits and explicitly mark global truncation. Model output cannot set prices, external
URLs, image paths, condition, or detected finish. The UI must disclose image transfer/API billing
before recognizing. Microsoft Copilot and consumer subscription inference are unsupported.

## Validation, 2026-10-10

Isolated Kotlin 2.3.20/JDK 17 compilation against Android 36 stubs, the shared Records contract,
and the configured integration dependencies passed. No UI or application build was run here.
45 deterministic JUnit tests passed, including exact grade mapping, malformed amounts,
currency/identity/variant/canonical mismatch, the actual mobile table structure, blocked search,
candidate limits/deduplication, recognition schema/refusal/truncation, and safe request contracts.

Two opt-in public smoke tests also passed (47 total):

- `https://api.tcgdex.net/v2/en/cards` search returned 50 Charizard briefs; details at
  `/v2/en/cards/base1-4` returned Charizard, Base Set, `4/102`, with no invented price.
- `https://www.pricecharting.com/game/pokemon-base-set/charizard-4` returned verified USD
  quotes for all six bases. Last smoke retrieval timestamp: `1791672929815` (Unix milliseconds).

`PublicIntegrationSmokeTest` skips unless JVM property `tcgninja.livePublicSmoke=true` is set.
The tests assert attribution/structure, not volatile dollar amounts. The public search endpoint
returned HTTP 403/browser challenge in this environment; search has fixture coverage but successful
live search is unverified. It reports unavailable and offers no challenge bypass.

Paid vision calls, identification accuracy, Android Keystore persistence, photo orientation/crop
quality on devices, and device backup/restore are not live-verified by these tests.

## API/source references

- [OpenAI Responses vision](https://developers.openai.com/api/docs/guides/images-vision)
- [OpenAI structured JSON](https://developers.openai.com/api/docs/guides/structured-outputs)
- [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- [Anthropic image messages](https://platform.claude.com/docs/en/build-with-claude/vision)
- [Anthropic output_config.format](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)
- [Anthropic models](https://platform.claude.com/docs/en/models/overview)
- [TCGdex details](https://tcgdex.dev/rest/card) and [pagination](https://tcgdex.dev/rest/filtering-sorting-pagination)
- [PriceCharting terms](https://www.pricecharting.com/page/terms-of-service): personal data use
  does not establish permission for public redistribution or a general automated-access license.
  This adapter is limited to the requested personal, user-triggered lookups. No bulk scraping.
