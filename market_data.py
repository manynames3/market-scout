#!/usr/bin/env python3
"""
Shared Redfin dataset helpers for the local Flask app and the static web data build.
"""

from __future__ import annotations

import datetime
import email.utils
import gzip
import json
import os
import shutil
import urllib.request
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
DEFAULT_RAW_DIR = ROOT_DIR / ".cache" / "redfin"
DEFAULT_BUILD_DIR = ROOT_DIR / "generated" / "web-data"

DATASET_SPECS = {
    "city": {
        "url": "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/city_market_tracker.tsv000.gz",
        "filename": "redfin_city_data.tsv.gz",
    },
    "zip": {
        "url": "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/zip_code_market_tracker.tsv000.gz",
        "filename": "redfin_zip_data.tsv.gz",
    },
    "county": {
        "url": "https://redfin-public-data.s3.us-west-2.amazonaws.com/redfin_market_tracker/county_market_tracker.tsv000.gz",
        "filename": "redfin_county_data.tsv.gz",
    },
}

DATASET_ORDER = ("city", "zip", "county")


def get_dataset_local_path(dataset_key, raw_dir=DEFAULT_RAW_DIR):
    raw_dir = Path(raw_dir)
    return raw_dir / DATASET_SPECS[dataset_key]["filename"]


def should_refresh(local_path, max_age_days=30):
    """Re-download only if file doesn't exist or is older than max_age_days."""
    local_path = Path(local_path)
    if not local_path.exists():
        return True
    age_days = (
        datetime.date.today() - datetime.date.fromtimestamp(local_path.stat().st_mtime)
    ).days
    return age_days >= max_age_days


