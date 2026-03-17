#!/usr/bin/env python3
"""
Market Scout — Multi-source CSV backend.
Supports City, Zip Code, and County lookups from Redfin data.
No browser required — all data from Redfin's public CSV files.
"""

from flask import Flask, send_file, request, jsonify, Response
import os, gzip, io, json, datetime, threading, queue
import urllib.request
import re

# ── Census Income Lookup ──────────────────────────────────────────────────────
# Uses Census Bureau API (free, no key needed for basic queries)
# Variable B19013_001E = Median Household Income

_income_cache = {}

def get_income(query, dtype):
    """
    Fetch median household income from Census API.
    Returns formatted string like "$72,400" or None.
    """
    if query in _income_cache:
        return _income_cache[query]

    try:
        if dtype == "zip":
            # ZIP code income
            url = f"https://api.census.gov/data/2022/acs/acs5?get=B19013_001E,NAME&for=zip+code+tabulation+area:{query}&key="
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            resp = urllib.request.urlopen(req, timeout=10)
            data = json.loads(resp.read())
            if len(data) > 1:
                income = int(data[1][0])
                result = f"${income:,}" if income > 0 else None
                _income_cache[query] = result
                return result

        elif dtype == "city":
            parts = query.split(",")
            if len(parts) < 2:
                return None
            city  = parts[0].strip()
            state = parts[1].strip().upper()

            # Get state FIPS code
            state_fips = {
                "AL":"01","AK":"02","AZ":"04","AR":"05","CA":"06","CO":"08","CT":"09",
                "DE":"10","FL":"12","GA":"13","HI":"15","ID":"16","IL":"17","IN":"18",
                "IA":"19","KS":"20","KY":"21","LA":"22","ME":"23","MD":"24","MA":"25",
                "MI":"26","MN":"27","MS":"28","MO":"29","MT":"30","NE":"31","NV":"32",
                "NH":"33","NJ":"34","NM":"35","NY":"36","NC":"37","ND":"38","OH":"39",
                "OK":"40","OR":"41","PA":"42","RI":"44","SC":"45","SD":"46","TN":"47",
                "TX":"48","UT":"49","VT":"50","VA":"51","WA":"53","WV":"54","WI":"55","WY":"56"
            }.get(state)
            if not state_fips:
                return None

            url = f"https://api.census.gov/data/2022/acs/acs5?get=B19013_001E,NAME&for=place:*&in=state:{state_fips}&key="
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            resp = urllib.request.urlopen(req, timeout=10)
            data = json.loads(resp.read())
            city_lower = city.lower()
            for row in data[1:]:
                name = row[1].lower()
                if city_lower in name:
                    income = int(row[0])
                    result = f"${income:,}" if income > 0 else None
                    _income_cache[query] = result
                    return result

        elif dtype == "county":
            parts = query.split(",")
            if len(parts) < 2:
                return None
            county = parts[0].strip()
            state  = parts[1].strip().upper()
            state_fips = {
                "AL":"01","AK":"02","AZ":"04","AR":"05","CA":"06","CO":"08","CT":"09",
                "DE":"10","FL":"12","GA":"13","HI":"15","ID":"16","IL":"17","IN":"18",
                "IA":"19","KS":"20","KY":"21","LA":"22","ME":"23","MD":"24","MA":"25",
                "MI":"26","MN":"27","MS":"28","MO":"29","MT":"30","NE":"31","NV":"32",
                "NH":"33","NJ":"34","NM":"35","NY":"36","NC":"37","ND":"38","OH":"39",
                "OK":"40","OR":"41","PA":"42","RI":"44","SC":"45","SD":"46","TN":"47",
                "TX":"48","UT":"49","VT":"50","VA":"51","WA":"53","WV":"54","WI":"55","WY":"56"
            }.get(state)
            if not state_fips:
                return None
            url = f"https://api.census.gov/data/2022/acs/acs5?get=B19013_001E,NAME&for=county:*&in=state:{state_fips}&key="
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            resp = urllib.request.urlopen(req, timeout=10)
            data = json.loads(resp.read())
            county_lower = county.lower()
            for row in data[1:]:
                name = row[1].lower()
                if county_lower.replace(" county","") in name:
                    income = int(row[0])
                    result = f"${income:,}" if income > 0 else None
                    _income_cache[query] = result
                    return result

    except Exception as e:
        pass

    _income_cache[query] = None
    return None

