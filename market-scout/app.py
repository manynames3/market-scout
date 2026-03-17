#!/usr/bin/env python3
"""
Market Scout — Flask Backend
Serves the UI and runs the Redfin scraper on demand.
"""

from flask import Flask, send_file, request, jsonify, Response
import os
import json
import re
import time
import datetime
import threading
import queue

app = Flask(__name__)

# ── Scraper logic (inline from scraper.py) ──────────────────────────────────

def get_redfin_market_url(page, city_state):
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
        if "&&" in raw:
            raw = raw[raw.index("&&") + 2:]
        data = json.loads(raw)
        sections = data.get("payload", {}).get("sections", [])
        for section in sections:
            for row in section.get("rows", []):
                url = row.get("url", "")
                rtype = row.get("type", 0)
                name = row.get("name", "")
                if rtype == 2 and url:
                    return f"https://www.redfin.com{url}/housing-market", name
                if city.lower() in name.lower() and url:
                    return f"https://www.redfin.com{url}/housing-market", name
    except Exception as e:
        pass
    return None, None


def scrape_market_page(page, url, city_state):
    result = {
        "city": city_state,
        "url": url or "NOT FOUND",
        "compete_score": None,
        "label": "N/A",
        "median_sale": "N/A",
        "dom": "N/A",
        "sale_vs_list": "N/A",
        "data_date": "N/A",
        "hot_homes_dom": "N/A",
        "status": "OK",
        "par_ratio": None,
        "par_pending": None,
        "par_active": None,
        "par_label": "N/A"
    }

    if not url:
        result["status"] = "City URL not found"
        return result

    try:
        page.goto(url, wait_until="domcontentloaded", timeout=60000)
        time.sleep(6)

        extracted = page.evaluate("""
            () => {
                const allText = document.body.innerText;
                return { allText: allText.substring(0, 8000) };
            }
        """)

        full_text = extracted.get("allText", "")

        # Compete Score — require 2+ digits to avoid single-digit false matches
        score = None
        patterns = [
            r'(\d{2,3})\s*(?:out of 100)?\s*Redfin Compete Score',
            r'Redfin Compete Score[^\d]*(\d{2,3})',
            r'compete score[^\d]*(\d{2,3})',
            r'(\d{2,3})\s*Very Competitive',
            r'(\d{2,3})\s*Most Competitive',
            r'(\d{2,3})\s*Somewhat Competitive',
            r'(\d{2,3})\s*Not.*Competitive',
        ]
        for pat in patterns:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                v = int(m.group(1))
                if 10 <= v <= 100:
                    score = v
                    break
        # Fallback: find any 2-digit number adjacent to competitive label
        if not score:
            m = re.search(r'(\d{2,3})\s*/\s*100', full_text)
            if m:
                v = int(m.group(1))
                if 10 <= v <= 100:
                    score = v

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

        for label in ["Most Competitive", "Very Competitive", "Somewhat Competitive", "Not Very Competitive"]:
            if label.lower() in full_text.lower():
                result["label"] = label
                break

        # Median Sale Price
        for pat in [r'Median Sale Price[^\$]*\$([0-9,.]+[KkMm]?)', r'\$([0-9,.]+[KkMm]?)\s*Median Sale']:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                result["median_sale"] = "$" + m.group(1)
                break

        # DOM
        for pat in [r'(\d+)\s*days?\s*(?:on market|to pending)', r'go pending in(?:\s*around)?\s*(\d+)\s*days?', r'sell in\s*(\d+)\s*days?']:
            m = re.search(pat, full_text, re.IGNORECASE)
            if m:
                result["dom"] = m.group(1) + " days"
                break

        # Sale vs List
        m = re.search(r'sell for (?:about )?([\d.]+)%\s*(above|below)', full_text, re.IGNORECASE)
        if m:
            sign = "+" if m.group(2).lower() == "above" else "-"
            result["sale_vs_list"] = f"{sign}{m.group(1)}%"
        elif re.search(r'sell for (?:around )?list price', full_text, re.IGNORECASE):
            result["sale_vs_list"] = "~list"

        # Hot Homes DOM
        m = re.search(r'[Hh]ot homes?.*?pending in(?:\s*around)?\s*(\d+)\s*days?', full_text)
        if m:
            result["hot_homes_dom"] = m.group(1) + " days"

        # Data Date
        m = re.search(r'(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{4})', full_text)
        if m:
            result["data_date"] = f"{m.group(1)} {m.group(2)}"

        # ── PAR Ratio (Pending/Active) ─────────────────────────────
        # Look for pending and active listing counts in page text
        pending_match = re.search(r'(\d+)\s*(?:homes?)?\s*(?:are\s*)?pending', full_text, re.IGNORECASE)
        active_match  = re.search(r'(\d+)\s*(?:homes?)?\s*(?:are\s*)?(?:for sale|active|available)', full_text, re.IGNORECASE)

        if pending_match and active_match:
            pending = int(pending_match.group(1))
            active  = int(active_match.group(1))
            total   = active + pending
            if total > 0:
                par = round((pending / total) * 100, 1)
                result["par_ratio"] = par
                result["par_pending"] = pending
                result["par_active"] = active
                if par >= 40:
                    result["par_label"] = "Seller's Market"
                elif par >= 20:
                    result["par_label"] = "Balanced"
                else:
                    result["par_label"] = "Buyer's Market"
        
    except Exception as e:
        result["status"] = f"Error: {str(e)[:120]}"

    return result


