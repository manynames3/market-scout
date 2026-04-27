# ADR 0001: Web Hosting And Cost Strategy

## Status

Accepted

## Date

2026-04-27

## Context

Market Scout started as a CLI/local Python app and later gained a macOS wrapper. The next
step is to make it available from anywhere, including mobile browsers, while keeping
monthly operating cost at free or as close to free as possible.

The current app architecture assumes:

- a long-lived Python process
- local filesystem caching
- startup-time dataset downloads
- in-memory search structures

That is acceptable for a local desktop app, but it is a poor fit for ultra-low-cost web
hosting.

## Important Observation

The original README described the Redfin source files as roughly `20 MB` each. That is no
longer operationally true enough to use as a hosting assumption.

On 2026-04-27, the current Redfin source objects referenced by this app were observed at
approximately:

- city dataset: `991,534,794` bytes
- zip dataset: `1,528,604,844` bytes
- county dataset: `237,720,173` bytes

This materially changes the hosting decision:

- serverless cold starts become unattractive
- free-tier VMs become risky
- startup-time fetch-and-parse is the wrong online architecture

## Constraints

- Target hosting cost: `free` or `< $1/month`
- Mobile-friendly public access
- Minimal operational burden
- Avoid surprise bills
- Preserve the current product behavior as much as possible

## Options Considered

### 1. AWS free tier / small EC2 / Lightsail

Pros:

- straightforward mental model
- easy to keep Flask mostly unchanged

Cons:

- does not meet the free or under-`$1/month` target for a reliable always-on service
- still couples runtime availability to large source downloads and local caching
- more operational overhead than necessary for this project stage

Decision:

- Rejected for the current cost target

### 2. Serverless Python API on Vercel / similar platforms

Pros:

- easy public deployment
- good frontend DX

Cons:

- current app shape is not serverless-friendly
- ephemeral filesystems conflict with local dataset caching
- large startup work makes cold-start behavior and memory usage unattractive

Decision:

- Rejected for the current architecture

### 3. Always-on container on Railway / Fly / Render

Pros:

- simpler than AWS
- closer to the current Flask app

Cons:

- still not realistically free or under `1 USD/month`
- the runtime would still be carrying work that should be moved to build time

Decision:

- Rejected for the current cost target

### 4. Static frontend + scheduled data build + optional object storage

Pros:

- realistic path to `0 USD/month` or near-zero cost
- operationally simple
- works well on phones
- avoids runtime dependency on multi-GB source downloads
- easy to reason about and cap spending

Cons:

- requires refactoring the app architecture
- some current server-side behavior moves into build-time preprocessing or browser logic

Decision:

- Accepted

## Decision

Market Scout's online version will be built as:

- Cloudflare Pages for the frontend
- GitHub Actions for scheduled monthly data refreshes while the repository remains public
- optional Cloudflare R2 for generated artifacts if the published dataset becomes too large

The web version should not depend on downloading and parsing the raw Redfin source files
at request time or startup time.

Instead, a scheduled build job should:

1. download the Redfin source data
2. keep only the latest rows and required columns
3. normalize keys for city/zip/county lookups
4. produce compact output for browser use, such as JSON shards or SQLite-derived exports
5. publish those outputs to the frontend build

## Consequences

### Positive

- hosting cost can realistically stay at `0 USD/month` for early usage
- no always-on Python server is required
- fewer moving parts in production
- better fit for public/mobile usage

### Negative

- the current Flask runtime is no longer the production architecture for the web version
- some existing code will be replaced by build scripts and static frontend logic
- autocomplete and lookup behavior need to be reimplemented against prebuilt data

## Follow-up Work

- create a build pipeline that emits compact browser-friendly data
- redesign the frontend as a static site
- move search/lookup to client-side data access or tiny edge functions only if needed
- keep the current Flask app available as a local/dev tool during migration
