# ADR 0004: Keep the Flask App for Local and Development

## Status

Accepted

## Context

The public production path changed from Flask to a static deployment, but the local app still
provides a useful development, validation, and fallback runtime. Removing it would make the
transition harder and eliminate a path that is still useful for local testing.

## Decision

Keep the Flask app as a supported local/development runtime while the static site remains the
production deployment model.

## Consequences

- The repository supports both a legacy local runtime and a modern static production path.
- Shared parsing logic in `market_data.py` reduces duplication between the two modes.
- The project carries some extra maintenance cost because two execution paths must remain
  understandable.
