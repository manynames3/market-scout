#!/usr/bin/env python3
"""
Redfin Compete Score Bulk Scraper
===================================
Pulls Compete Score, DOM, median sale price, and sale/list ratio
from Redfin's housing market pages for any list of cities.

USAGE:
  python3 redfin_compete_score.py

Edit the CITIES list at the bottom to add/remove markets.

REQUIREMENTS:
  pip3 install playwright
  python3 -m playwright install chromium
"""

import re
import time
import json
import datetime
from playwright.sync_api import sync_playwright
try:
    from playwright_stealth import stealth_sync
    HAS_STEALTH = True
except ImportError:
    HAS_STEALTH = False

def get_cities_from_user():
    """Prompt user to enter cities interactively."""
    print("\n" + "═" * 50)
    print("   REDFIN COMPETE SCORE SCRAPER")
    print("═" * 50)
    print("Enter cities one per line in \'City, ST\' format.")
    print("Press ENTER twice when done.\n")

    cities = []
    while True:
        entry = input("  City, ST: ").strip()
        if entry == "":
            if cities:
                break
            else:
                print("  (enter at least one city)")
                continue
        if "," not in entry:
            print("  Format must be \'City, ST\' (e.g. Atlanta, GA) — try again")
            continue
        cities.append(entry)
        print(f"  Added: {entry}")

    print(f"\nRunning for {len(cities)} city/cities...\n")
    return cities


def get_redfin_market_url(page, city_state: str):
    """Use Redfin's search autocomplete to find the correct housing-market URL."""
    city, state = [x.strip() for x in city_state.split(",", 1)]
    query = f"{city}, {state}"
    encoded = query.replace(" ", "%20").replace(",", "%2C")
    api = f"https://www.redfin.com/stingray/do/location-autocomplete?location={encoded}&v=2&market=false"

    try:
        resp = page.request.get(api, headers={
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://www.redfin.com/",
        })
        raw = resp.text()
        # Strip Redfin's anti-JSON-hijacking prefix
        if "&&" in raw:
            raw = raw[raw.index("&&") + 2:]
        data = json.loads(raw)

        sections = data.get("payload", {}).get("sections", [])
        for section in sections:
            for row in section.get("rows", []):
                url = row.get("url", "")
                rtype = row.get("type", 0)
                name = row.get("name", "")
                # type 2 = city, type 3 = neighborhood — we want city
                if rtype == 2 and url:
                    return f"https://www.redfin.com{url}/housing-market", name
                # fallback: any result with city name match
                if city.lower() in name.lower() and url:
                    return f"https://www.redfin.com{url}/housing-market", name

    except Exception as e:
        print(f"\n    [autocomplete error: {e}]", end=" ")

    return None, None