app = Flask(__name__)

# ── Config ───────────────────────────────────────────────────────────────────
BASE_DIR = os.path.dirname(__file__)

DATASETS = {
    "city": {
        "url":   "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/city_market_tracker.tsv000.gz",
        "local": os.path.join(BASE_DIR, "redfin_city_data.tsv.gz"),
    },
    "zip": {
        "url":   "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/zip_code_market_tracker.tsv000.gz",
        "local": os.path.join(BASE_DIR, "redfin_zip_data.tsv.gz"),
    },
    "county": {
        "url":   "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/county_market_tracker.tsv000.gz",
        "local": os.path.join(BASE_DIR, "redfin_county_data.tsv.gz"),
    },
}

_caches = {"city": None, "zip": None, "county": None}
_loaded = {"city": False, "zip": False, "county": False}
_app_ready = False  # True once all datasets loaded
_load_log  = []     # Loading status messages for the waiting page


# ── Input type detection ─────────────────────────────────────────────────────

def detect_type(query):
    """Detect whether input is a zip code, county, or city."""
    q = query.strip()
    if re.match(r'^\d{5}$', q):
        return "zip"
    if re.search(r'\bcounty\b', q, re.IGNORECASE):
        return "county"
    return "city"


# ── CSV Loader ───────────────────────────────────────────────────────────────

def should_refresh(local_path):
    """Re-download only if file doesn't exist or is older than 30 days."""
    if not os.path.exists(local_path):
        return True
    age_days = (datetime.date.today() - datetime.date.fromtimestamp(os.path.getmtime(local_path))).days
    if age_days >= 30:
        print(f"  {os.path.basename(local_path)} is {age_days} days old — refreshing monthly")
        return True
    print(f"  {os.path.basename(local_path)} is current ({age_days} days old)")
    return False


def download_or_load(dataset_key):
    """Download or load a dataset. Auto-refreshes when new Redfin data is available."""
    ds = DATASETS[dataset_key]

    if should_refresh(ds["local"]):
        print(f"  Downloading {dataset_key} data (~20MB)...")
        try:
            req = urllib.request.Request(ds["url"], headers={"User-Agent": "Mozilla/5.0"})
            resp = urllib.request.urlopen(req, timeout=90)
            data = resp.read()
            with open(ds["local"], 'wb') as f:
                f.write(data)
            print(f"  Saved {dataset_key} data ({len(data)//1024//1024}MB)")
            return data
        except Exception as e:
            print(f"  Download failed: {e} — using cached version if available")
            if os.path.exists(ds["local"]):
                with open(ds["local"], 'rb') as f:
                    return f.read()
            raise
    else:
        with open(ds["local"], 'rb') as f:
            return f.read()


