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
- deploy the finished site to Cloudflare Pages only through manual dispatch with `deploy=true`

## Consequences

- Scheduled builds regenerate data artifacts; publishing those artifacts currently requires a manual dispatch.
- The build pipeline can update the live site even when no application code changes are made.
- Cloudflare deployment credentials must be managed correctly for the optional direct-upload
  path to work.