def scrape_market_page(page, url: str, city_state: str) -> dict:
    """Navigate to Redfin housing market page and extract all key metrics."""
    result = {
        "city": city_state,
        "url": url or "NOT FOUND",
        "compete_score": "N/A",
        "label": "N/A",
        "median_sale": "N/A",
        "dom": "N/A",
        "sale_vs_list": "N/A",
        "data_date": "N/A",
        "hot_homes_dom": "N/A",
        "status": "OK"
    }

    if not url:
        result["status"] = "URL not found via autocomplete"
        return result

    try:
        page.goto(url, wait_until="domcontentloaded", timeout=60000)
        time.sleep(6)

        # ── Extract via JavaScript evaluation ──────────────────────────
        extracted = page.evaluate("""
            () => {
                const getText = (el) => el ? (el.innerText || el.textContent || '').trim() : '';
                const result = {};

                // Compete Score — look for the score number near "Compete Score" text
                const allText = document.body.innerText;
                result.allText = allText.substring(0, 8000);

                // Try to find compete score widget specifically
                const scoreEls = document.querySelectorAll('[class*="compete"], [class*="score"], [class*="Score"]');
                result.scoreEls = Array.from(scoreEls).map(el => getText(el)).filter(t => t.length > 0 && t.length < 200);

                // Get all heading text
                const headings = document.querySelectorAll('h1, h2, h3, h4, .stat-value, .marketStat, [class*="stat"]');
                result.headings = Array.from(headings).map(el => getText(el)).filter(t => t.length > 0 && t.length < 300);

                return result;
            }
        """)

        full_text = extracted.get("allText", "")
        score_els = extracted.get("scoreEls", [])
        headings = extracted.get("headings", [])

        # ── Compete Score ──────────────────────────────────────────────
        # Primary: look in score-specific elements
        score = None
        for el_text in score_els:
            nums = re.findall(r'\b(\d{1,2}|100)\b', el_text)
            for n in nums:
                v = int(n)
                if 1 <= v <= 100:
                    score = v
                    break
            if score:
                break

        # Secondary: look in full page text around "Compete Score"
        if not score:
            # Find the number that appears right before or after "Compete Score"
            patterns = [
                r'(\d{1,3})\s*(?:out of 100)?\s*Redfin Compete Score',
                r'Redfin Compete Score[^\d]*(\d{1,3})',
                r'compete score[^\d]*(\d{1,3})',
                r'(\d{1,3})\s*Very Competitive',
                r'(\d{1,3})\s*Most Competitive',
                r'(\d{1,3})\s*Somewhat Competitive',
                r'(\d{1,3})\s*Not.*Competitive',
            ]
            for pat in patterns:
                m = re.search(pat, full_text, re.IGNORECASE)
                if m:
                    v = int(m.group(1))
                    if 1 <= v <= 100:
                        score = v
                        break

        if score:
            result["compete_score"] = score
            if score >= 85:
                result["label"] = "Most Competitive"
            elif score >= 70:
                result["label"] = "Very Competitive"
            elif score >= 40:
                result["label"] = "Somewhat Competitive"
            else:
                result["label"] = "Not Very Competitive"

        # ── Competition label (cross-check) ───────────────────────────
        for label in ["Most Competitive", "Very Competitive", "Somewhat Competitive", "Not Very Competitive"]:
            if label.lower() in full_text.lower():
                result["label"] = label
                break

        # ── Median Sale Price ──────────────────────────────────────────
        price_patterns = [
            r'Median Sale Price[^\$]*\$([0-9,.]+[KkMm]?)',
            r'median sale price[^\$]*\$([0-9,.]+[KkMm]?)',
            r'\$([0-9,.]+[KkMm]?)\s*Median Sale',
        ]
        for pat in price_patterns:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                result["median_sale"] = "$" + m.group(1)
                break

        # ── Days on Market ─────────────────────────────────────────────
        dom_patterns = [
            r'(\d+)\s*days?\s*(?:on market|to pending)',
            r'go pending in(?:\s*around)?\s*(\d+)\s*days?',
            r'sell in\s*(\d+)\s*days?',
            r'Days on Market[^\d]*(\d+)',
        ]
        for pat in dom_patterns:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                result["dom"] = m.group(1) + " days"
                break

        # ── Sale vs List ───────────────────────────────────────────────
        svl_patterns = [
            r'sell for (?:about )?([\d.]+)%\s*(above|below)',
            r'([\d.]+)%\s*(above|below)\s*list',
            r'sell for (?:around )?list price',
        ]
        for pat in svl_patterns:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                if m.lastindex and m.lastindex >= 2:
                    pct = m.group(1)
                    direction = m.group(2).lower()
                    sign = "+" if direction == "above" else "-"
                    result["sale_vs_list"] = f"{sign}{pct}%"
                else:
                    result["sale_vs_list"] = "~list"
                break

        # ── Hot Homes DOM ──────────────────────────────────────────────
        hot_patterns = [
            r'[Hh]ot homes?.*?pending in(?:\s*around)?\s*(\d+)\s*days?',
            r'[Hh]ot homes?.*?(\d+)\s*days?',
        ]
        for pat in hot_patterns:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                result["hot_homes_dom"] = m.group(1) + " days"
                break

        # ── Data Date ──────────────────────────────────────────────────
        date_m = re.search(
            r'(January|February|March|April|May|June|July|August|'
            r'September|October|November|December)\s+(\d{4})',
            full_text
        )
        if date_m:
            result["data_date"] = f"{date_m.group(1)} {date_m.group(2)}"

    except Exception as e:
        result["status"] = f"ERROR: {e}"

    return result


