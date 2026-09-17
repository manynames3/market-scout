# Android Client

## Purpose

The Android app is a native companion client for Market Scout. It provides the core mobile
workflow: add a city, ZIP code, or county; paste a bulk list; run a market scan; sort the
results; export a CSV; and open Zillow or Redfin for deeper research.

## Separation Boundary

The Android client is a standalone Gradle project under `android/`. It does not import the
Python application or the web UI. The web clients and Android client share one stable boundary:
the generated static data published at:

```text
https://market-scout-anl.pages.dev/data/manifest.json
https://market-scout-anl.pages.dev/data/search-index.json
https://market-scout-anl.pages.dev/data/markets/<type>/<state>.json
```

This keeps deployments independent. A web UI change does not require an Android release, and
an Android UI release does not require changing the data build or Flask runtime.

## Runtime Flow

1. `MainActivity` loads the manifest and search index on a background executor.
2. `MarketSearchIndex` resolves exact or bulk-pasted city, ZIP, and county names locally.
3. The user selects markets and starts an analysis.
4. `MarketDataRepository` loads only the required state/type shard and caches it in memory.
5. The app maps each JSON record into a typed `MarketResult` and optionally enriches it with
   Census ACS household-income data.
6. The activity renders mobile result cards, applies local sorting, and launches external
   Zillow/Redfin links through Android intents.

Network failures are shown per market instead of crashing the scan. The app remains useful
without a login because the published data is public and the Census enrichment is optional.

## Build And Install

Requirements:

- Android SDK with API 36 platform tools
- JDK 17

From the repository root:

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Constraints

- The app depends on the availability of the public Cloudflare Pages data artifacts.
- Redfin freshness is inherited from the latest generated source snapshot; it is displayed in
  the app rather than fetched live for every search.
- Household income comes from the Census ACS 5-year API and may be unavailable for some small
  geographies or during an API outage.
- ZIP-code markets are smaller geographic areas, so metrics such as months of supply and price
  drops can be sparse or less stable than city- or county-level data.
- There is no offline database yet. Shards are cached only for the current process.

## Release Boundary

The `codex/android-app` branch isolates the first native client implementation from the web
deployment branch. Once the mobile build and smoke test are accepted, it can be merged into
`master`; subsequent Android releases can then be versioned independently through the
`android/app` Gradle configuration.
