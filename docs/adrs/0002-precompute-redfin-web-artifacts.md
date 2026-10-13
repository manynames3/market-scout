# ADR 0002: Precompute Redfin Data Into Compact Web Artifacts

## Status

Accepted

## Context

Redfin publishes large gzip-compressed market-tracker files. Shipping those files directly to
the browser or parsing them at request time would be slow, expensive, and fragile for a
static-hosted product.

## Decision

The build pipeline will normalize Redfin data ahead of time and emit compact artifacts:

- a search index
- dataset manifests
- per-state market shards
- source freshness metadata

Only the fields needed by the UI are kept.

## Consequences

- The browser can work against lightweight JSON instead of raw source datasets.
- Production latency improves because expensive parsing happens before deployment.
- The build pipeline becomes a critical part of the system and must stay in sync with the UI’s
  expected data shape.