def parse_csv(data, dataset_key="city"):
    """
    Parse a Redfin market tracker CSV into a lookup dict.
    Key format:
      city:   "atlanta_GA"
      zip:    "30024"
      county: "gwinnett county_GA"
    Returns {key: data_dict}
    """
    def clean(v): return v.strip().strip('"')
    def num(v):
        try: return float(clean(v))
        except: return None
    def pct_ratio(v):
        f = num(v)
        if f is None: return None
        return round((f - 1) * 100, 1)
    def pct_direct(v):
        f = num(v)
        if f is None: return None
        return round(f * 100, 1)

    result = {}

    with gzip.open(io.BytesIO(data), 'rt', encoding='utf-8', errors='ignore') as f:
        raw_cols = f.readline().strip().split('\t')
        cols = [clean(c).upper() for c in raw_cols]

        # Required columns
        required = ['PERIOD_END','PROPERTY_TYPE','MONTHS_OF_SUPPLY',
                    'PENDING_SALES','INVENTORY','MEDIAN_DOM',
                    'AVG_SALE_TO_LIST','SOLD_ABOVE_LIST','MEDIAN_SALE_PRICE',
                    'HOMES_SOLD','NEW_LISTINGS','PRICE_DROPS',
                    'OFF_MARKET_IN_TWO_WEEKS']

        # Optional columns that differ by dataset
        C = {}
        for col in required:
            if col in cols:
                C[col] = cols.index(col)

        PERIOD_COL    = cols.index('PERIOD_END')
        PROP_COL      = cols.index('PROPERTY_TYPE')
        STATE_COL     = cols.index('STATE_CODE') if 'STATE_CODE' in cols else None
        DURATION_COL  = cols.index('PERIOD_DURATION') if 'PERIOD_DURATION' in cols else None

        # dataset_key tells us exactly what we're parsing — no peeking needed
        # City:   use CITY column for key
        # Zip:    use REGION column ("Zip Code: 30024" -> strip to "30024")
        # County: use REGION column ("Gwinnett County, GA" -> "gwinnett county_GA")
        dataset_type = dataset_key
        if dataset_type == 'city':
            KEY_COL = cols.index('CITY') if 'CITY' in cols else cols.index('REGION')
        else:
            KEY_COL = cols.index('REGION')
        KEY_COL2 = STATE_COL

        latest = {}

        for line in f:
            row = line.strip().split('\t')
            if len(row) <= KEY_COL:
                continue
            if clean(row[PROP_COL]) != 'All Residential':
                continue

            region_raw = clean(row[KEY_COL]) if KEY_COL < len(row) else ""
            state      = clean(row[KEY_COL2]).upper() if KEY_COL2 and KEY_COL2 < len(row) else ""
            period     = clean(row[PERIOD_COL])

            if not region_raw:
                continue

            if dataset_type == 'city':
                primary = region_raw.lower()
                key = f"{primary}_{state}" if state else primary

            elif dataset_type == 'zip':
                # REGION = "Zip Code: 30024" — extract number only
                zip_num = region_raw.replace('Zip Code:', '').replace('zip code:', '').strip()
                if not zip_num.isdigit() or len(zip_num) != 5:
                    continue
                key = zip_num
                primary = zip_num

            elif dataset_type == 'county':
                # REGION = "Gwinnett County, GA" — normalize to "gwinnett county_GA"
                county_name = region_raw.lower().strip()
                if ',' in county_name:
                    county_name = county_name.split(',')[0].strip()
                key = f"{county_name}_{state}" if state else county_name
                primary = county_name

            else:
                primary = region_raw.lower()
                key = f"{primary}_{state}" if state else primary

            if key in latest and period <= latest[key]['period']:
                continue

            pending   = num(row[C['PENDING_SALES']]) if 'PENDING_SALES' in C else None
            inventory = num(row[C['INVENTORY']]) if 'INVENTORY' in C else None

            # Normalize PENDING_SALES to monthly rate before calculating PAR
            # PENDING_SALES is cumulative over period_duration days
            # Dividing by (period_duration/30) converts to monthly equivalent
            period_dur = 30
            try:
                if DURATION_COL and DURATION_COL < len(row):
                    period_dur = max(30, int(float(row[DURATION_COL].strip().strip('"'))))
            except:
                pass

            par = None
            par_label = None
            if pending is not None and inventory is not None and inventory > 0:
                # Normalize pending to monthly rate
                pending_monthly = pending / (period_dur / 30)
                total = pending_monthly + inventory
                if total > 0:
                    par = round((pending_monthly / total) * 100, 1)
                    par_label = (
                        "Seller's Market" if par >= 40 else
                        "Balanced"        if par >= 20 else
                        "Buyer's Market"
                    )

            ms = num(row[C['MONTHS_OF_SUPPLY']]) if 'MONTHS_OF_SUPPLY' in C else None
            ms_label = None
            if ms is not None:
                ms_label = (
                    "Hot Seller's"    if ms < 2  else
                    "Seller's Market" if ms < 4  else
                    "Balanced"        if ms <= 6 else
                    "Buyer's Market"
                )

            stl = pct_ratio(row[C['AVG_SALE_TO_LIST']]) if 'AVG_SALE_TO_LIST' in C else None
            stl_str = (f"+{stl}%" if stl and stl >= 0 else f"{stl}%") if stl is not None else None

            sal = pct_direct(row[C['SOLD_ABOVE_LIST']]) if 'SOLD_ABOVE_LIST' in C else None
            pd  = pct_direct(row[C['PRICE_DROPS']]) if 'PRICE_DROPS' in C else None
            omtw = pct_direct(row[C['OFF_MARKET_IN_TWO_WEEKS']]) if 'OFF_MARKET_IN_TWO_WEEKS' in C else None

            med = num(row[C['MEDIAN_SALE_PRICE']]) if 'MEDIAN_SALE_PRICE' in C else None
            med_str = None
            if med:
                med_str = f"${med/1000000:.2f}M" if med >= 1000000 else f"${int(med):,}"

            dom = num(row[C['MEDIAN_DOM']]) if 'MEDIAN_DOM' in C else None

            latest[key] = {
                'period':          period,
                'display_name':    clean(row[KEY_COL]),
                'state':           state,
                'median_sale':     med_str,
                'months_supply':   ms,
                'supply_label':    ms_label,
                'par_ratio':       par,
                'par_pending':     int(pending) if pending else None,
                'par_total':       int(pending + inventory) if (pending and inventory) else None,
                'par_label':       par_label,
                'median_dom':      int(dom) if dom else None,
                'sale_to_list':    stl_str,
                'sold_above_list': f"{sal}%" if sal is not None else None,
                'price_drops':     f"{pd}%" if pd is not None else None,
                'off_market_2wk':  f"{omtw}%" if omtw is not None else None,
                'homes_sold':      int(num(row[C['HOMES_SOLD']])) if 'HOMES_SOLD' in C and num(row[C['HOMES_SOLD']]) else None,
                'new_listings':    int(num(row[C['NEW_LISTINGS']])) if 'NEW_LISTINGS' in C and num(row[C['NEW_LISTINGS']]) else None,
            }

        result = latest
        print(f"  Loaded {len(result)} entries")

    return result


