package com.supremeaiventures.marketscout;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class MarketDataRepository {
    private static final String DATA_BASE_URL = "https://market-scout-anl.pages.dev/data/";
    private static final String CENSUS_BASE_URL = "https://api.census.gov/data/2022/acs/acs5?get=B19013_001E,NAME&";
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SnapshotCache cache;
    private final Map<String, JSONObject> memory = new HashMap<>();
    private final Set<Request> requests = new HashSet<>();
    private volatile boolean closed;
    private String snapshot = "";

    MarketDataRepository(Context context) {
        cache = new SnapshotCache(new java.io.File(context.getFilesDir(), "market_cache"));
    }

    static final class Request {
        private volatile boolean canceled;
        private volatile HttpURLConnection connection;
        private volatile Future<?> future;

        void cancel() {
            canceled = true;
            if (connection != null) connection.disconnect();
            if (future != null) future.cancel(true);
        }
    }

    interface BootstrapCallback {
        void onSuccess(ManifestSnapshot manifest, MarketSearchIndex searchIndex, boolean cached);
        void onError(Exception error);
    }

    interface LookupCallback {
        void onSuccess(MarketResult result);
        void onError(Exception error);
    }

    Request loadBootstrap(BootstrapCallback callback) {
        return submit(request -> {
            JSONObject saved = cache.read("bootstrap", null);
            boolean restored = false;
            if (saved != null) {
                try {
                    deliverBootstrap(saved, true, request, callback);
                    restored = true;
                } catch (Exception ignored) { }
            }
            try {
                JSONObject bundle = new JSONObject()
                        .put("manifest", new JSONObject(getText(DATA_BASE_URL + "manifest.json", request)))
                        .put("index", new JSONArray(getText(DATA_BASE_URL + "search-index.json", request)));
                deliverBootstrap(bundle, false, request, callback);
                cache.write("bootstrap", version(bundle.getJSONObject("manifest")), bundle);
            } catch (Exception error) {
                if (!restored) post(request, () -> callback.onError(error));
            }
        });
    }

    private static String version(JSONObject manifest) {
        return manifest.optString("generated_at", manifest.optString("latest_source_updated_at"));
    }

    private void deliverBootstrap(JSONObject bundle, boolean cached, Request request, BootstrapCallback callback) throws Exception {
        JSONObject rawManifest = bundle.getJSONObject("manifest");
        JSONArray index = bundle.getJSONArray("index");
        if (index.length() == 0) throw new IOException("Empty market index");
        MarketSearchIndex searchIndex = MarketSearchIndex.fromJson(index);
        ManifestSnapshot manifest = ManifestSnapshot.fromJson(rawManifest);
        String version = version(rawManifest);
        post(request, () -> {
            snapshot = version;
            callback.onSuccess(manifest, searchIndex, cached);
        });
    }

    Request lookup(SearchItem item, LookupCallback callback) {
        String version = snapshot;
        return submit(request -> {
            try {
                if (!item.shard.matches("markets/(city|zip|county)/[A-Za-z0-9_-]+\\.json")) {
                    throw new IOException("Invalid market shard");
                }
                String cacheKey = version + "/" + item.shard;
                JSONObject shard;
                boolean cached = false;
                synchronized (memory) { shard = memory.get(cacheKey); }
                if (shard == null) {
                    try {
                        shard = new JSONObject(getText(DATA_BASE_URL + item.shard, request));
                        if (shard.optJSONObject("items") == null) throw new IOException("Invalid market data");
                        cache.write(item.shard, version, shard);
                    } catch (Exception error) {
                        shard = cache.read(item.shard, version);
                        if (shard == null) throw error;
                        cached = true;
                    }
                    if (!cached) synchronized (memory) { memory.put(cacheKey, shard); }
                }
                JSONObject raw = shard.getJSONObject("items").optJSONObject(item.key);
                if (raw == null) { post(request, () -> callback.onSuccess(null)); return; }
                MarketResult result = MarketResult.fromJson(raw, item);
                result.cached = cached;
                result.householdIncome = fetchIncome(item, request);
                post(request, () -> callback.onSuccess(result));
            } catch (Exception error) { post(request, () -> callback.onError(error)); }
        });
    }

    private interface Work { void run(Request request); }

    private Request submit(Work work) {
        Request request = new Request();
        synchronized (requests) {
            if (closed) { request.cancel(); return request; }
            requests.add(request);
            request.future = executor.submit(() -> {
                try { work.run(request); }
                finally { synchronized (requests) { requests.remove(request); } }
            });
        }
        return request;
    }

    private void post(Request request, Runnable callback) {
        if (!closed && !request.canceled) handler.post(() -> {
            if (!closed && !request.canceled) callback.run();
        });
    }

    void shutdown() {
        synchronized (requests) {
            closed = true;
            for (Request request : requests) request.cancel();
            requests.clear();
        }
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private String fetchIncome(SearchItem item, Request request) {
        try {
            String url;
            if ("zip".equals(item.type)) {
                if (!item.value.matches("[0-9]{5}")) return null;
                url = CENSUS_BASE_URL + "for=zip%20code%20tabulation%20area:" + item.value;
            } else {
                String fips = stateFips(item.state);
                if (fips == null) return null;
                url = CENSUS_BASE_URL + "for=" + ("county".equals(item.type) ? "county:*" : "place:*") + "&in=state:" + fips;
            }
            JSONObject saved;
            synchronized (memory) { saved = memory.get(url); }
            if (saved == null) saved = cache.read(url, "acs-2022");
            if (saved == null) {
                saved = new JSONObject().put("rows", new JSONArray(getText(url, request)));
                cache.write(url, "acs-2022", saved);
            }
            synchronized (memory) { memory.put(url, saved); }
            String found = null;
            JSONArray rows = saved.getJSONArray("rows");
            for (int i = 1; i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null || row.length() < 2) continue;
                if ("zip".equals(item.type) || sameGeography(item.label, row.optString(1))) {
                    if (found != null) return null;
                    int income = row.optInt(0, -1);
                    if (income > 0) found = String.format(Locale.US, "$%,d", income);
                }
            }
            return found;
        } catch (Exception ignored) { return null; }
    }

    static boolean sameGeography(String label, String censusName) {
        String desired = placeName(label);
        String actual = placeName(censusName);
        return desired.equals(actual) || desired.equals(actual
                .replaceFirst("\\s+(city|town|village|borough|cdp|municipality)$", ""));
    }

    private static String placeName(String value) {
        return value.split(",", 2)[0].trim().toLowerCase(Locale.US)
                .replaceAll("\\s+", " ");
    }

    private static String stateFips(String state) {
        String[] codes = {"AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN","IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VT","VA","WA","WV","WI","WY","DC"};
        String[] fips = {"01","02","04","05","06","08","09","10","12","13","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","44","45","46","47","48","49","50","51","53","54","55","56","11"};
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(state)) return fips[i];
        return null;
    }

    private static String getText(String address, Request request) throws IOException {
        if (request.canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        request.connection = connection;
        try {
            if (request.canceled) throw new IOException("Canceled");
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (java.io.InputStream input = connection.getInputStream();
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (request.canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
                    if (output.size() + count > 16 * 1024 * 1024) throw new IOException("Dataset exceeds cache limit");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            request.connection = null;
            connection.disconnect();
        }
    }
}
