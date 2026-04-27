# Market Scout

Real estate market analytics tool powered by Redfin public data. Search any US city, zip code, or county and get key market metrics instantly — no API key, no scraping, no browser automation required.

## Current State

This repository currently ships a local-first Python/Flask app and a macOS app wrapper.
It works well on a laptop because it can cache Redfin source data locally and keep an
always-on in-memory search index.

## Web Deployment Direction

The online/mobile version is being redesigned around a static frontend plus scheduled data
builds instead of an always-on Flask server.

- Frontend: Cloudflare Pages
- Scheduled data build: GitHub Actions
- Optional artifact storage: Cloudflare R2
- Cost target: free or as close to $0/month as possible

Why this changed:

- The current app architecture downloads Redfin source files locally and builds large
  in-memory caches on startup.
- That is a poor fit for serverless and ultra-cheap always-on hosting.
- A static site with prebuilt compact data files is materially cheaper and simpler to run.

See [docs/adr/0001-web-hosting-and-cost-strategy.md](docs/adr/0001-web-hosting-and-cost-strategy.md)
for the decision record and
[docs/web-migration-plan.md](docs/web-migration-plan.md) for the implementation plan.

## Phase 1 Build

Phase 1 extracts the Redfin download/parse path into a standalone static-data build:

```bash
python3 scripts/build_static_data.py
```

By default this writes generated web artifacts under `generated/web-data/` and caches raw
Redfin files under `.cache/redfin/`.

## Phase 2 Static Frontend

Phase 2 adds a static browser app that reads the generated artifacts directly:

```bash
python3 scripts/build_static_site.py
```

That assembles a deployable site under `dist/` with:

- committed frontend assets from `static/`
- generated data copied to `dist/data/`

To preview locally:

```bash
python3 -m http.server 8000 --directory dist
```

## Phase 3 Scheduled Build And Deploy

Phase 3 adds a GitHub Actions workflow for unattended refreshes:

- workflow file: `.github/workflows/static-site.yml`
- monthly rebuild schedule: first day of the month at `12:00 UTC`
- build artifact: `dist/`
- optional Cloudflare Pages deploy via Wrangler

Cloudflare deployment is enabled only after you configure:

- secret: `CLOUDFLARE_API_TOKEN`
- secret: `CLOUDFLARE_ACCOUNT_ID`
- variable: `CLOUDFLARE_PAGES_PROJECT`

See [docs/cloudflare-pages-setup.md](docs/cloudflare-pages-setup.md) for the setup details.

### Why `exit 0` appears in the Pages docs

Cloudflare recommends `exit 0` for static Pages projects when the output directory already
exists and Pages does not need to build anything itself. In this repository, that only
applies when deployment is handled by GitHub Actions direct upload and Cloudflare Pages is
just receiving a finished `dist/` bundle.

It does **not** work for a normal Git-integrated Pages build in this repo, because `dist/`
is gitignored and is generated at build time. If Cloudflare runs `exit 0`, no `dist/`
directory gets created, and the deploy fails with `Output directory "dist" not found`.

### Cloudflare Pages project settings for this repo

If you are using Cloudflare's Git integration during project setup, use:

- Build command: `bash scripts/build_cloudflare_pages.sh`
- Build output directory: `dist`

If you are using GitHub Actions direct upload as the primary deployment path, `exit 0` is
still valid in principle, but only if Cloudflare is not expected to build the site from
source.

## What It Does

Market Scout downloads Redfin's public market tracker CSVs (city, zip code, and county level), parses them locally, and surfaces the metrics that matter most for evaluating a real estate market. It also pulls median household income from the US Census Bureau API at no cost.

## Metrics Returned

| Metric | What It Tells You |
|--------|-------------------|
| Median Sale Price | Current market price level |
| Months of Supply | < 3 = seller's market, > 6 = buyer's market |
| Pending-to-Available Ratio (PAR) | Demand intensity — higher = more competitive |
| Median Days on Market | How fast homes are selling |
| Sale-to-List % | Whether homes sell above or below asking |
| Sold Above List % | Share of homes with bidding wars |
| Price Drop % | Share of listings with price reductions |
| Off Market in 2 Weeks % | How quickly inventory is absorbed |
| Median Household Income | From US Census ACS 5-year estimates |

## Setup

### Requirements

- Python 3.8+
- pip

### Install



Or on macOS, run the included setup script:



### Run



Then open [http://localhost:5001](http://localhost:5001) in your browser.

On macOS you can also double-click **Market Scout.app** or run:



## How It Works

In the current local Flask app, startup downloads three Redfin public CSV snapshots from
their public S3 bucket and caches them locally for 30 days:

-  — city-level data
-  — zip code data
-  — county data

After the first run, startup is fast since the data is already cached. The app auto-refreshes the cache when data is older than 30 days.

For the web deployment, this startup-time download model will be replaced by a scheduled
build pipeline that produces a compact browser-friendly dataset ahead of time.

## Search

The search bar accepts:

- **City** — e.g. 
- **Zip code** — e.g. 
- **County** — e.g. 

Autocomplete suggestions appear as you type. Results stream in via SSE (Server-Sent Events) so you see each metric as it loads.

## Data Source

All market data comes from [Redfin's public data center](https://www.redfin.com/news/data-center/). Income data comes from the [US Census Bureau ACS 5-year estimates](https://www.census.gov/programs-surveys/acs) (no API key required).

## License

MIT
