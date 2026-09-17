package com.supremeaiventures.marketscout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SearchItem {
    final String label;
    final String value;
    final String type;
    final String key;
    final String state;
    final String shard;

    SearchItem(JSONObject object) {
        label = object.optString("label", "");
        value = object.optString("value", "");
        type = object.optString("type", "");
        key = object.optString("key", "");
        state = object.optString("state", "");
        shard = object.optString("shard", "");
    }

    String identity() {
        return type + "|" + key;
    }
}

final class BulkParseResult {
    final List<SearchItem> items;
    final List<String> unmatched;

    BulkParseResult(List<SearchItem> items, List<String> unmatched) {
        this.items = items;
        this.unmatched = unmatched;
    }
}

final class ManifestSnapshot {
    final int cityCount;
    final int zipCount;
    final int countyCount;
    final int searchIndexCount;
    final String latestSourceUpdatedAt;

    private ManifestSnapshot(
            int cityCount,
            int zipCount,
            int countyCount,
            int searchIndexCount,
            String latestSourceUpdatedAt
    ) {
        this.cityCount = cityCount;
        this.zipCount = zipCount;
        this.countyCount = countyCount;
        this.searchIndexCount = searchIndexCount;
        this.latestSourceUpdatedAt = latestSourceUpdatedAt;
    }

    static ManifestSnapshot fromJson(JSONObject object) {
        JSONObject datasets = object.optJSONObject("datasets");
        return new ManifestSnapshot(
                datasets == null ? 0 : datasets.optInt("city", 0),
                datasets == null ? 0 : datasets.optInt("zip", 0),
                datasets == null ? 0 : datasets.optInt("county", 0),
                object.optInt("search_index_count", 0),
                nullableString(object, "latest_source_updated_at")
        );
    }

    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}

final class MarketResult {
    final String city;
    final String type;
    final String status;
    final String period;
    final String medianSale;
    final Double monthsSupply;
    final String supplyLabel;
    final Double parRatio;
    final Integer parPending;
    final Integer parTotal;
    final String parLabel;
    final Integer medianDom;
    final String saleToList;
    final String soldAboveList;
    final String priceDrops;
    final String offMarketTwoWeeks;
    final Integer homesSold;
    final Integer newListings;
    final String redfinUrl;
    String householdIncome;

    private MarketResult(
            String city,
            String type,
            String status,
            String period,
            String medianSale,
            Double monthsSupply,
            String supplyLabel,
            Double parRatio,
            Integer parPending,
            Integer parTotal,
            String parLabel,
            Integer medianDom,
            String saleToList,
            String soldAboveList,
            String priceDrops,
            String offMarketTwoWeeks,
            Integer homesSold,
            Integer newListings,
            String redfinUrl
    ) {
        this.city = city;
        this.type = type;
        this.status = status;
        this.period = period;
        this.medianSale = medianSale;
        this.monthsSupply = monthsSupply;
        this.supplyLabel = supplyLabel;
        this.parRatio = parRatio;
        this.parPending = parPending;
        this.parTotal = parTotal;
        this.parLabel = parLabel;
        this.medianDom = medianDom;
        this.saleToList = saleToList;
        this.soldAboveList = soldAboveList;
        this.priceDrops = priceDrops;
        this.offMarketTwoWeeks = offMarketTwoWeeks;
        this.homesSold = homesSold;
        this.newListings = newListings;
        this.redfinUrl = redfinUrl;
    }

    static MarketResult fromJson(JSONObject object, SearchItem item) {
        return new MarketResult(
                item.label,
                item.type,
                "OK",
                nullableString(object, "period"),
                nullableString(object, "median_sale"),
                nullableDouble(object, "months_supply"),
                nullableString(object, "supply_label"),
                nullableDouble(object, "par_ratio"),
                nullableInt(object, "par_pending"),
                nullableInt(object, "par_total"),
                nullableString(object, "par_label"),
                nullableInt(object, "median_dom"),
                nullableString(object, "sale_to_list"),
                nullableString(object, "sold_above_list"),
                nullableString(object, "price_drops"),
                nullableString(object, "off_market_2wk"),
                nullableInt(object, "homes_sold"),
                nullableInt(object, "new_listings"),
                nullableString(object, "redfin_url")
        );
    }

    static MarketResult missing(SearchItem item, String status) {
        return new MarketResult(
                item.label,
                item.type,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    String marketTypeLabel() {
        if (supplyLabel != null && !supplyLabel.isEmpty()) return supplyLabel;
        if (parLabel != null && !parLabel.isEmpty()) return parLabel;
        return type;
    }

    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        String value = object.optString(key, "").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static Double nullableDouble(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        try {
            double value = object.getDouble(key);
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer nullableInt(JSONObject object, String key) {
        Double value = nullableDouble(object, key);
        return value == null ? null : value.intValue();
    }
}
