# Web Migration Plan

## Goal

Make Market Scout available on the web, including phones, while keeping recurring hosting
cost at free or near-zero.

## Non-Goal

Do not lift-and-shift the current Flask app onto a paid always-on host.

## Target Architecture

- static frontend deployed to Cloudflare Pages
- scheduled monthly data build via GitHub Actions
- optional Cloudflare R2 storage for generated artifacts
- no always-on application server in the first hosted version

## Why

The current local app assumes it can:

- download large Redfin source files on startup
- cache those files on disk
- keep parsed data in memory

That is the wrong cost model for a free web deployment.

## Implementation Phases

### Phase 1: Data pipeline extraction

- move Redfin download and parsing logic into a standalone build script
- emit compact artifacts containing only the latest row per market, required metrics,
  normalized search keys, and compact search metadata

Current implementation:

- build command: `python3 scripts/build_static_data.py`
- raw cache directory: `.cache/redfin/`
- output directory: `generated/web-data/`
- output manifests: `manifest.json`, `cities.json`, `zips.json`, `counties.json`
- search index: `search-index.json`
- market shards: `markets/<type>/<state>.json`

Suggested outputs:

- `cities.json`
- `zips.json`
- `counties.json`
- `search-index.json`

If artifact size is still too large, shard by state or market type.

### Phase 2: Static frontend

- replace Flask-served HTML with a static frontend
- load generated artifacts over HTTP
- run search/autocomplete in the browser
- render results without SSE

Current implementation:

- committed static frontend: `static/index.html`
- site assembly command: `python3 scripts/build_static_site.py`
- deployable output directory: `dist/`
- runtime data path inside built site: `dist/data/`
- suggestion source: `data/search-index.json`
- market lookups: lazy-loaded shard files under `data/markets/<type>/<state>.json`
- optional browser-side Census income fetch for `hh_income`

### Phase 3: Scheduled refresh

- add a GitHub Actions workflow that rebuilds data monthly
- publish the generated artifacts with the site deploy

Current implementation:

- workflow file: `.github/workflows/static-site.yml`
- schedule: first day of the month at `12:00 UTC`
- build output artifact: `dist/`
- deploy mode: Cloudflare Pages direct upload with Wrangler
- deploy condition: only when Cloudflare secrets and project variable are configured
- fallback Git integration build command: `bash scripts/build_cloudflare_pages.sh`

### Phase 4: Optional edge enhancements

Only add runtime compute if clearly needed for UX or performance.

Possible later additions:

- Cloudflare Worker for search proxying
- R2 for large static artifacts
- edge caching for derived responses

## Cost Model

Initial target:

- Cloudflare Pages: `0 USD/month`
- GitHub Actions on a public repo: `0 USD/month`
- Cloudflare R2: `0 USD/month` if usage stays inside free tier

## Repo Strategy During Migration

- keep the current Flask app working for local use
- build the web version incrementally beside it
- use docs/ADR notes to explain why the architecture is diverging
