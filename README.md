# Market Scout

Market Scout is a real estate market analytics tool powered by Redfin public data. It can
search US cities, ZIP codes, and counties and surface the metrics that matter most for
evaluating a housing market.

The project started as a local Python/Flask app, later gained a macOS wrapper, and now has
a static web deployment path designed for very low monthly cost.

## What It Does

Market Scout pulls market-level housing data from Redfin's public market tracker datasets
and presents it in a simpler form for quick comparison.

Metrics include:

| Metric | What It Tells You |
|--------|-------------------|
| Median Sale Price | Current market price level |
| Months of Supply | Inventory tightness |
| Pending-to-Available Ratio (PAR) | Demand intensity |
| Median Days on Market | How fast homes are selling |
| Sale-to-List % | Whether homes sell above or below ask |
| Sold Above List % | Share of homes facing bidding pressure |
| Price Drop % | Share of listings with reductions |
| Off Market in 2 Weeks % | Speed of inventory absorption |
| Median Household Income | Census ACS 5-year estimate |

## Project Modes

This repository currently supports two practical modes:

### 1. Local Flask app

Good for development and local use.

- loads Redfin data into memory
- serves the UI from Flask
- supports local caching on disk

### 2. Static web build

Good for public hosting and phones.

- prebuilds compact Redfin artifacts
- serves a static frontend
- can be hosted cheaply on Cloudflare Pages

## Why The Web Version Changed

The original local architecture was fine on a laptop, but it is a poor fit for cheap web
hosting because it assumes:

- a long-lived Python process
- local filesystem caching
- startup-time dataset downloads
- in-memory search structures

The web version therefore uses:

- Cloudflare Pages for the frontend
- prebuilt JSON artifacts for search and market lookups
- GitHub Actions for optional scheduled rebuilds and deploys

For the detailed decision record, see
[docs/adr/0001-web-hosting-and-cost-strategy.md](docs/adr/0001-web-hosting-and-cost-strategy.md).

## Local App Setup

### Requirements

- Python 3.8+
- `pip3`

### Install dependencies

```bash
pip3 install flask playwright playwright-stealth
python3 -m playwright install chromium
```

Or use the included setup script:

```bash
bash setup.sh
```

### Run the local app

```bash
python3 app.py
```

Then open:

- [http://127.0.0.1:8080](http://127.0.0.1:8080)

On macOS you can also use the app wrapper or launcher script:

```bash
bash launch.sh
```

## Static Web Build

The static web path is split into two steps.

### Phase 1: Build static data artifacts

```bash
python3 scripts/build_static_data.py
```

This:

- downloads or reuses cached Redfin source files
- keeps only the latest row per market
- emits compact browser-friendly artifacts under `generated/web-data/`

Default raw cache location:

- `.cache/redfin/`

Generated artifact directory:

- `generated/web-data/`

### Phase 2: Assemble the deployable site

```bash
python3 scripts/build_static_site.py
```

This builds:

- frontend assets from `static/`
- final deployable site under `dist/`
- runtime data copied into `dist/data/`

### Preview locally

```bash
python3 -m http.server 8000 --directory dist
```

Then open:

- [http://127.0.0.1:8000](http://127.0.0.1:8000)

## Cloudflare Pages Setup

If Cloudflare Pages is building directly from GitHub, use:

- Build command: `bash scripts/build_cloudflare_pages.sh`
- Build output directory: `dist`

That script simply runs the data build and site assembly steps in order:

```bash
bash scripts/build_cloudflare_pages.sh
```

### Why not `exit 0`

Cloudflare's docs often show `exit 0` for static projects when the output directory already
exists and Pages does not need to build anything itself.

That does **not** work for this repository when Cloudflare is building from source, because:

- `dist/` is not committed
- `dist/` is created at build time
- skipping the build means there is nothing to publish

If Cloudflare runs `exit 0` here, deployment fails with:

- `Output directory "dist" not found`

## Monthly Redfin Refreshes

This repository includes a GitHub Actions workflow at
[`/.github/workflows/static-site.yml`](.github/workflows/static-site.yml) that can rebuild
the site monthly from fresh Redfin data.

Monthly schedule:

- first day of the month at `12:00 UTC`

Important distinction:

- Cloudflare Git integration alone can keep the site online
- GitHub Actions is what enables scheduled monthly rebuilds from Redfin without a manual
  code change or redeploy

To let GitHub Actions publish the rebuilt site to Cloudflare Pages, configure:

- secret `CLOUDFLARE_API_TOKEN`
- secret `CLOUDFLARE_ACCOUNT_ID`
- variable `CLOUDFLARE_PAGES_PROJECT`

If those values are not configured:

- the site can still work
- but the monthly GitHub Actions rebuild will not be able to deploy refreshed data to
  Cloudflare Pages

For setup details, see
[docs/cloudflare-pages-setup.md](docs/cloudflare-pages-setup.md).

## Data Sources

- [Redfin Data Center](https://www.redfin.com/news/data-center/)
- [US Census Bureau ACS 5-year estimates](https://www.census.gov/programs-surveys/acs)

## Additional Docs

- [docs/web-migration-plan.md](docs/web-migration-plan.md)
- [docs/cloudflare-pages-setup.md](docs/cloudflare-pages-setup.md)
- [docs/adr/0001-web-hosting-and-cost-strategy.md](docs/adr/0001-web-hosting-and-cost-strategy.md)

## License

MIT
