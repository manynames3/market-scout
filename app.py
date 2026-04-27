#!/usr/bin/env python3
"""
Market Scout — Multi-source CSV backend.
Supports City, Zip Code, and County lookups from Redfin data.
No browser required — all data from Redfin's public CSV files.
"""

from flask import Flask, send_file, request, jsonify, Response
import os, json, threading, queue
import urllib.request
import re
from market_data import (
    build_search_index as build_static_search_index,
    ensure_dataset_file,
    parse_dataset_file,
)

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


def load_dataset(dataset_key):
    """Load a dataset, using cache if already loaded."""
    global _caches, _loaded
    if _loaded[dataset_key]:
        return _caches[dataset_key]
    try:
        local_path = ensure_dataset_file(dataset_key, raw_dir=BASE_DIR, logger=print)
        _caches[dataset_key] = parse_dataset_file(local_path, dataset_key)
        print(f"  Loaded {len(_caches[dataset_key]):,} entries")
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
    cache_bundle = {
        "city": load_dataset("city"),
        "county": load_dataset("county"),
        "zip": load_dataset("zip"),
    }
    _search_index = [
        {"label": item["label"], "value": item["value"], "type": item["type"]}
        for item in build_static_search_index(cache_bundle)
    ]
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
