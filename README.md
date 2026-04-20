# Market Scout

Real estate market analytics tool powered by Redfin public data. Search any US city, zip code, or county and get key market metrics instantly — no API key, no scraping, no browser automation required.

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

On startup, the app downloads three Redfin public CSVs (~20 MB each) from their public S3 bucket and caches them locally for 30 days:

-  — city-level data
-  — zip code data
-  — county data

After the first run, startup is fast since the data is already cached. The app auto-refreshes the cache when data is older than 30 days.

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
