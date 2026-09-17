package com.supremeaiventures.marketscout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MarketDataRepository {
    private static final String DATA_BASE_URL = "https://market-scout-anl.pages.dev/data/";
    private static final String CENSUS_BASE_URL = "https://api.census.gov/data/2022/acs/acs5?get=B19013_001E,NAME&";

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, JSONObject> shardCache = new HashMap<>();
    private final Map<String, String> stateFips = buildStateFips();

    interface BootstrapCallback {
        void onSuccess(ManifestSnapshot manifest, MarketSearchIndex searchIndex);

        void onError(Exception error);
    }

    interface LookupCallback {
        void onSuccess(MarketResult result);

        void onError(Exception error);
    }

    void loadBootstrap(BootstrapCallback callback) {
        executor.execute(() -> {
            try {
                ManifestSnapshot manifest = ManifestSnapshot.fromJson(getJson(DATA_BASE_URL + "manifest.json"));
                MarketSearchIndex searchIndex = MarketSearchIndex.fromJson(
                        new JSONArray(getText(DATA_BASE_URL + "search-index.json"))
                );
                mainHandler.post(() -> callback.onSuccess(manifest, searchIndex));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    void lookup(SearchItem item, LookupCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject shard = loadShard(item.shard);
                JSONObject items = shard.optJSONObject("items");
                JSONObject raw = items == null ? null : items.optJSONObject(item.key);
                if (raw == null) {
                    mainHandler.post(() -> callback.onSuccess(null));
                    return;
                }

                MarketResult result = MarketResult.fromJson(raw, item);
                result.householdIncome = fetchHouseholdIncome(item);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private JSONObject loadShard(String shardPath) throws Exception {
        synchronized (shardCache) {
            JSONObject cached = shardCache.get(shardPath);
            if (cached != null) return cached;
        }
        JSONObject loaded = getJson(DATA_BASE_URL + shardPath);
        synchronized (shardCache) {
            shardCache.put(shardPath, loaded);
        }
        return loaded;
    }

    private String fetchHouseholdIncome(SearchItem item) {
        try {
            String url;
            if ("zip".equals(item.type)) {
                url = CENSUS_BASE_URL + "for=zip%20code%20tabulation%20area:" + encode(item.value);
            } else {
                String[] parts = item.label.split(",", 2);
                if (parts.length < 2) return null;
                String state = parts[1].trim().toUpperCase(Locale.US);
                String fips = stateFips.get(state);
                if (fips == null) return null;
                String geography = "county".equals(item.type) ? "county:*" : "place:*";
                url = CENSUS_BASE_URL + "for=" + geography + "&in=state:" + fips;
                String desired = parts[0].trim().toLowerCase(Locale.US);
                JSONArray data = new JSONArray(getText(url));
                for (int i = 1; i < data.length(); i++) {
                    JSONArray row = data.optJSONArray(i);
                    if (row == null || row.length() < 2) continue;
                    String name = row.optString(1, "").toLowerCase(Locale.US);
                    String normalizedDesired = desired.replace(" county", "").trim();
                    if (name.contains(normalizedDesired)) {
                        return formatIncome(row.optString(0, ""));
                    }
                }
                return null;
            }

            JSONArray data = new JSONArray(getText(url));
            if (data.length() > 1) {
                JSONArray row = data.optJSONArray(1);
                return row == null ? null : formatIncome(row.optString(0, ""));
            }
        } catch (Exception ignored) {
            // Census enrichment is optional; market metrics should still render if it fails.
        }
        return null;
    }

    private static String formatIncome(String raw) {
        try {
            int income = Integer.parseInt(raw);
            return income > 0 ? String.format(Locale.US, "$%,d", income) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static JSONObject getJson(String url) throws Exception {
        return new JSONObject(getText(url));
    }

    private static String getText(String urlString) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MarketScoutAndroid/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Request failed with HTTP " + responseCode);
            }

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
                return output.toString();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Map<String, String> buildStateFips() {
        Map<String, String> values = new HashMap<>();
        String[][] entries = {
                {"AL", "01"}, {"AK", "02"}, {"AZ", "04"}, {"AR", "05"}, {"CA", "06"},
                {"CO", "08"}, {"CT", "09"}, {"DE", "10"}, {"FL", "12"}, {"GA", "13"},
                {"HI", "15"}, {"ID", "16"}, {"IL", "17"}, {"IN", "18"}, {"IA", "19"},
                {"KS", "20"}, {"KY", "21"}, {"LA", "22"}, {"ME", "23"}, {"MD", "24"},
                {"MA", "25"}, {"MI", "26"}, {"MN", "27"}, {"MS", "28"}, {"MO", "29"},
                {"MT", "30"}, {"NE", "31"}, {"NV", "32"}, {"NH", "33"}, {"NJ", "34"},
                {"NM", "35"}, {"NY", "36"}, {"NC", "37"}, {"ND", "38"}, {"OH", "39"},
                {"OK", "40"}, {"OR", "41"}, {"PA", "42"}, {"RI", "44"}, {"SC", "45"},
                {"SD", "46"}, {"TN", "47"}, {"TX", "48"}, {"UT", "49"}, {"VT", "50"},
                {"VA", "51"}, {"WA", "53"}, {"WV", "54"}, {"WI", "55"}, {"WY", "56"}
        };
        for (String[] entry : entries) values.put(entry[0], entry[1]);
        return values;
    }
}