def months_old(date_str):
    months_map = {"January":1,"February":2,"March":3,"April":4,"May":5,"June":6,
                  "July":7,"August":8,"September":9,"October":10,"November":11,"December":12}
    try:
        parts = date_str.split()
        dt = datetime.date(int(parts[1]), months_map[parts[0]], 1)
        today = datetime.date.today()
        return (today.year - dt.year) * 12 + (today.month - dt.month)
    except:
        return 0


def run_scraper(cities, result_queue):
    """Run scraper in a thread, push results via queue for SSE streaming."""
    try:
        from playwright.sync_api import sync_playwright
        try:
            from playwright_stealth import stealth_sync
            HAS_STEALTH = True
        except ImportError:
            HAS_STEALTH = False

        with sync_playwright() as p:
            browser = p.chromium.launch(
                headless=False,
                args=["--no-sandbox", "--disable-blink-features=AutomationControlled"]
            )
            context = browser.new_context(
                user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                viewport={"width": 1280, "height": 900},
                locale="en-US",
            )
            context.route("**/*.{png,jpg,jpeg,gif,webp,woff,woff2,ttf}", lambda route: route.abort())

            page = context.new_page()
            if HAS_STEALTH:
                stealth_sync(page)

            # Warmup
            result_queue.put({"type": "status", "message": "Warming up browser session..."})
            try:
                page.goto("https://www.redfin.com", wait_until="domcontentloaded", timeout=20000)
                time.sleep(2)
            except:
                pass

            results = []
            for city_state in cities:
                result_queue.put({"type": "progress", "message": f"Fetching {city_state}..."})

                url, matched_name = get_redfin_market_url(page, city_state)
                data = scrape_market_page(page, url, city_state)

                # Add staleness flag
                if data["data_date"] != "N/A":
                    age = months_old(data["data_date"])
                    data["stale"] = age > 2
                    data["months_old"] = age
                else:
                    data["stale"] = False
                    data["months_old"] = 0

                results.append(data)
                result_queue.put({"type": "result", "data": data})
                time.sleep(5)

            context.close()
            browser.close()

        result_queue.put({"type": "done", "results": results})

    except Exception as e:
        result_queue.put({"type": "error", "message": str(e)})


# ── Routes ──────────────────────────────────────────────────────────────────

@app.route("/")
def index():
    return send_file(os.path.join(os.path.dirname(__file__), "templates", "index.html"))


@app.route("/scrape")
def scrape():
    cities_raw = request.args.get("cities", "")
    cities = [c.strip() for c in cities_raw.split("|") if c.strip() and "," in c.strip()]

    if not cities:
        return jsonify({"error": "No valid cities provided"}), 400

    def generate():
        q = queue.Queue()
        thread = threading.Thread(target=run_scraper, args=(cities, q), daemon=True)
        thread.start()

        while True:
            try:
                msg = q.get(timeout=120)
                yield f"data: {json.dumps(msg)}\n\n"
                if msg["type"] in ("done", "error"):
                    break
            except queue.Empty:
                yield f"data: {json.dumps({'type': 'error', 'message': 'Timeout'})}\n\n"
                break

    return Response(generate(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


if __name__ == "__main__":
    print("\n" + "═"*50)
    print("   MARKET SCOUT — Starting up")
    print("   Open: http://localhost:5000")
    print("═"*50 + "\n")
    app.run(debug=False, port=5000, threaded=True)
