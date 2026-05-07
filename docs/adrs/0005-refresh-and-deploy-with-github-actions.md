# ADR 0005: Refresh and Deploy With GitHub Actions

## Status

Accepted

## Context

Redfin data changes over time, and a static deployment only stays useful if it can be rebuilt
regularly. Relying only on manual rebuilds or Cloudflare Git deployments would make data refresh
too easy to forget.

## Decision

Use GitHub Actions to:

- rebuild artifacts on code changes
- support manual refreshes
- run a scheduled monthly rebuild
- optionally deploy the finished site to Cloudflare Pages

## Consequences

- Data refresh becomes part of the documented delivery workflow instead of a manual step.
- The build pipeline can update the live site even when no application code changes are made.
- Cloudflare deployment credentials must be managed correctly for the optional direct-upload
  path to work.
