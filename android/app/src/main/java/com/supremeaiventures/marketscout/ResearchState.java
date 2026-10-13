package com.supremeaiventures.marketscout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ResearchState {
    final List<SearchItem> selections = new ArrayList<>();
    final List<MarketResult> results = new ArrayList<>();
    final Set<String> favorites = new LinkedHashSet<>();
    final Map<String, String> notes = new LinkedHashMap<>();
    final Map<String, JSONObject> savedLists = new LinkedHashMap<>();
    final Set<String> compared = new LinkedHashSet<>();
    MarketSort sort = MarketSort.DEFAULT;
    boolean descending;
    boolean favoritesOnly;
    Double maxSupply;
    Integer maxDom;
    String searchDraft = "";
    String bulkDraft = "";
    String sourceDate = "";
    String activeList = "";
    String scanMessage = "";
    String pendingCsv = "";
    int scrollY;

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject().put("version", 1).put("view", viewJson())
                .put("search_draft", searchDraft).put("bulk_draft", bulkDraft)
                .put("source_date", sourceDate).put("active_list", activeList)
                .put("scan_message", scanMessage).put("scroll_y", scrollY).put("pending_csv", pendingCsv)
                .put("favorites", new JSONArray(favorites)).put("compared", new JSONArray(compared))
                .put("notes", new JSONObject(notes)).put("saved_lists", new JSONObject(savedLists));
        JSONArray records = new JSONArray();
        for (MarketResult result : results) records.put(result.toJson());
        return object.put("results", records);
    }

    static ResearchState fromJson(JSONObject object) {
        ResearchState state = new ResearchState();
        state.restoreView(object.optJSONObject("view"));
        state.searchDraft = object.optString("search_draft", "");
        state.bulkDraft = object.optString("bulk_draft", "");
        state.sourceDate = object.optString("source_date", "");
        state.activeList = object.optString("active_list", "");
        state.scanMessage = object.optString("scan_message", "");
        state.pendingCsv = object.optString("pending_csv", "");
        state.scrollY = Math.max(0, object.optInt("scroll_y"));
        readStrings(object.optJSONArray("favorites"), state.favorites);
        readStrings(object.optJSONArray("compared"), state.compared);
        JSONObject notes = object.optJSONObject("notes");
        if (notes != null) {
            java.util.Iterator<String> keys = notes.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                state.notes.put(key, notes.optString(key));
            }
        }
        JSONObject lists = object.optJSONObject("saved_lists");
        if (lists != null) {
            java.util.Iterator<String> keys = lists.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject list = lists.optJSONObject(key);
                if (list != null) state.savedLists.put(key, list);
            }
        }
        JSONArray records = object.optJSONArray("results");
        if (records != null) for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && record.optJSONObject("item") != null) {
                state.results.add(MarketResult.fromSavedJson(record));
            }
        }
        return state;
    }

    void saveList(String name) throws JSONException {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 60) throw new IllegalArgumentException("Use a list name of 1 to 60 characters.");
        if (selections.isEmpty()) throw new IllegalArgumentException("Add markets before saving a list.");
        savedLists.put(trimmed, viewJson());
        activeList = trimmed;
    }

    void openList(String name) {
        JSONObject saved = savedLists.get(name);
        if (saved == null) return;
        restoreView(saved);
        activeList = name;
        results.clear();
        compared.clear();
        scanMessage = "";
        scrollY = 0;
    }

    boolean includes(MarketResult result) {
        if (favoritesOnly && !favorites.contains(result.identity())) return false;
        if (maxSupply != null && (result.monthsSupply == null || result.monthsSupply > maxSupply)) return false;
        return maxDom == null || result.medianDom != null && result.medianDom <= maxDom;
    }

    private JSONObject viewJson() throws JSONException {
        JSONArray markets = new JSONArray();
        for (SearchItem item : selections) markets.put(item.toJson());
        return new JSONObject().put("markets", markets).put("sort", sort.name())
                .put("descending", descending).put("favorites_only", favoritesOnly)
                .put("max_supply", maxSupply).put("max_dom", maxDom);
    }

    private void restoreView(JSONObject view) {
        if (view == null) return;
        selections.clear();
        JSONArray markets = view.optJSONArray("markets");
        Set<String> identities = new LinkedHashSet<>();
        if (markets != null) for (int i = 0; i < markets.length(); i++) {
            JSONObject market = markets.optJSONObject(i);
            if (market == null) continue;
            SearchItem item = new SearchItem(market);
            if (!item.key.isEmpty() && identities.add(item.identity())) selections.add(item);
        }
        try { sort = MarketSort.valueOf(view.optString("sort", "DEFAULT")); }
        catch (IllegalArgumentException ignored) { sort = MarketSort.DEFAULT; }
        descending = view.optBoolean("descending");
        favoritesOnly = view.optBoolean("favorites_only");
        maxSupply = view.has("max_supply") ? MarketSort.number(view.optString("max_supply")) : null;
        maxDom = view.has("max_dom") ? view.optInt("max_dom") : null;
    }

    private static void readStrings(JSONArray array, Set<String> destination) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) destination.add(value);
        }
    }
}
