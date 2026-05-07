# ADR 0003: Use Client-Side Search and Shard Loading in the Web App

## Status

Accepted

## Context

The production site is static and does not have a dedicated backend API for market search or
lookup. At the same time, the dataset is too large to load as one monolithic file on every page
visit.

## Decision

The web app will:

- load a lightweight search index first
- resolve market selection in the browser
- lazily fetch only the relevant per-state shard for the chosen market

## Consequences

- The production deployment can stay backend-free.
- Initial page load stays smaller than a single-file dataset approach.
- The frontend becomes responsible for search behavior, sorting, and shard lookup logic.
