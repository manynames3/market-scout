package com.supremeaiventures.marketscout;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class MarketSearchIndex {
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final List<SearchItem> items;
    private final Map<String, SearchItem> exactLookup = new HashMap<>();
    private final Map<String, SearchItem> phraseLookup = new HashMap<>();
    private int maxPhraseTokens = 1;

    private MarketSearchIndex(List<SearchItem> items) {
        this.items = items;
        for (SearchItem item : items) register(item);
    }

    static MarketSearchIndex fromJson(JSONArray array) {
        List<SearchItem> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            items.add(new SearchItem(array.optJSONObject(i)));
        }
        return new MarketSearchIndex(items);
    }

    private void register(SearchItem item) {
        putExact(item.label, item);
        putExact(item.value, item);
        putExact(item.key, item);

        putPhrase(item.label, item);
        putPhrase(item.value, item);
        putPhrase(item.key, item);
    }

    private void putExact(String value, SearchItem item) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) exactLookup.put(normalized, item);
    }

    private void putPhrase(String value, SearchItem item) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return;
        phraseLookup.putIfAbsent(normalized, item);
        maxPhraseTokens = Math.max(maxPhraseTokens, tokenize(normalized).size());
    }

    SearchItem resolve(String value) {
        if (value == null) return null;
        return exactLookup.get(normalize(value));
    }

    List<String> suggestionLabels(String query, int maxResults) {
        String normalizedQuery = String.valueOf(query == null ? "" : query).trim().toLowerCase(Locale.US);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        if (normalizedQuery.length() < 2) return starts;

        for (SearchItem item : items) {
            String label = item.label.toLowerCase(Locale.US);
            String value = item.value.toLowerCase(Locale.US);
            if (label.startsWith(normalizedQuery) || value.startsWith(normalizedQuery)) {
                starts.add(item.label);
            } else if (label.contains(normalizedQuery) || value.contains(normalizedQuery)) {
                contains.add(item.label);
            }
            if (starts.size() + contains.size() >= maxResults * 2) break;
        }

        List<String> output = new ArrayList<>();
        for (String label : starts) {
            if (!output.contains(label)) output.add(label);
            if (output.size() == maxResults) return output;
        }
        for (String label : contains) {
            if (!output.contains(label)) output.add(label);
            if (output.size() == maxResults) return output;
        }
        return output;
    }

    BulkParseResult parseBulk(String raw) {
        List<String> tokens = tokenize(raw == null ? "" : raw);
        List<SearchItem> matches = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;

        while (index < tokens.size()) {
            SearchItem best = null;
            int bestEnd = index;
            StringBuilder phrase = new StringBuilder();
            int endLimit = Math.min(tokens.size(), index + maxPhraseTokens);
            for (int end = index; end < endLimit; end++) {
                if (phrase.length() > 0) phrase.append(' ');
                phrase.append(tokens.get(end));
                SearchItem candidate = phraseLookup.get(phrase.toString());
                if (candidate != null) {
                    best = candidate;
                    bestEnd = end + 1;
                }
            }

            if (best != null) {
                if (seen.add(best.identity())) matches.add(best);
                index = bestEnd;
            } else {
                unmatched.add(tokens.get(index));
                index++;
            }
        }
        return new BulkParseResult(matches, unmatched);
    }

    private static String normalize(String value) {
        return String.join(" ", tokenize(value == null ? "" : value));
    }

    private static List<String> tokenize(String value) {
        String normalized = NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.US)).replaceAll(" ").trim();
        List<String> tokens = new ArrayList<>();
        if (normalized.isEmpty()) return tokens;
        for (String token : normalized.split("\\s+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }
}
