# Cloudflare Pages Setup

This repository's GitHub Actions workflow can build the static site every month and deploy
it directly to Cloudflare Pages.

## Workflow

The workflow lives at:

- `.github/workflows/static-site.yml`

It does three things:

1. builds the static Redfin data artifacts
2. assembles the deployable site under `dist/`
3. deploys `dist/` to Cloudflare Pages if Cloudflare credentials are configured

## Two valid Cloudflare Pages modes

### 1. Git integration build inside Cloudflare Pages

Use this while creating the Pages project if Cloudflare is building the site directly from
your repository.

Configuration:

- Build command: `bash scripts/build_cloudflare_pages.sh`
- Build output directory: `dist`

Why:

- this repository does not commit `dist/`
- `dist/` is created by running the Phase 1 and Phase 2 build steps
- using `exit 0` in this mode causes Pages to fail because there is no output directory

### 2. Direct upload from GitHub Actions

Use this when GitHub Actions builds `dist/` and deploys it directly to Cloudflare Pages via
Wrangler.

In that mode, `exit 0` is acceptable because Cloudflare does not need to build the site
from source itself.

## Trigger behavior

- `push` to `master`: build and optionally deploy
- `pull_request`: build only
- `schedule`: monthly refresh on the first day of the month at `12:00 UTC`
- `workflow_dispatch`: manual run, with optional forced data refresh

## Required repository configuration for deployment

Add these before expecting Cloudflare deployment to run:

### GitHub repository secrets

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

### GitHub repository variable

- `CLOUDFLARE_PAGES_PROJECT`

This should be the Cloudflare Pages project name, for example:

- `market-scout`

## Cloudflare token scope

For Pages direct upload, Cloudflare documents using an API token with:

- `Account`
- `Cloudflare Pages`
- `Edit`

See Cloudflare's current guide:

- https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/

## Notes

- If the Cloudflare secrets or project variable are missing, the workflow still builds and
  uploads the `dist/` artifact, but the deploy job is skipped.
- This uses direct upload via Wrangler rather than relying on Cloudflare's Git integration.
- Direct upload is useful here because the scheduled GitHub Actions run can refresh the
  data and publish a new deployment without requiring a new git commit.
- The most common setup mistake is using `exit 0` with Git integration before `dist/` is
  being uploaded by GitHub Actions. In that case, Pages has no built assets to publish.