def months_old(date_str: str) -> int:
    months_map = {"January":1,"February":2,"March":3,"April":4,"May":5,"June":6,
                  "July":7,"August":8,"September":9,"October":10,"November":11,"December":12}
    try:
        parts = date_str.split()
        dt = datetime.date(int(parts[1]), months_map[parts[0]], 1)
        today = datetime.date.today()
        return (today.year - dt.year) * 12 + (today.month - dt.month)
    except:
        return 0


def print_report(results):
    print("\n" + "═" * 72)
    print("   REDFIN COMPETE SCORE — BULK MARKET SNAPSHOT")
    print(f"   Pulled: {datetime.date.today().strftime('%B %d, %Y')} | Source: redfin.com")
    print("═" * 72)

    for r in results:
        age = months_old(r["data_date"]) if r["data_date"] != "N/A" else 0
        age_flag = f" ⚠️  {age} MONTHS OLD" if age > 2 else ""

        print(f"\n📍 {r['city']}")
        print(f"   Compete Score : {r['compete_score']}/100 — {r['label']}")
        print(f"   Median Sale $ : {r['median_sale']}")
        print(f"   Avg DOM       : {r['dom']}")
        print(f"   Hot Homes DOM : {r['hot_homes_dom']}")
        print(f"   Sale vs List  : {r['sale_vs_list']}")
        print(f"   Data Date     : {r['data_date']}{age_flag}")
        if r["status"] != "OK":
            print(f"   ⚠️  STATUS     : {r['status']}")

    print("\n" + "─" * 72)
    print(f"{'City':<25} {'Score':>7} {'DOM':>10} {'Sale/List':>12} {'Date':<16}")
    print("─" * 72)
    for r in results:
        age = months_old(r["data_date"]) if r["data_date"] != "N/A" else 0
        flag = " ⚠️" if age > 2 else ""
        print(f"{r['city']:<25} {str(r['compete_score']):>7} "
              f"{r['dom']:>10} {r['sale_vs_list']:>12} {r['data_date']}{flag}")
    print("═" * 72 + "\n")


def main():
    CITIES = get_cities_from_user()
    results = []

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=False,  # visible browser avoids Redfin bot detection
            args=[
                "--no-sandbox",
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--disable-web-security",
            ]
        )
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            viewport={"width": 1280, "height": 900},
            locale="en-US",
            timezone_id="America/New_York",
        )

        # Block images/fonts to speed up loading
        context.route("**/*.{png,jpg,jpeg,gif,webp,svg,woff,woff2,ttf}", lambda route: route.abort())

        page = context.new_page()
        page.on("console", lambda _: None)
        if HAS_STEALTH:
            stealth_sync(page)

        # Warm up — visit Redfin homepage first to get cookies
        print("  Warming up session...", end=" ", flush=True)
        try:
            page.goto("https://www.redfin.com", wait_until="domcontentloaded", timeout=20000)
            time.sleep(2)
            print("✅")
        except:
            print("⚠️  (continuing anyway)")

        for city_state in CITIES:
            print(f"  → {city_state}...", end=" ", flush=True)

            # Step 1: Get correct URL via autocomplete
            url, matched_name = get_redfin_market_url(page, city_state)
            if url:
                print(f"[{matched_name}] ", end="", flush=True)

            # Step 2: Scrape the page
            data = scrape_market_page(page, url, city_state)
            results.append(data)

            score = data["compete_score"]
            if score != "N/A":
                print(f"✅ Score: {score}")
            else:
                print(f"⚠️  Score not found | Status: {data['status'][:60]}")

            time.sleep(5)

        context.close()
        browser.close()

    print_report(results)

    with open("redfin_compete_scores.json", "w") as f:
        json.dump(results, f, indent=2)
    print("📄 Results saved to redfin_compete_scores.json\n")


if __name__ == "__main__":
    main()