def download_dataset(dataset_key, destination, timeout=90):
    """Stream a Redfin dataset to disk so large source files do not sit in RAM."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = destination.with_suffix(destination.suffix + ".tmp")
    spec = DATASET_SPECS[dataset_key]
    request = urllib.request.Request(
        spec["url"], headers={"User-Agent": "Mozilla/5.0"}
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        with open(tmp_path, "wb") as handle:
            shutil.copyfileobj(response, handle, length=1024 * 1024)
    tmp_path.replace(destination)
    return destination


def _normalize_http_datetime(value):
    if not value:
        return None
    try:
        parsed = email.utils.parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        parsed = parsed.astimezone(datetime.timezone.utc)
        return parsed.replace(microsecond=0).isoformat().replace("+00:00", "Z")
    except Exception:
        return None


def fetch_dataset_metadata(dataset_key, timeout=30):
    """Fetch current upstream metadata for a Redfin dataset via HEAD request."""
    spec = DATASET_SPECS[dataset_key]
    request = urllib.request.Request(
        spec["url"],
        headers={"User-Agent": "Mozilla/5.0"},
        method="HEAD",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        headers = response.headers
        content_length = headers.get("Content-Length")
        return {
            "dataset": dataset_key,
            "url": spec["url"],
            "last_modified": _normalize_http_datetime(headers.get("Last-Modified")),
            "etag": headers.get("ETag", "").strip('"') or None,
            "content_length": int(content_length) if content_length and content_length.isdigit() else None,
        }


def ensure_dataset_file(
    dataset_key,
    raw_dir=DEFAULT_RAW_DIR,
    max_age_days=30,
    force_refresh=False,
    logger=print,
):
    local_path = get_dataset_local_path(dataset_key, raw_dir)
    if force_refresh or should_refresh(local_path, max_age_days=max_age_days):
        if logger:
            logger(f"Downloading {dataset_key} dataset -> {local_path}")
        download_dataset(dataset_key, local_path)
    elif logger:
        logger(f"Using cached {dataset_key} dataset -> {local_path}")
    return local_path


def _clean(value):
    return value.strip().strip('"')


def _num(value):
    try:
        return float(_clean(value))
    except Exception:
        return None


def _pct_ratio(value):
    number = _num(value)
    if number is None:
        return None
    return round((number - 1) * 100, 1)


def _pct_direct(value):
    number = _num(value)
    if number is None:
        return None
    return round(number * 100, 1)


def _record_query_value(dataset_key, key, record):
    state = record.get("state", "")
    if dataset_key == "zip":
        return key
    name = key.rsplit("_", 1)[0] if "_" in key else key
    label = name.title()
    if state:
        return f"{label}, {state}"
    return label


def parse_dataset_file(local_path, dataset_key):
    """
    Parse a Redfin market tracker gzip file into a latest-row-per-market lookup dict.
    """
    local_path = Path(local_path)
    result = {}

    with gzip.open(local_path, "rt", encoding="utf-8", errors="ignore") as handle:
        raw_cols = handle.readline().strip().split("\t")
        cols = [_clean(column).upper() for column in raw_cols]

        required = [
            "PERIOD_END",
            "PROPERTY_TYPE",
            "MONTHS_OF_SUPPLY",
            "PENDING_SALES",
            "INVENTORY",
            "MEDIAN_DOM",
            "AVG_SALE_TO_LIST",
            "SOLD_ABOVE_LIST",
            "MEDIAN_SALE_PRICE",
            "HOMES_SOLD",
            "NEW_LISTINGS",
            "PRICE_DROPS",
            "OFF_MARKET_IN_TWO_WEEKS",
        ]

        column_index = {}
        for column in required:
            if column in cols:
                column_index[column] = cols.index(column)

        period_col = cols.index("PERIOD_END")
        property_type_col = cols.index("PROPERTY_TYPE")
        state_col = cols.index("STATE_CODE") if "STATE_CODE" in cols else None
        duration_col = cols.index("PERIOD_DURATION") if "PERIOD_DURATION" in cols else None

        if dataset_key == "city":
            key_col = cols.index("CITY") if "CITY" in cols else cols.index("REGION")
        else:
            key_col = cols.index("REGION")

        latest = {}

        for line in handle:
            row = line.strip().split("\t")
            if len(row) <= key_col:
                continue
            if _clean(row[property_type_col]) != "All Residential":
                continue

            region_raw = _clean(row[key_col]) if key_col < len(row) else ""
            state = _clean(row[state_col]).upper() if state_col is not None and state_col < len(row) else ""
            period = _clean(row[period_col])

            if not region_raw:
                continue

            if dataset_key == "city":
                primary = region_raw.lower()
                key = f"{primary}_{state}" if state else primary
            elif dataset_key == "zip":
                zip_number = (
                    region_raw.replace("Zip Code:", "").replace("zip code:", "").strip()
                )
                if not zip_number.isdigit() or len(zip_number) != 5:
                    continue
                primary = zip_number
                key = zip_number
            elif dataset_key == "county":
                county_name = region_raw.lower().strip()
                if "," in county_name:
                    county_name = county_name.split(",")[0].strip()
                primary = county_name
                key = f"{county_name}_{state}" if state else county_name
            else:
                primary = region_raw.lower()
                key = f"{primary}_{state}" if state else primary

            if key in latest and period <= latest[key]["period"]:
                continue

            pending = _num(row[column_index["PENDING_SALES"]]) if "PENDING_SALES" in column_index else None
            inventory = _num(row[column_index["INVENTORY"]]) if "INVENTORY" in column_index else None

            period_duration = 30
            try:
                if duration_col is not None and duration_col < len(row):
                    period_duration = max(
                        30, int(float(row[duration_col].strip().strip('"')))
                    )
            except Exception:
                pass

            par_ratio = None
            par_label = None
            if pending is not None and inventory is not None and inventory > 0:
                pending_monthly = pending / (period_duration / 30)
                total = pending_monthly + inventory
                if total > 0:
                    par_ratio = round((pending_monthly / total) * 100, 1)
                    par_label = (
                        "Seller's Market"
                        if par_ratio >= 40
                        else "Balanced"
                        if par_ratio >= 20
                        else "Buyer's Market"
                    )

            months_supply = (
                _num(row[column_index["MONTHS_OF_SUPPLY"]])
                if "MONTHS_OF_SUPPLY" in column_index
                else None
            )
            supply_label = None
            if months_supply is not None:
                supply_label = (
                    "Hot Seller's"
                    if months_supply < 2
                    else "Seller's Market"
                    if months_supply < 4
                    else "Balanced"
                    if months_supply <= 6
                    else "Buyer's Market"
                )

            sale_to_list = (
                _pct_ratio(row[column_index["AVG_SALE_TO_LIST"]])
                if "AVG_SALE_TO_LIST" in column_index
                else None
            )
            sale_to_list_str = (
                f"+{sale_to_list}%" if sale_to_list and sale_to_list >= 0 else f"{sale_to_list}%"
            ) if sale_to_list is not None else None

            sold_above_list = (
                _pct_direct(row[column_index["SOLD_ABOVE_LIST"]])
                if "SOLD_ABOVE_LIST" in column_index
                else None
            )
            price_drops = (
                _pct_direct(row[column_index["PRICE_DROPS"]])
                if "PRICE_DROPS" in column_index
                else None
            )
            off_market_two_weeks = (
                _pct_direct(row[column_index["OFF_MARKET_IN_TWO_WEEKS"]])
                if "OFF_MARKET_IN_TWO_WEEKS" in column_index
                else None
            )

            median_sale = (
                _num(row[column_index["MEDIAN_SALE_PRICE"]])
                if "MEDIAN_SALE_PRICE" in column_index
                else None
            )
            median_sale_str = None
            if median_sale:
                median_sale_str = (
                    f"${median_sale / 1000000:.2f}M"
                    if median_sale >= 1000000
                    else f"${int(median_sale):,}"
                )

            median_dom = (
                _num(row[column_index["MEDIAN_DOM"]])
                if "MEDIAN_DOM" in column_index
                else None
            )

            latest[key] = {
                "key": key,
                "type": dataset_key,
                "query": _record_query_value(dataset_key, key, {"state": state}),
                "display_name": _clean(row[key_col]),
                "state": state,
                "period": period,
                "median_sale": median_sale_str,
                "months_supply": months_supply,
                "supply_label": supply_label,
                "par_ratio": par_ratio,
                "par_pending": int(pending) if pending else None,
                "par_total": int(pending + inventory) if (pending and inventory) else None,
                "par_label": par_label,
                "median_dom": int(median_dom) if median_dom else None,
                "sale_to_list": sale_to_list_str,
                "sold_above_list": (
                    f"{sold_above_list}%" if sold_above_list is not None else None
                ),
                "price_drops": f"{price_drops}%" if price_drops is not None else None,
                "off_market_2wk": (
                    f"{off_market_two_weeks}%" if off_market_two_weeks is not None else None
                ),
                "homes_sold": (
                    int(_num(row[column_index["HOMES_SOLD"]]))
                    if "HOMES_SOLD" in column_index and _num(row[column_index["HOMES_SOLD"]])
                    else None
                ),
                "new_listings": (
                    int(_num(row[column_index["NEW_LISTINGS"]]))
                    if "NEW_LISTINGS" in column_index and _num(row[column_index["NEW_LISTINGS"]])
                    else None
                ),
            }

        result = latest

    return result


def build_search_index(caches):
    index = []
    for dataset_key in DATASET_ORDER:
        cache = caches.get(dataset_key, {})
        for key, record in cache.items():
            state = record.get("state", "") or "NA"
            if dataset_key == "city":
                label = _record_query_value(dataset_key, key, record)
                value = label
            elif dataset_key == "county":
                if "county" not in key.lower():
                    continue
                label = _record_query_value(dataset_key, key, record)
                value = label
            else:
                label = key + (f", {record['state']}" if record.get("state") else "")
                value = key

            index.append(
                {
                    "label": label,
                    "value": value,
                    "type": dataset_key,
                    "key": key,
                    "state": record.get("state", ""),
                    "shard": f"markets/{dataset_key}/{state}.json",
                }
            )

    index.sort(key=lambda item: (item["label"].lower(), item["type"], item["value"]))
    return index


def _build_dataset_shards(dataset_key, cache):
    shards = {}
    for key, record in cache.items():
        state = record.get("state", "") or "NA"
        state_bucket = shards.setdefault(state, {})
        state_bucket[key] = record
    return shards


def _write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, separators=(",", ":"), sort_keys=True)


def write_static_artifacts(caches, output_dir=DEFAULT_BUILD_DIR, source_metadata=None):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    source_metadata = source_metadata or {}

    search_index = build_search_index(caches)
    _write_json(output_dir / "search-index.json", search_index)

    dataset_manifests = {}
    total_counts = {}
    for dataset_key in DATASET_ORDER:
        cache = caches.get(dataset_key, {})
        shards = _build_dataset_shards(dataset_key, cache)
        state_manifest = {}
        for state, items in sorted(shards.items()):
            shard_path = output_dir / "markets" / dataset_key / f"{state}.json"
            payload = {
                "type": dataset_key,
                "state": state,
                "count": len(items),
                "items": items,
            }
            _write_json(shard_path, payload)
            state_manifest[state] = {
                "count": len(items),
                "path": str(Path("markets") / dataset_key / f"{state}.json"),
            }

        dataset_manifest = {
            "type": dataset_key,
            "count": len(cache),
            "states": state_manifest,
        }
        dataset_manifests[dataset_key] = dataset_manifest
        total_counts[dataset_key] = len(cache)

        top_level_name = {
            "city": "cities.json",
            "zip": "zips.json",
            "county": "counties.json",
        }[dataset_key]
        _write_json(output_dir / top_level_name, dataset_manifest)

    latest_source_updated_at = None
    source_last_modified_values = [
        metadata.get("last_modified")
        for metadata in source_metadata.values()
        if metadata.get("last_modified")
    ]
    if source_last_modified_values:
        latest_source_updated_at = max(source_last_modified_values)

    manifest = {
        "generated_at": datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
        "datasets": total_counts,
        "search_index_count": len(search_index),
        "latest_source_updated_at": latest_source_updated_at,
        "sources": source_metadata,
        "files": {
            "search_index": "search-index.json",
            "cities": "cities.json",
            "zips": "zips.json",
            "counties": "counties.json",
        },
    }
    _write_json(output_dir / "manifest.json", manifest)
    return {
        "manifest": manifest,
        "dataset_manifests": dataset_manifests,
        "search_index_count": len(search_index),
    }