def load_dataset(dataset_key):
    """Load a dataset, using cache if already loaded."""
    global _caches, _loaded
    if _loaded[dataset_key]:
        return _caches[dataset_key]
    try:
        data = download_or_load(dataset_key)
        _caches[dataset_key] = parse_csv(data, dataset_key)
    except Exception as e:
        print(f"  Failed to load {dataset_key}: {e}")
        import traceback; traceback.print_exc()
        _caches[dataset_key] = {}
    _loaded[dataset_key] = True
    return _caches[dataset_key]


def lookup(query):
    """Look up a city, zip, or county."""
    q = query.strip()
    dtype = detect_type(q)

    if dtype == "zip":
        cache = load_dataset("zip")
        result = cache.get(q.strip())  # key is just "30024"
        return result or {}, dtype

    elif dtype == "county":
        cache = load_dataset("county")
        # Input: "Gwinnett County, GA" -> key "gwinnett county_GA"
        parts = q.split(",")
        if len(parts) >= 2:
            county = parts[0].strip().lower()  # "gwinnett county"
            state  = parts[1].strip().upper()  # "GA"
            key    = f"{county}_{state}"
            result = cache.get(key, {})
            if not result:
                # Try without "county" suffix
                county_bare = county.replace(' county', '').strip()
                key2 = f"{county_bare} county_{state}"
                result = cache.get(key2, {})
            return result, dtype
        return {}, dtype

    else:  # city
        cache = load_dataset("city")
        parts = q.split(",")
        if len(parts) >= 2:
            city  = parts[0].strip().lower()
            state = parts[1].strip().upper()
            key   = f"{city}_{state}"
            return cache.get(key, {}), dtype
        return {}, dtype


# ── Scraper (instant CSV lookup) ─────────────────────────────────────────────

def run_scraper(queries, q_out):
    try:
        # Pre-load all needed datasets
        types_needed = set(detect_type(q) for q in queries)
        for t in types_needed:
            load_dataset(t)

        for query in queries:
            q_out.put({"type": "progress", "message": f"Looking up {query}..."})
            data, dtype = lookup(query)
            if not data:
                q_out.put({"type": "result", "data": {
                    "city": query, "dtype": dtype, "status": "Not found in Redfin data",
                    "period": None, "median_sale": None, "months_supply": None,
                    "supply_label": None, "par_ratio": None, "par_pending": None,
                    "par_total": None, "par_label": None, "median_dom": None,
                    "sale_to_list": None, "sold_above_list": None,
                    "price_drops": None, "off_market_2wk": None,
                    "homes_sold": None, "new_listings": None, "hh_income": None,
                }})
            else:
                data["city"]   = query
                data["dtype"]  = dtype
                data["status"] = "OK"
                # Fetch income from Census API
                data["hh_income"] = get_income(query, dtype)
                q_out.put({"type": "result", "data": data})

        q_out.put({"type": "done"})

    except Exception as e:
        q_out.put({"type": "error", "message": str(e)})


