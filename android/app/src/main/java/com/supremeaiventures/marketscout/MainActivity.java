package com.supremeaiventures.marketscout;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 10, 15);
    private static final int SURFACE = Color.rgb(17, 17, 24);
    private static final int BORDER = Color.rgb(48, 48, 62);
    private static final int ACCENT = Color.rgb(232, 255, 71);
    private static final int TEXT = Color.rgb(232, 232, 240);
    private static final int MUTED = Color.rgb(166, 166, 185);
    private static final int GREEN = Color.rgb(71, 255, 138);
    private static final int YELLOW = Color.rgb(255, 193, 69);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScanSession scan = new ScanSession();
    private final List<MarketDataRepository.Request> lookups = new ArrayList<>();
    private final Runnable saveDraft = this::persist;
    private ResearchStore store;
    private ResearchState state;
    private MarketDataRepository repository;
    private MarketDataRepository.Request bootstrap;
    private MarketSearchIndex index;
    private ScrollView scroll;
    private LinearLayout selectedList;
    private LinearLayout resultsList;
    private LinearLayout resultsSection;
    private AutoCompleteTextView marketInput;
    private EditText bulkInput;
    private ArrayAdapter<String> suggestions;
    private TextView sourceLabel;
    private TextView dataStatus;
    private TextView feedback;
    private TextView progress;
    private TextView resultSummary;
    private Button runButton;
    private Button shortlistButton;
    private Button sortButton;
    private Button directionButton;
    private Button filterButton;
    private Button compareButton;
    private Button savedButton;
    private Button retryButton;
    private Button addButton;
    private Button bulkButton;
    private boolean restoring = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ResearchStore(this);
        state = store.load();
        repository = new MarketDataRepository(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        marketInput.setText(state.searchDraft, false);
        bulkInput.setText(state.bulkDraft);
        restoring = false;
        renderSelections();
        renderResults();
        scroll.post(() -> scroll.scrollTo(0, state.scrollY));
        loadData();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (scan.isActive()) state.scanMessage = "Scan paused. Tap Run Analysis to finish your comparison.";
        persist();
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        persist();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        scan.cancel();
        handler.removeCallbacksAndMessages(null);
        repository.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
            root.requestApplyInsets();
        } else {
            root.setFitsSystemWindows(true);
        }

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = column();
        page.setPadding(dp(18), dp(14), dp(18), dp(16));
        scroll.addView(page);
        TextView title = label("Market Scout", 26, TEXT, true);
        page.addView(title);
        page.addView(label("Screen markets. Save your next move.", 14, MUTED, false), gap(0, 4, 0, 12));
        sourceLabel = label(sourceText(), 12, MUTED, false);
        page.addView(sourceLabel, gap(0, 0, 0, 8));
        LinearLayout connection = row();
        dataStatus = label("Loading market search...", 12, MUTED, false);
        connection.addView(dataStatus, weight(1, 0));
        retryButton = button("Retry", false);
        retryButton.setOnClickListener(view -> loadData());
        connection.addView(retryButton, wrap());
        page.addView(connection, gap(0, 0, 0, 12));

        LinearLayout inputCard = card();
        inputCard.addView(label("ADD MARKETS", 11, MUTED, true));
        LinearLayout inputRow = row();
        marketInput = new AutoCompleteTextView(this);
        marketInput.setId(R.id.market_input);
        styleInput(marketInput, "City, county + state, or ZIP");
        marketInput.setSingleLine();
        marketInput.setThreshold(2);
        marketInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        suggestions = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>()) {
            private android.widget.Filter searchFilter;
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setTextSize(16);
                view.setMinHeight(dp(48));
                view.setBackgroundColor(SURFACE);
                return view;
            }

            @Override
            public android.widget.Filter getFilter() {
                if (searchFilter != null) return searchFilter;
                searchFilter = new android.widget.Filter() {
                    @Override protected FilterResults performFiltering(CharSequence query) {
                        List<String> matches = index == null ? java.util.Collections.emptyList()
                                : index.suggestionLabels(query == null ? "" : query.toString(), 10);
                        FilterResults results = new FilterResults();
                        results.values = matches;
                        results.count = matches.size();
                        return results;
                    }
                    @Override @SuppressWarnings("unchecked")
                    protected void publishResults(CharSequence query, FilterResults results) {
                        clear();
                        if (results.values != null) addAll((List<String>) results.values);
                        notifyDataSetChanged();
                    }
                };
                return searchFilter;
            }
        };
        marketInput.setAdapter(suggestions);
        marketInput.setDropDownBackgroundDrawable(background(SURFACE));
        inputRow.addView(marketInput, weight(1, 0));
        addButton = button("Add", true);
        addButton.setId(R.id.add_market);
        inputRow.addView(addButton, wrap());
        inputCard.addView(inputRow, gap(0, 8, 0, 8));
        addButton.setOnClickListener(view -> addMarkets(marketInput.getText().toString(), false));
        marketInput.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            addMarkets(marketInput.getText().toString(), false);
            return true;
        });
        marketInput.setOnItemClickListener((parent, view, position, id) ->
                addMarkets(String.valueOf(parent.getItemAtPosition(position)), false));
        marketInput.addTextChangedListener(watcher(() -> {
            state.searchDraft = marketInput.getText().toString();
            saveSoon();
            if (state.searchDraft.trim().length() < 2) marketInput.dismissDropDown();
        }));
        bulkInput = new EditText(this);
        bulkInput.setId(R.id.bulk_input);
        styleInput(bulkInput, "Paste a list:\nBrooklyn Park, MN Atlanta, GA 90210");
        bulkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bulkInput.setMinLines(2);
        bulkInput.setMaxLines(5);
        bulkInput.setGravity(Gravity.TOP | Gravity.START);
        bulkInput.addTextChangedListener(watcher(() -> {
            state.bulkDraft = bulkInput.getText().toString();
            saveSoon();
        }));
        inputCard.addView(bulkInput, gap(0, 0, 0, 8));
        bulkButton = button("Add Pasted List", true);
        bulkButton.setId(R.id.add_pasted_list);
        bulkButton.setOnClickListener(view -> addMarkets(bulkInput.getText().toString(), true));
        inputCard.addView(bulkButton);
        feedback = label("", 13, GREEN, false);
        inputCard.addView(feedback, gap(0, 8, 0, 0));
        page.addView(inputCard, gap(0, 0, 0, 12));

        LinearLayout research = card();
        LinearLayout researchActions = row();
        savedButton = button("Saved lists", false);
        savedButton.setId(R.id.saved_lists);
        savedButton.setOnClickListener(view -> showLists());
        Button save = button("Save list", false);
        save.setId(R.id.save_list);
        save.setOnClickListener(view -> saveListDialog());
        researchActions.addView(savedButton, weight(1, 0));
        researchActions.addView(save, weight(1, 8));
        research.addView(researchActions);
        shortlistButton = button("Shortlist", false);
        shortlistButton.setId(R.id.shortlist_toggle);
        research.addView(shortlistButton, gap(0, 8, 0, 0));
        selectedList = column();
        selectedList.setVisibility(View.GONE);
        shortlistButton.setOnClickListener(view -> selectedList.setVisibility(
                selectedList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        research.addView(selectedList, gap(0, 8, 0, 0));
        Button clear = button("Clear current research", false);
        clear.setOnClickListener(view -> confirmClear());
        research.addView(clear, gap(0, 8, 0, 0));
        page.addView(research, gap(0, 0, 0, 12));

        resultsSection = card();
        resultsSection.setId(R.id.results_section);
        resultsSection.addView(label("Your market comparison", 22, TEXT, true));
        resultSummary = label("", 13, MUTED, false);
        resultsSection.addView(resultSummary, gap(0, 5, 0, 8));
        sortButton = button("Sort", false);
        sortButton.setId(R.id.sort_results);
        sortButton.setOnClickListener(view -> chooseSort());
        resultsSection.addView(sortButton);
        LinearLayout controls = row();
        directionButton = button("Low to high", false);
        directionButton.setId(R.id.sort_direction);
        directionButton.setOnClickListener(view -> {
            state.descending = !state.descending;
            persist();
            renderResults();
        });
        filterButton = button("Filters", false);
        filterButton.setId(R.id.filter_results);
        filterButton.setOnClickListener(view -> chooseFilters());
        controls.addView(directionButton, weight(1, 0));
        controls.addView(filterButton, weight(1, 8));
        resultsSection.addView(controls, gap(0, 8, 0, 8));
        progress = label("", 13, MUTED, false);
        progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        resultsSection.addView(progress, gap(0, 0, 0, 10));
        resultsList = column();
        resultsList.setId(R.id.results_list);
        resultsSection.addView(resultsList);
        Button export = button("Export comparison CSV", false);
        export.setId(R.id.export_csv);
        export.setOnClickListener(view -> exportCsv());
        resultsSection.addView(export, gap(0, 8, 0, 8));
        resultsSection.addView(label("ZIP metrics can be sparse. Missing readings are shown as unavailable. Income is a Census ACS median estimate.", 12, MUTED, false));
        page.addView(resultsSection, gap(0, 0, 0, 16));
        TextView copyright = label("\u00a92026 SUPREME AI VENTURES LLC", 11, MUTED, false);
        copyright.setGravity(Gravity.CENTER);
        page.addView(copyright);

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(18), dp(10), dp(18), dp(10));
        toolbar.setBackground(background(SURFACE));
        runButton = button("Run Analysis", true);
        runButton.setId(R.id.run_analysis);
        runButton.setOnClickListener(view -> {
            if (scan.isActive()) cancelScan("Scan canceled. Your partial results are saved.");
            else runAnalysis();
        });
        compareButton = button("Compare (0)", false);
        compareButton.setId(R.id.compare_markets);
        compareButton.setOnClickListener(view -> showComparison());
        toolbar.addView(runButton, weight(1, 0));
        toolbar.addView(compareButton, weight(1, 8));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void loadData() {
        if (bootstrap != null) bootstrap.cancel();
        retryButton.setEnabled(false);
        dataStatus.setText("Checking market data...");
        bootstrap = repository.loadBootstrap(new MarketDataRepository.BootstrapCallback() {
            @Override
            public void onSuccess(ManifestSnapshot manifest, MarketSearchIndex loaded, boolean cached) {
                index = loaded;
                state.sourceDate = manifest.latestSourceUpdatedAt == null ? "" : manifest.latestSourceUpdatedAt;
                sourceLabel.setText(sourceText());
                dataStatus.setText(cached ? "Cached market search ready. Retry to check for updates." :
                        String.format(Locale.US, "%,d markets ready", manifest.searchIndexCount));
                retryButton.setEnabled(true);
                persist();
                renderSelections();
            }
            @Override
            public void onError(Exception error) {
                dataStatus.setText("Search is unavailable. Saved research is still here.");
                retryButton.setEnabled(true);
                renderSelections();
            }
        });
    }

    private void addMarkets(String raw, boolean bulk) {
        if (index == null || scan.isActive()) return;
        BulkParseResult parsed = index.parseBulk(raw);
        if (parsed.items.isEmpty()) {
            message("No match. Include the state for cities/counties or use a 5-digit ZIP.", true);
            return;
        }
        int added = 0;
        for (SearchItem item : parsed.items) {
            boolean duplicate = false;
            for (SearchItem selected : state.selections) if (selected.identity().equals(item.identity())) duplicate = true;
            if (!duplicate) { state.selections.add(item); added++; }
        }
        state.activeList = "";
        String report = "Added " + added + " market" + (added == 1 ? "" : "s");
        if (added < parsed.items.size()) report += ". Existing markets skipped.";
        if (!parsed.unmatched.isEmpty()) report += ". Unmatched: " + String.join(" ", parsed.unmatched);
        message(report, !parsed.unmatched.isEmpty());
        // Keep unrecognized input available for correction rather than losing it.
        if (parsed.unmatched.isEmpty()) {
            if (bulk) bulkInput.setText(""); else marketInput.setText("", false);
        }
        persist();
        renderSelections();
        hideKeyboard();
    }

    private void renderSelections() {
        selectedList.removeAllViews();
        shortlistButton.setText("Shortlist (" + state.selections.size() + ")  \u00b7  Tap to review");
        savedButton.setText(state.activeList.isEmpty() ? "Saved lists (" + state.savedLists.size() + ")" : state.activeList);
        for (SearchItem item : state.selections) {
            LinearLayout market = row();
            market.addView(label(item.label, 15, TEXT, true), weight(1, 0));
            Button remove = button("Remove", false);
            remove.setContentDescription("Remove " + item.label + " from shortlist");
            remove.setEnabled(!scan.isActive());
            remove.setOnClickListener(view -> {
                state.selections.remove(item);
                state.activeList = "";
                persist();
                renderSelections();
            });
            market.addView(remove, wrap());
            selectedList.addView(market, gap(0, 6, 0, 0));
        }
        boolean editable = !scan.isActive();
        marketInput.setEnabled(editable);
        bulkInput.setEnabled(editable);
        addButton.setEnabled(editable && index != null);
        bulkButton.setEnabled(editable && index != null);
        runButton.setEnabled(scan.isActive() || !state.selections.isEmpty() && index != null);
        runButton.setText(scan.isActive() ? "Cancel scan" : "Run Analysis");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Clear current research?")
                .setMessage("Saved lists, favorites, and market notes will be kept.")
                .setNegativeButton("Keep", null).setPositiveButton("Clear", (dialog, which) -> {
                    cancelScan("");
                    state.selections.clear();
                    state.results.clear();
                    state.compared.clear();
                    state.activeList = "";
                    state.scanMessage = "";
                    persist();
                    renderSelections();
                    renderResults();
                }).show();
    }

    private void runAnalysis() {
        if (index == null || state.selections.isEmpty()) return;
        cancelScan("");
        long token = scan.begin(state.selections);
        state.results.clear();
        state.compared.clear();
        state.scanMessage = "Loading your markets...";
        persist();
        renderSelections();
        renderResults();
        hideKeyboard();
        scroll.post(() -> scroll.smoothScrollTo(0, resultsSection.getTop()));
        nextMarket(token, 0);
    }

    private void nextMarket(long token, int position) {
        if (!scan.accepts(token)) return;
        if (position >= scan.markets().size()) {
            scan.finish(token);
            lookups.clear();
            state.scanMessage = "Scan complete. " + state.results.size() + " markets checked.";
            persist();
            renderSelections();
            renderResults();
            return;
        }
        SearchItem item = scan.markets().get(position);
        progress.setText("Loading " + (position + 1) + " of " + scan.markets().size() + ": " + item.label);
        lookups.add(repository.lookup(item, new MarketDataRepository.LookupCallback() {
            @Override
            public void onSuccess(MarketResult result) {
                accept(result == null ? MarketResult.missing(item, "No market data available.") : result);
            }
            @Override
            public void onError(Exception error) {
                accept(MarketResult.missing(item, "Could not load this market. Retry the scan when connected."));
            }
            private void accept(MarketResult result) {
                if (!scan.accepts(token)) return;
                state.results.add(result);
                state.scanMessage = "Scan paused. Tap Run Analysis to finish your comparison.";
                persist();
                renderResults();
                nextMarket(token, position + 1);
            }
        }));
    }

    private void cancelScan(String reason) {
        scan.cancel();
        for (MarketDataRepository.Request request : lookups) request.cancel();
        lookups.clear();
        state.scanMessage = reason;
        if (runButton != null) {
            persist();
            renderSelections();
            renderResults();
        }
    }

    private void chooseSort() {
        MarketSort[] options = MarketSort.values();
        String[] names = new String[options.length];
        for (int i = 0; i < options.length; i++) names[i] = options[i].label;
        new AlertDialog.Builder(this).setTitle("Sort markets")
                .setSingleChoiceItems(names, state.sort.ordinal(), (dialog, which) -> {
                    state.sort = options[which];
                    persist();
                    renderResults();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseFilters() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(12), dp(20), dp(8));
        CheckBox favorites = new CheckBox(this);
        favorites.setText("Favorites only");
        favorites.setTextColor(TEXT);
        favorites.setChecked(state.favoritesOnly);
        favorites.setMinHeight(dp(48));
        form.addView(favorites);
        EditText supply = numberInput("Maximum months of supply", state.maxSupply == null ? "" : state.maxSupply.toString());
        EditText dom = numberInput("Maximum median days on market", state.maxDom == null ? "" : state.maxDom.toString());
        form.addView(label("Maximum months of supply (optional)", 14, TEXT, false));
        form.addView(supply, gap(0, 4, 0, 10));
        form.addView(label("Maximum median days on market (optional)", 14, TEXT, false));
        form.addView(dom, gap(0, 4, 0, 8));
        form.addView(label("Markets with missing readings are excluded when a limit is set.", 12, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Filter results").setView(form)
                .setNegativeButton("Cancel", null).setNeutralButton("Reset", (d, which) -> {
                    state.favoritesOnly = false; state.maxSupply = null; state.maxDom = null;
                    persist(); renderResults();
                }).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double supplyLimit = MarketSort.number(supply.getText().toString());
            Double domLimit = MarketSort.number(dom.getText().toString());
            if ((!supply.getText().toString().trim().isEmpty() && (supplyLimit == null || supplyLimit < 0))
                    || (!dom.getText().toString().trim().isEmpty() &&
                    (domLimit == null || domLimit < 0 || domLimit != Math.rint(domLimit) || domLimit > Integer.MAX_VALUE))) {
                Toast.makeText(this, "Enter nonnegative limits; days must be a whole number.", Toast.LENGTH_LONG).show();
                return;
            }
            state.favoritesOnly = favorites.isChecked();
            state.maxSupply = supplyLimit;
            state.maxDom = domLimit == null ? null : domLimit.intValue();
            persist(); renderResults(); dialog.dismiss();
        }));
        dialog.show();
    }

    private List<MarketResult> visibleResults() {
        List<MarketResult> visible = new ArrayList<>();
        for (MarketResult result : state.results) if (state.includes(result)) visible.add(result);
        if (state.sort != MarketSort.DEFAULT) visible.sort(state.sort.comparator(state.descending));
        return visible;
    }

    private void renderResults() {
        resultsSection.setVisibility(state.results.isEmpty() && !scan.isActive() ? View.GONE : View.VISIBLE);
        List<MarketResult> visible = visibleResults();
        resultSummary.setText(visible.size() + " of " + state.results.size() + " markets shown");
        sortButton.setText("Sort: " + state.sort.label);
        directionButton.setEnabled(state.sort != MarketSort.DEFAULT);
        directionButton.setText(state.descending ? "Descending" : "Ascending");
        int filters = (state.favoritesOnly ? 1 : 0) + (state.maxSupply != null ? 1 : 0) + (state.maxDom != null ? 1 : 0);
        filterButton.setText(filters == 0 ? "Filters" : "Filters (" + filters + ")");
        compareButton.setText("Compare (" + state.compared.size() + ")");
        compareButton.setEnabled(state.compared.size() >= 2);
        if (!scan.isActive()) progress.setText(state.scanMessage);
        resultsList.removeAllViews();
        if (visible.isEmpty() && !state.results.isEmpty()) {
            resultsList.addView(label("No markets match. Adjust your filters to see more.", 15, MUTED, false));
        }
        for (MarketResult result : visible) resultsList.addView(resultCard(result), gap(0, 0, 0, 12));
    }

    private View resultCard(MarketResult result) {
        LinearLayout card = card();
        TextView market = label(result.city, 20, TEXT, true);
        market.setMinHeight(dp(48));
        market.setContentDescription("Open " + result.city + " on Zillow");
        market.setOnClickListener(view -> openUrl(zillowUrl(result.city)));
        card.addView(market);
        TextView marketType = label(result.marketTypeLabel(), 14, marketColor(result), true);
        if (result.redfinUrl != null) {
            marketType.setMinHeight(dp(48));
            marketType.setContentDescription("Open Redfin market page for " + result.city);
            marketType.setOnClickListener(view -> openUrl(result.redfinUrl));
        }
        card.addView(marketType);
        card.addView(label((result.cached ? "Cached reading \u00b7 " : "") + "Period " + display(result.period), 12, MUTED, false));
        if (!"OK".equals(result.status)) card.addView(label(result.status, 13, YELLOW, false), gap(0, 6, 0, 6));
        metrics(card, "MEDIAN SALE", display(result.medianSale), "MONTHS SUPPLY", months(result.monthsSupply));
        metrics(card, "MEDIAN DOM", result.medianDom == null ? "Unavailable" : result.medianDom + " days", "PAR RATIO", par(result));

        LinearLayout choices = row();
        Button favorite = button(state.favorites.contains(result.identity()) ? "Favorited" : "Favorite", false);
        favorite.setContentDescription((state.favorites.contains(result.identity()) ? "Remove favorite " : "Favorite ") + result.city);
        favorite.setOnClickListener(view -> {
            if (!state.favorites.add(result.identity())) state.favorites.remove(result.identity());
            persist(); renderResults();
        });
        CheckBox compare = new CheckBox(this);
        compare.setText("Compare");
        compare.setTextColor(TEXT);
        compare.setMinHeight(dp(48));
        compare.setContentDescription("Include " + result.city + " in side-by-side comparison");
        compare.setChecked(state.compared.contains(result.identity()));
        compare.setOnCheckedChangeListener((button, checked) -> {
            if (checked && state.compared.size() >= 3) {
                button.setChecked(false);
                Toast.makeText(this, "Compare up to three markets at a time.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checked) state.compared.add(result.identity()); else state.compared.remove(result.identity());
            persist();
            compareButton.setText("Compare (" + state.compared.size() + ")");
            compareButton.setEnabled(state.compared.size() >= 2);
        });
        choices.addView(favorite, weight(1, 0));
        choices.addView(compare, weight(1, 8));
        card.addView(choices, gap(0, 8, 0, 8));

        LinearLayout details = column();
        details.setVisibility(View.GONE);
        metrics(details, "SALE VS LIST", display(result.saleToList), "SOLD ABOVE LIST", display(result.soldAboveList));
        metrics(details, "PRICE DROPS", display(result.priceDrops), "OFF MKT 2WK", display(result.offMarketTwoWeeks));
        metrics(details, "MEDIAN HH INCOME", display(result.householdIncome), "HOMES SOLD", display(result.homesSold));
        if (!"OK".equals(result.status)) {
            Button retry = button("Retry " + result.city, false);
            retry.setEnabled(!scan.isActive() && index != null);
            retry.setOnClickListener(view -> retryMarket(result));
            details.addView(retry, gap(0, 8, 0, 0));
        }
        LinearLayout links = row();
        Button zillow = button("Zillow listings", false);
        zillow.setOnClickListener(view -> openUrl(zillowUrl(result.city)));
        Button redfin = button("Redfin market", false);
        redfin.setEnabled(result.redfinUrl != null);
        redfin.setOnClickListener(view -> openUrl(result.redfinUrl));
        links.addView(zillow, weight(1, 0)); links.addView(redfin, weight(1, 8));
        details.addView(links, gap(0, 8, 0, 8));
        String note = state.notes.get(result.identity());
        if (note != null && !note.isEmpty()) details.addView(label(note, 15, TEXT, false), gap(0, 4, 0, 8));
        Button editNote = button(note == null || note.isEmpty() ? "Add research note" : "Edit research note", false);
        editNote.setOnClickListener(view -> editNote(result));
        details.addView(editNote);
        Button expand = button("More metrics & research", false);
        expand.setOnClickListener(view -> {
            boolean expanded = details.getVisibility() == View.VISIBLE;
            details.setVisibility(expanded ? View.GONE : View.VISIBLE);
            expand.setText(expanded ? "More metrics & research" : "Hide details");
        });
        card.addView(expand);
        card.addView(details, gap(0, 8, 0, 0));
        return card;
    }

    private void retryMarket(MarketResult previous) {
        if (scan.isActive()) return;
        long token = scan.begin(java.util.Collections.singletonList(previous.item));
        renderSelections();
        lookups.add(repository.lookup(previous.item, new MarketDataRepository.LookupCallback() {
            @Override public void onSuccess(MarketResult result) {
                if (!scan.accepts(token)) return;
                int position = state.results.indexOf(previous);
                if (position >= 0 && result != null) state.results.set(position, result);
                scan.finish(token); lookups.clear();
                state.scanMessage = result == null ? "No market data available." : "Market refreshed.";
                persist(); renderSelections(); renderResults();
            }
            @Override public void onError(Exception error) {
                if (!scan.accepts(token)) return;
                scan.finish(token); lookups.clear();
                state.scanMessage = "Retry failed. Your saved results are kept.";
                persist(); renderSelections(); renderResults();
            }
        }));
    }

    private void editNote(MarketResult result) {
        EditText note = new EditText(this);
        styleInput(note, "What makes this market worth a closer look?");
        note.setMinLines(3);
        note.setGravity(Gravity.TOP);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setText(state.notes.get(result.identity()));
        new AlertDialog.Builder(this).setTitle("Notes: " + result.city).setView(note)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                    String value = note.getText().toString().trim();
                    if (value.isEmpty()) state.notes.remove(result.identity()); else state.notes.put(result.identity(), value);
                    persist(); renderResults();
                }).show();
    }

    private void saveListDialog() {
        EditText name = new EditText(this);
        styleInput(name, "Example: Midwest leads");
        name.setSingleLine();
        name.setText(state.activeList);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save markets, sort & filters")
                .setView(name).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (state.savedLists.containsKey(title)) {
                new AlertDialog.Builder(this).setTitle("Replace " + title + "?").setMessage("This updates the markets and filters in this saved list.")
                        .setNegativeButton("Keep", null).setPositiveButton("Replace", (confirm, which) -> {
                            if (saveNamedList(title)) dialog.dismiss();
                        }).show();
            } else if (saveNamedList(title)) dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean saveNamedList(String name) {
        try {
            state.saveList(name);
            persist(); renderSelections();
            message("Saved " + state.activeList + " on this device.", false);
            return true;
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void showLists() {
        if (state.savedLists.isEmpty()) {
            message("Save a shortlist to pick up your research later.", false);
            return;
        }
        String[] names = state.savedLists.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Saved research lists").setItems(names, (dialog, which) ->
                new AlertDialog.Builder(this).setTitle(names[which])
                        .setPositiveButton("Open", (d, w) -> {
                            cancelScan("");
                            state.openList(names[which]);
                            persist(); renderSelections(); renderResults();
                            scroll.smoothScrollTo(0, 0);
                        }).setNeutralButton("Delete", (d, w) ->
                                new AlertDialog.Builder(this).setTitle("Delete saved list?")
                                        .setMessage("Favorites and notes will be kept.")
                                        .setNegativeButton("Keep", null).setPositiveButton("Delete", (confirm, action) -> {
                                            state.savedLists.remove(names[which]);
                                            if (state.activeList.equals(names[which])) state.activeList = "";
                                            persist(); renderSelections();
                                        }).show())
                        .setNegativeButton("Cancel", null).show()).show();
    }

    private void showComparison() {
        List<MarketResult> selected = new ArrayList<>();
        for (MarketResult result : state.results) if (state.compared.contains(result.identity())) selected.add(result);
        if (selected.size() < 2) return;
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        LinearLayout table = column();
        table.setPadding(dp(16), dp(12), dp(16), dp(12));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int labelWidth = screenWidth < dp(360) ? dp(80) : dp(96);
        int marketWidth = Math.max(dp(80), (screenWidth - dp(80) - labelWidth) / 2);
        String[] labels = {"Market", "Market type", "Median sale", "Months supply", "Median DOM", "PAR ratio",
                "Sale vs list", "Sold above list", "Price drops", "Off market 2wk", "Median HH income", "Reporting period"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = row();
            TextView label = label(labels[i], 14, MUTED, true);
            label.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.addView(label, new LinearLayout.LayoutParams(labelWidth, -1));
            for (MarketResult result : selected) {
                String[] values = {result.city, result.marketTypeLabel(), display(result.medianSale), months(result.monthsSupply),
                        result.medianDom == null ? "Unavailable" : result.medianDom + " days", par(result), display(result.saleToList),
                        display(result.soldAboveList), display(result.priceDrops), display(result.offMarketTwoWeeks),
                        display(result.householdIncome), display(result.period)};
                TextView value = label(values[i], 15, TEXT, i == 0);
                value.setContentDescription(labels[i] + " for " + result.city + ": " + values[i]);
                value.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.addView(value, new LinearLayout.LayoutParams(marketWidth, -1));
            }
            if (i % 2 == 0) row.setBackground(background(SURFACE));
            table.addView(row);
        }
        horizontal.addView(table);
        ScrollView vertical = new ScrollView(this);
        vertical.addView(horizontal);
        new AlertDialog.Builder(this).setTitle("Compare " + selected.size() + " markets")
                .setMessage(selected.size() == 3 ? "Swipe horizontally for the third market." : "Two markets, one comparison.")
                .setView(vertical).setPositiveButton("Done", null).show();
    }

    private void exportCsv() {
        List<MarketResult> results = visibleResults();
        if (results.isEmpty()) { message("No visible results to export.", true); return; }
        StringBuilder csv = new StringBuilder("Market,Market Type,PAR Ratio,Months Supply,Median DOM,Median Sale,Sale vs List,Sold Above List,Price Drops,Off Market 2wk,Median HH Income,Reporting Period,Favorite,Notes\n");
        for (MarketResult result : results) {
            String[] cells = {result.city, result.marketTypeLabel(), par(result), months(result.monthsSupply),
                    display(result.medianDom), display(result.medianSale), display(result.saleToList), display(result.soldAboveList),
                    display(result.priceDrops), display(result.offMarketTwoWeeks), display(result.householdIncome),
                    display(result.period), String.valueOf(state.favorites.contains(result.identity())), state.notes.getOrDefault(result.identity(), "")};
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) csv.append(',');
                String value = cells[i];
                if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
                csv.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            csv.append('\n');
        }
        try {
            // A system document picker writes a real file, without requesting storage permission.
            state.pendingCsv = csv.toString();
            persist();
            Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "market-scout-" + LocalDate.now() + ".csv");
            startActivityForResult(export, 10);
        } catch (ActivityNotFoundException error) {
            message("No document picker is available on this device.", true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 10) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || state.pendingCsv.isEmpty()) {
            state.pendingCsv = "";
            persist();
            return;
        }
        try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new java.io.IOException("Destination unavailable");
            output.write(state.pendingCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message("CSV exported.", false);
        } catch (Exception error) { message("Could not export the CSV. Please try again.", true); }
        finally { state.pendingCsv = ""; persist(); }
    }

    private void persist() {
        if (state == null || store == null || restoring) return;
        state.scrollY = scroll.getScrollY();
        store.save(state);
    }

    private void saveSoon() {
        if (restoring) return;
        handler.removeCallbacks(saveDraft);
        handler.postDelayed(saveDraft, 250);
    }

    private String sourceText() {
        if (state.sourceDate.isEmpty()) return "Redfin market data \u00b7 Saved research stays on this device";
        String date = state.sourceDate;
        try { date = LocalDate.parse(date.substring(0, 10)).format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)); }
        catch (Exception ignored) { }
        return "Redfin source updated " + date;
    }

    private void metrics(LinearLayout parent, String a, String av, String b, String bv) {
        LinearLayout row = row();
        row.addView(metric(a, av), weight(1, 0)); row.addView(metric(b, bv), weight(1, 8));
        parent.addView(row, gap(0, 8, 0, 0));
    }

    private View metric(String name, String value) {
        LinearLayout cell = column();
        cell.setPadding(dp(10), dp(12), dp(10), dp(12));
        cell.setBackground(background(BG));
        cell.addView(label(name, 11, MUTED, true));
        cell.addView(label(value, 17, TEXT, true), gap(0, 5, 0, 0));
        return cell;
    }

    private String display(Object value) { return value == null || value.toString().isEmpty() ? "Unavailable" : value.toString(); }
    private String months(Double value) { return value == null ? "Unavailable" : String.format(Locale.US, "%.1f mo", value); }
    private String par(MarketResult result) {
        if (result.parRatio == null) return "Unavailable";
        return String.format(Locale.US, "%.1f%%", result.parRatio);
    }

    private int marketColor(MarketResult result) {
        String type = result.marketTypeLabel().toLowerCase(Locale.US);
        return type.contains("seller") ? GREEN : type.contains("buyer") ? Color.rgb(255, 125, 125) : YELLOW;
    }

    private String zillowUrl(String city) {
        return "https://www.zillow.com/homes/" + city.toLowerCase(Locale.US).replace(",", "")
                .replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", "").replaceAll("-+", "-") + "/";
    }

    private void openUrl(String address) {
        if (address == null) return;
        Uri uri = Uri.parse(address);
        if (!"https".equals(uri.getScheme())) { message("This market link is unavailable.", true); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) { message("No browser is available.", true); }
    }

    private void message(String text, boolean warning) {
        feedback.setText(text); feedback.setTextColor(warning ? YELLOW : GREEN);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        marketInput.dismissDropDown();
        marketInput.clearFocus(); bulkInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(scroll.getWindowToken(), 0);
    }

    private TextWatcher watcher(Runnable change) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!restoring) change.run(); }
            @Override public void afterTextChanged(Editable text) { }
        };
    }

    private EditText numberInput(String hint, String value) {
        EditText input = new EditText(this); styleInput(input, hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value); return input;
    }

    private void styleInput(EditText input, String hint) {
        input.setHint(hint); input.setContentDescription(hint);
        input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setMinHeight(dp(48)); input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(background(BG));
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(background(SURFACE)); return view;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); return view;
    }
    private Button button(String title, boolean primary) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setTextSize(14); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setTextColor(primary ? BG : TEXT); button.setBackground(background(primary ? ACCENT : BG));
        return button;
    }
    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(12));
        if (color != ACCENT) drawable.setStroke(dp(1), BORDER); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams gap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }
    private LinearLayout.LayoutParams weight(int value, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, value);
        params.setMargins(dp(left), 0, 0, 0); return params;
    }
    private LinearLayout.LayoutParams wrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(8), 0, 0, 0); return params;
    }
}
