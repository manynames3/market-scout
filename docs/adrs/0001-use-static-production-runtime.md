# ADR 0001: Use a Static Production Runtime

## Status

Accepted

## Context

The original product was a local Python/Flask app that assumed a long-lived process, local
cache files, and in-memory search state. That model works locally but creates unnecessary cost
and operational complexity for a public web deployment.

## Decision

The public production deployment will be a static site served from Cloudflare Pages instead of
an always-on Python backend.

## Consequences

- Production hosting cost stays low and predictable.
- The public site becomes easier to deploy and access from mobile devices.
- Data preparation must move to build time because the production runtime no longer has a
  Python process available for heavy parsing.