# ── Search index ─────────────────────────────────────────────────────────────

_search_index = None

def build_search_index():
    global _search_index
    if _search_index is not None:
        return _search_index
    _search_index = []
    # Cities
    cache = load_dataset("city")
    for key in cache:
        parts = key.rsplit("_", 1)
        if len(parts) == 2:
            city, state = parts
            _search_index.append({
                "label": city.title() + ", " + state,
                "value": city.title() + ", " + state,
                "type": "city"
            })
    # Counties — key: "gwinnett county_GA"
    cache = load_dataset("county")
    for key in cache:
        parts = key.rsplit("_", 1)
        if len(parts) == 2:
            county, state = parts
            # Only include if it looks like a real county (contains "county")
            if "county" in county.lower():
                label = county.title() + ", " + state
                _search_index.append({
                    "label": label,
                    "value": label,
                    "type": "county"
                })
    # Zips — key is just the 5-digit zip number
    cache = load_dataset("zip")
    for key in cache:
        if key.isdigit() and len(key) == 5:
            state = cache[key].get("state", "") if isinstance(cache[key], dict) else ""
            label = key + (", " + state if state else "")
            _search_index.append({
                "label": label,
                "value": key,
                "type": "zip"
            })
    print(f"  Search index built: {len(_search_index)} entries")
    return _search_index


# ── Routes ───────────────────────────────────────────────────────────────────

@app.route("/")
def index():
    return send_file(os.path.join(BASE_DIR, "templates", "index.html"))


@app.route("/status")
def status():
    return jsonify({"ready": _app_ready, "log": _load_log})


@app.route("/suggest")
def suggest():
    q = request.args.get("q", "").strip().lower()
    if len(q) < 2:
        return jsonify([])
    index = build_search_index()
    matches = [
        item for item in index
        if q in item["label"].lower()
    ]
    # Prioritize starts-with matches
    starts = [m for m in matches if m["label"].lower().startswith(q)]
    others = [m for m in matches if not m["label"].lower().startswith(q)]
    return jsonify((starts + others)[:12])


@app.route("/scrape")
def scrape():
    cities_raw = request.args.get("cities", "")
    queries = [c.strip() for c in cities_raw.split("|") if c.strip()]
    if not queries:
        return jsonify({"error": "No valid queries"}), 400

    def generate():
        q = queue.Queue()
        t = threading.Thread(target=run_scraper, args=(queries, q), daemon=True)
        t.start()
        while True:
            try:
                msg = q.get(timeout=180)
                yield f"data: {json.dumps(msg)}\n\n"
                if msg["type"] in ("done", "error"):
                    break
            except queue.Empty:
                yield f"data: {json.dumps({'type':'error','message':'Timeout'})}\n\n"
                break

    return Response(generate(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


def background_loader():
    """Load all datasets in background so Flask starts immediately."""
    global _app_ready, _load_log
    _load_log.append("Starting up...")

    _load_log.append("City data loading...")
    load_dataset("city")
    city_count = len(_caches.get("city") or {})
    _load_log.append(f"City data loaded — {city_count:,} cities")

    _load_log.append("Zip data loading...")
    load_dataset("zip")
    zip_count = len(_caches.get("zip") or {})
    _load_log.append(f"Zip data loaded — {zip_count:,} zip codes")

    _load_log.append("County data loading...")
    load_dataset("county")
    county_count = len(_caches.get("county") or {})
    _load_log.append(f"County data loaded — {county_count:,} counties")

    _load_log.append("Building search index...")
    build_search_index()
    _load_log.append("Ready!")

    _app_ready = True
    print("  Ready. All data loaded.\n")


if __name__ == "__main__":
    print("\n" + "="*50)
    print("   MARKET SCOUT — Starting up")
    print("   Open: http://127.0.0.1:8080")
    print("   Supports: City, Zip Code, County")
    print("="*50 + "\n")
    # Start data loading in background thread
    loader = threading.Thread(target=background_loader, daemon=True)
    loader.start()
    app.run(debug=False, port=8080, host="0.0.0.0", threaded=True)
