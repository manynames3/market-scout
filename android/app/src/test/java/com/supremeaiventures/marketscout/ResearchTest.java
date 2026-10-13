package com.supremeaiventures.marketscout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ResearchTest {
    private SearchItem market(String label, String type, String value) throws Exception {
        return new SearchItem(new JSONObject().put("label", label).put("value", value).put("type", type)
                .put("key", value.toLowerCase()).put("state", "GA").put("shard", "markets/" + type + "/GA.json"));
    }

    private MarketResult result(String label, Object price, Object supply) throws Exception {
        return MarketResult.fromJson(new JSONObject().put("median_sale", price).put("months_supply", supply)
                .put("period", "2026-03-31"), market(label, "city", label));
    }

    @Test public void bulkPasteMatchesLongestCityCountyAndLeadingZeroZip() throws Exception {
        JSONArray data = new JSONArray().put(market("Brooklyn, NY", "city", "Brooklyn, NY").toJson())
                .put(market("Brooklyn Park, MN", "city", "Brooklyn Park, MN").toJson())
                .put(market("Orange County, CA", "county", "Orange County, CA").toJson())
                .put(market("02108", "zip", "02108").toJson());
        BulkParseResult parsed = MarketSearchIndex.fromJson(data).parseBulk("Brooklyn Park, MN Orange County, CA; 02108");
        assertEquals(3, parsed.items.size());
        assertEquals("Brooklyn Park, MN", parsed.items.get(0).label);
        assertEquals("02108", parsed.items.get(2).value);
        assertTrue(parsed.unmatched.isEmpty());
    }

    @Test public void bulkPasteDeduplicatesAndReportsUnknownInput() throws Exception {
        MarketSearchIndex index = MarketSearchIndex.fromJson(new JSONArray().put(market("Atlanta, GA", "city", "Atlanta, GA").toJson()));
        BulkParseResult parsed = index.parseBulk("Atlanta, GA ATLANTA, GA Unknown Town, ZZ");
        assertEquals(1, parsed.items.size());
        assertEquals(Arrays.asList("unknown", "town", "zz"), parsed.unmatched);
    }

    @Test public void malformedSearchEntriesAreSkipped() throws Exception {
        MarketSearchIndex index = MarketSearchIndex.fromJson(new JSONArray().put(JSONObject.NULL).put(new JSONObject())
                .put(market("Atlanta, GA", "city", "Atlanta, GA").toJson()));
        assertNotNull(index.resolve("atlanta ga"));
    }

    @Test public void scansUseFrozenInputs() throws Exception {
        List<SearchItem> input = new ArrayList<>();
        input.add(market("Atlanta, GA", "city", "Atlanta, GA"));
        ScanSession scan = new ScanSession();
        long token = scan.begin(input);
        input.clear();
        assertEquals(1, scan.markets().size());
        assertTrue(scan.accepts(token));
    }

    @Test public void cancelAndReplacementRejectLateCallbacks() throws Exception {
        ScanSession scan = new ScanSession();
        List<SearchItem> markets = Arrays.asList(market("Atlanta, GA", "city", "Atlanta, GA"));
        long first = scan.begin(markets);
        scan.cancel();
        assertFalse(scan.accepts(first));
        long second = scan.begin(markets);
        assertFalse(scan.accepts(first));
        assertTrue(scan.accepts(second));
        scan.finish(second);
        assertFalse(scan.accepts(second));
    }

    @Test public void pricesSortNumericallyWithMissingLastBothWays() throws Exception {
        MarketResult cheap = result("Cheap", "$950,000", 2);
        MarketResult expensive = result("Expensive", "$1.2m", 5);
        MarketResult missing = result("Missing", JSONObject.NULL, JSONObject.NULL);
        List<MarketResult> results = new ArrayList<>(Arrays.asList(missing, expensive, cheap));
        results.sort(MarketSort.MEDIAN_SALE.comparator(false));
        assertEquals(Arrays.asList(cheap, expensive, missing), results);
        results.sort(MarketSort.MEDIAN_SALE.comparator(true));
        assertEquals(Arrays.asList(expensive, cheap, missing), results);
    }

    @Test public void percentSortKeepsNegativeValues() throws Exception {
        MarketResult negative = MarketResult.fromJson(new JSONObject().put("sale_to_list", "-3.4%"), market("A", "city", "a"));
        MarketResult positive = MarketResult.fromJson(new JSONObject().put("sale_to_list", "+0.5%"), market("B", "city", "b"));
        assertTrue(MarketSort.SALE_TO_LIST.comparator(false).compare(negative, positive) < 0);
        assertNull(MarketSort.number("NaN"));
        assertNull(MarketSort.number("Unavailable"));
    }

    @Test public void researchRoundTripPreservesAllUserWork() throws Exception {
        ResearchState original = new ResearchState();
        SearchItem item = market("Atlanta, GA", "city", "Atlanta, GA");
        original.selections.add(item);
        MarketResult reading = MarketResult.fromJson(new JSONObject().put("median_sale", "$446,000")
                .put("months_supply", 5.5).put("par_pending", 12).put("par_total", 35)
                .put("period", "2026-03-31"), item);
        reading.householdIncome = "$70,000";
        original.results.add(reading);
        original.favorites.add(item.identity());
        original.notes.put(item.identity(), "Review inventory before calling.");
        original.compared.add(item.identity());
        original.searchDraft = "Brooklyn";
        original.bulkDraft = "Unfinished list";
        original.sourceDate = "2026-04-14";
        original.sort = MarketSort.MONTHS_SUPPLY;
        original.descending = true;
        original.favoritesOnly = true;
        original.maxSupply = 6.0;
        original.maxDom = 70;
        original.scrollY = 320;
        original.saveList("Candidate markets");
        ResearchState restored = ResearchState.fromJson(new JSONObject(original.toJson().toString()));
        assertEquals(item.identity(), restored.selections.get(0).identity());
        assertEquals("$70,000", restored.results.get(0).householdIncome);
        assertEquals(Integer.valueOf(12), restored.results.get(0).parPending);
        assertEquals(Integer.valueOf(35), restored.results.get(0).parTotal);
        assertEquals("2026-03-31", restored.results.get(0).period);
        assertEquals(original.notes, restored.notes);
        assertEquals(original.favorites, restored.favorites);
        assertEquals(original.compared, restored.compared);
        assertEquals("Brooklyn", restored.searchDraft);
        assertEquals("Unfinished list", restored.bulkDraft);
        assertEquals(320, restored.scrollY);
        restored.selections.clear();
        restored.openList("Candidate markets");
        assertEquals(1, restored.selections.size());
        assertEquals(MarketSort.MONTHS_SUPPLY, restored.sort);
        assertTrue(restored.descending);
        assertTrue(restored.favoritesOnly);
        assertEquals(Integer.valueOf(70), restored.maxDom);
        assertTrue(restored.results.isEmpty());
        assertFalse(restored.notes.isEmpty());
    }

    @Test public void savedListDoesNotChangeWhenCurrentListIsEdited() throws Exception {
        ResearchState state = new ResearchState();
        state.selections.add(market("Atlanta, GA", "city", "Atlanta, GA"));
        state.saveList("Original");
        state.selections.clear();
        state.maxSupply = 1.0;
        state.openList("Original");
        assertEquals(1, state.selections.size());
        assertNull(state.maxSupply);
    }

    @Test public void filtersExcludeMissingSupplyInsteadOfTreatingItAsZero() throws Exception {
        ResearchState state = new ResearchState();
        state.maxSupply = 3.0;
        assertTrue(state.includes(result("A", "$1", 2.5)));
        assertFalse(state.includes(result("B", "$1", 4)));
        assertFalse(state.includes(result("C", "$1", JSONObject.NULL)));
        state.favoritesOnly = true;
        MarketResult favorite = result("D", "$1", 1);
        assertFalse(state.includes(favorite));
        state.favorites.add(favorite.identity());
        assertTrue(state.includes(favorite));
    }

    @Test(expected = IllegalArgumentException.class) public void emptyListsCannotOverwriteSavedResearch() throws Exception {
        new ResearchState().saveList("Empty");
    }

    @Test public void censusMatchingDoesNotConfuseSimilarPlaces() {
        assertTrue(MarketDataRepository.sameGeography("Atlanta, GA", "Atlanta city, Georgia"));
        assertFalse(MarketDataRepository.sameGeography("York, PA", "New York city, Pennsylvania"));
        assertFalse(MarketDataRepository.sameGeography("Orange County, CA", "Orangeburg County, California"));
        assertTrue(MarketDataRepository.sameGeography("Orange County, CA", "Orange County, California"));
        assertTrue(MarketDataRepository.sameGeography("Jersey City, NJ", "Jersey City city, New Jersey"));
        assertTrue(MarketDataRepository.sameGeography("Oklahoma City, OK", "Oklahoma City city, Oklahoma"));
        assertFalse(MarketDataRepository.sameGeography("Jersey, NJ", "Jersey City city, New Jersey"));
    }

    @Test public void cacheRestoresLastGoodDataAndRejectsAnotherSnapshot() throws Exception {
        SnapshotCache cache = new SnapshotCache(Files.createTempDirectory("market-cache-test").toFile());
        cache.write("markets/city/GA.json", "v1", new JSONObject().put("items", new JSONObject().put("atlanta", 1)));
        assertNotNull(cache.read("markets/city/GA.json", "v1"));
        assertNull(cache.read("markets/city/GA.json", "v2"));
        cache.write("markets/city/GA.json", "v2", new JSONObject().put("items", new JSONObject().put("atlanta", 2)));
        assertEquals(2, cache.read("markets/city/GA.json", "v2").getJSONObject("items").getInt("atlanta"));
    }
}
