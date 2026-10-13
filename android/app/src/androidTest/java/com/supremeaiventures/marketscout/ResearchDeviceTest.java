package com.supremeaiventures.marketscout;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

public class ResearchDeviceTest extends InstrumentationTestCase {
    private Context context;
    private String originalResearch;
    private String originalRecovery;
    private Activity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("market_research", 0);
        originalResearch = preferences.getString("research", null);
        originalRecovery = preferences.getString("recovery", null);
        preferences.edit().remove("research").commit();
    }

    @Override protected void tearDown() throws Exception {
        if (activity != null) finishAndWait(activity);
        context.getSharedPreferences("market_research", 0).edit().putString("research", originalResearch)
                .putString("recovery", originalRecovery).commit();
        super.tearDown();
    }

    private void finishAndWait(Activity target) throws Exception {
        getInstrumentation().runOnMainSync(target::finish);
        java.util.concurrent.atomic.AtomicBoolean destroyed = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        while (!destroyed.get() && android.os.SystemClock.uptimeMillis() < deadline) {
            getInstrumentation().runOnMainSync(() -> destroyed.set(target.isDestroyed()));
            if (!destroyed.get()) Thread.sleep(25);
        }
        assertTrue("Activity must finish before inspecting persistent state", destroyed.get());
    }

    private SearchItem item() throws Exception {
        return new SearchItem(new JSONObject().put("label", "Atlanta, GA").put("value", "Atlanta, GA")
                .put("type", "city").put("key", "atlanta|GA").put("state", "GA").put("shard", "markets/city/GA.json"));
    }

    public void testResearchSurvivesNewStoreInstance() throws Exception {
        ResearchState state = new ResearchState();
        SearchItem market = item();
        state.selections.add(market);
        state.favorites.add(market.identity());
        state.notes.put(market.identity(), "Call after reviewing supply.");
        state.saveList("Field research");
        state.searchDraft = "Philadelphia";
        new ResearchStore(context).save(state);
        ResearchState restored = new ResearchStore(context).load();
        assertEquals("Philadelphia", restored.searchDraft);
        assertEquals(1, restored.selections.size());
        assertTrue(restored.savedLists.containsKey("Field research"));
        assertEquals(state.notes, restored.notes);
        assertEquals(state.favorites, restored.favorites);
    }

    public void testDraftAndResultsSurviveActivityRecreation() throws Exception {
        ResearchState state = new ResearchState();
        state.selections.add(item());
        state.results.add(MarketResult.fromJson(new JSONObject().put("median_sale", "$446,000"), item()));
        state.searchDraft = "Brooklyn";
        state.bulkDraft = "Unfinished market list";
        new ResearchStore(context).save(state);
        activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
        getInstrumentation().runOnMainSync(() -> activity.recreate());
        Activity recreated = monitor.waitForActivityWithTimeout(5_000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull("Activity recreation must complete", recreated);
        activity = recreated;
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> {
            assertEquals("Brooklyn", ((EditText) activity.findViewById(R.id.market_input)).getText().toString());
            assertEquals("Unfinished market list", ((EditText) activity.findViewById(R.id.bulk_input)).getText().toString());
            assertEquals(1, ((android.widget.LinearLayout) activity.findViewById(R.id.results_list)).getChildCount());
        });
        ResearchState restored = new ResearchStore(context).load();
        assertEquals("Brooklyn", restored.searchDraft);
        assertEquals("Unfinished market list", restored.bulkDraft);
        assertEquals(1, restored.results.size());
        assertEquals("$446,000", restored.results.get(0).medianSale);
    }

    public void testDraftEditsPersistWhenActivityStops() throws Exception {
        activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(() -> {
            ((EditText) activity.findViewById(R.id.bulk_input)).setText("Brooklyn Park, MN Mechanicsville, MD");
        });
        finishAndWait(activity);
        assertEquals("Brooklyn Park, MN Mechanicsville, MD", new ResearchStore(context).load().bulkDraft);
    }

    public void testOfflineBootstrapLoadsFromDeviceCache() throws Exception {
        File temporary = new File(context.getCacheDir(), "cache-device-test");
        SnapshotCache cache = new SnapshotCache(temporary);
        cache.write("bootstrap", "fixture", new JSONObject().put("manifest", new JSONObject().put("search_index_count", 1))
                .put("index", new JSONArray().put(item().toJson())));
        SnapshotCache reopened = new SnapshotCache(temporary);
        JSONObject saved = reopened.read("bootstrap", null);
        assertNotNull(saved);
        assertNotNull(MarketSearchIndex.fromJson(saved.getJSONArray("index")).resolve("Atlanta GA"));
    }

    public void testScanActionsHaveAccessibleTouchTargets() throws Exception {
        activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> {
            int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
            assertTrue(activity.findViewById(R.id.run_analysis).getHeight() >= minimum);
            assertTrue(activity.findViewById(R.id.compare_markets).getHeight() >= minimum);
            assertTrue(activity.findViewById(R.id.bulk_input).getHeight() >= minimum);
            assertFalse(activity.findViewById(R.id.run_analysis).isEnabled());
            assertTrue(activity.findViewById(R.id.run_analysis).getAlpha() < 1f);
        });
    }
}
