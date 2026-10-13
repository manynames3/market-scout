# ADR 0006: Native Android With Local Research Storage

## Status

Accepted

## Context

Phone users need research to survive rotation, connection loss, and process restarts. The
public data already exists as static JSON on Cloudflare Pages, and the product's operating
cost target does not justify a new account service or database server for mobile research.

## Decision

- Keep a native Java/Android SDK client in the separate `android/` Gradle project.
- Share the existing manifest, search index, and shard contract with the web client.
- Store lists, favorites, notes, filters, drafts, and results in private device preferences.
- Cache downloaded JSON atomically on disk, scoped to the source snapshot and capped at 32 MiB.
- Use frozen scan inputs, generation tokens, and cancelable requests to reject obsolete results.
- Verify business logic in JVM tests and Android persistence/layout in device tests.
- Build signed APK/AAB artifacts from owner-supplied keys; never store keys in the repository.

## Consequences

- The mobile client adds no paid service or production library dependency.
- Saved research remains usable offline; new searches require a cached index and market shard.
- Research is local to a device, with no cloud synchronization. Clearing app data removes it.
- Mobile and web releases are independent, but changes to the JSON contract need compatibility checks.
- A paused scan keeps completed results and requires a deliberate retry after activity recreation.
- Public distribution still requires the owner's signing key and store setup.
