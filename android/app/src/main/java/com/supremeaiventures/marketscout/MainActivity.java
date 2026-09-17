package com.supremeaiventures.marketscout;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(10, 10, 15);
    private static final int SURFACE = Color.rgb(17, 17, 24);
    private static final int SURFACE_2 = Color.rgb(21, 21, 32);
    private static final int BORDER = Color.rgb(39, 39, 54);
    private static final int ACCENT = Color.rgb(232, 255, 71);
    private static final int TEXT = Color.rgb(232, 232, 240);
    private static final int MUTED = Color.rgb(136, 136, 157);
    private static final int GREEN = Color.rgb(71, 255, 138);
    private static final int YELLOW = Color.rgb(255, 193, 69);
    private static final int RED = Color.rgb(255, 107, 107);
    private static final Pattern NON_SLUG_CHARACTERS = Pattern.compile("[^a-z0-9-]");

    private ScrollView scrollView;
    private LinearLayout content;
    private LinearLayout selectedList;
    private LinearLayout resultsList;
    private LinearLayout resultsSection;
    private AutoCompleteTextView marketInput;
    private EditText bulkInput;
    private ArrayAdapter<String> suggestionAdapter;
    private TextView feedback;
    private TextView shortlistLabel;
    private TextView freshnessLabel;
    private TextView dataStatus;
    private TextView resultsMeta;
    private TextView progressLabel;
    private ProgressBar progressBar;
    private Button addButton;
    private Button bulkAddButton;
    private Button runButton;
    private Button clearButton;
    private Button sortFieldButton;
    private Button sortDirectionButton;
    private Button shareButton;

    private final List<SearchItem> selections = new ArrayList<>();
    private final List<MarketResult> results = new ArrayList<>();
    private MarketDataRepository repository;
    private MarketSearchIndex searchIndex;
    private ManifestSnapshot manifest;
    private SortField sortField = SortField.DEFAULT;
    private boolean sortDescending;
    private int completedLookups;

    private enum SortField {
        DEFAULT("Default order"),
        MARKET("Market"),
        MARKET_TYPE("Market Type"),
        PAR_RATIO("PAR Ratio"),
        MONTHS_SUPPLY("Months Supply"),
        MEDIAN_DOM("Avg DOM"),
        MEDIAN_SALE("Median Sale"),
        SALE_TO_LIST("Sale vs List"),
        SOLD_ABOVE_LIST("% Sold Above"),
        PRICE_DROPS("Price Drops"),
        OFF_MARKET("Off Mkt 2wk"),
        INCOME("Avg HH Income"),
        DATE("Data Date");

        final String label;

        SortField(String label) {
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        repository = new MarketDataRepository();
        buildUi();
        loadBootstrapData();
    }

    @Override
    protected void onDestroy() {
        if (repository != null) repository.shutdown();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    private void buildUi() {
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);

        content = vertical();
        int pagePadding = dp(18);
        content.setPadding(pagePadding, dp(16), pagePadding, dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(pagePadding, dp(16) + bars.top, pagePadding, dp(28) + bars.bottom);
                return insets;
            });
            content.requestApplyInsets();
        }

        content.addView(buildHeader(), margins(0, 0, 0, 16));
        content.addView(buildIntroCard(), margins(0, 0, 0, 14));
        content.addView(buildMarketInputCard(), margins(0, 0, 0, 14));
        content.addView(buildShortlistCard(), margins(0, 0, 0, 14));
        content.addView(buildResultsSection(), margins(0, 0, 0, 14));
        content.addView(buildFooter());
    }

    private View buildHeader() {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = text("M", 23, BACKGROUND, Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(roundRect(ACCENT, 0, 8));
        header.addView(logo, size(46, 46));

        LinearLayout brand = vertical();
        TextView title = text("Market Scout", 21, TEXT, Typeface.DEFAULT_BOLD);
        TextView subtitle = text("Market intelligence on the go", 11, MUTED, Typeface.MONOSPACE);
        brand.addView(title);
        brand.addView(subtitle, margins(0, 2, 0, 0));
        header.addView(brand, weightParams(1, 0, 0, 0, 12, 0, 0));

        freshnessLabel = text("Loading data...", 10, MUTED, Typeface.MONOSPACE);
        freshnessLabel.setGravity(Gravity.RIGHT);
        freshnessLabel.setMaxWidth(dp(125));
        header.addView(freshnessLabel, widthParams(125));
        return header;
    }

    private View buildIntroCard() {
        LinearLayout card = card(SURFACE);
        TextView eyebrow = sectionLabel("MARKET SCREENING");
        card.addView(eyebrow);
        TextView headline = text("Find markets worth chasing.", 29, TEXT, Typeface.DEFAULT_BOLD);
        headline.setLineSpacing(0, 1.03f);
        card.addView(headline, margins(0, 5, 0, 10));
        TextView body = text(
                "Compare pricing, supply, demand, and sales speed before you spend time on the next lead. Buying is not the finish line. Exiting is.",
                14,
                MUTED,
                Typeface.DEFAULT
        );
        body.setLineSpacing(dp(3), 1.0f);
        card.addView(body);
        TextView signal = text("PRICING   •   SUPPLY   •   SPEED   •   DEMAND", 10, ACCENT, Typeface.MONOSPACE);
        card.addView(signal, margins(0, 14, 0, 0));
        return card;
    }

    private View buildMarketInputCard() {
        LinearLayout card = card(SURFACE);
        card.addView(sectionLabel("ADD MARKETS"));

        LinearLayout singleRow = horizontal();
        singleRow.setGravity(Gravity.CENTER_VERTICAL);
        marketInput = new AutoCompleteTextView(this);
        marketInput.setSingleLine(true);
        marketInput.setTextSize(14);
        marketInput.setTextColor(TEXT);
        marketInput.setHintTextColor(MUTED);
        marketInput.setHint("Atlanta, GA");
        marketInput.setPadding(dp(13), 0, dp(13), 0);
        marketInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        marketInput.setBackground(roundRect(BACKGROUND, BORDER, 10));
        marketInput.setThreshold(2);
        marketInput.setDropDownBackgroundDrawable(roundRect(SURFACE_2, BORDER, 10));
        suggestionAdapter = new SuggestionAdapter(this);
        marketInput.setAdapter(suggestionAdapter);
        singleRow.addView(marketInput, weightParams(1, 0, 0, 0, 0, 0, 0));

        addButton = primaryButton("Add");
        singleRow.addView(addButton, wrapMargins(8, 0, 0, 0));
        card.addView(singleRow);

        TextView hint = text("Search any city, ZIP code, or county.", 11, MUTED, Typeface.MONOSPACE);
        card.addView(hint, margins(0, 7, 0, 13));

        bulkInput = new EditText(this);
        bulkInput.setTextSize(12);
        bulkInput.setTextColor(TEXT);
        bulkInput.setHintTextColor(MUTED);
        bulkInput.setGravity(Gravity.TOP | Gravity.START);
        bulkInput.setHint("Paste a bulk list like:\nPhiladelphia, MS Brooklyn Park, MN\nOrange County, CA 90210");
        bulkInput.setPadding(dp(13), dp(11), dp(13), dp(11));
        bulkInput.setMinLines(4);
        bulkInput.setMaxLines(7);
        bulkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bulkInput.setBackground(roundRect(BACKGROUND, BORDER, 10));
        card.addView(bulkInput, margins(0, 0, 0, 8));

        bulkAddButton = accentButton("Add Pasted List");
        card.addView(bulkAddButton, widthParams(ViewGroup.LayoutParams.MATCH_PARENT));

        feedback = text("", 11, MUTED, Typeface.MONOSPACE);
        feedback.setMinHeight(dp(20));
        feedback.setLineSpacing(dp(2), 1.0f);
        card.addView(feedback, margins(0, 8, 0, 0));

        addButton.setOnClickListener(view -> addFromInput());
        bulkAddButton.setOnClickListener(view -> addBulkMarkets(bulkInput.getText().toString()));
        marketInput.setOnItemClickListener((parent, view, position, id) -> {
            String label = String.valueOf(parent.getItemAtPosition(position));
            SearchItem item = searchIndex == null ? null : searchIndex.resolve(label);
            if (item != null) addSelection(item);
            marketInput.setText("");
            marketInput.dismissDropDown();
        });
        marketInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSuggestions(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        return card;
    }

    private View buildShortlistCard() {
        LinearLayout card = card(SURFACE);
        shortlistLabel = sectionLabel("SHORTLIST (0)");
        card.addView(shortlistLabel);

        selectedList = vertical();
        card.addView(selectedList, margins(0, 1, 0, 12));
        renderSelections();

        runButton = accentButton("Run Analysis");
        runButton.setEnabled(false);
        card.addView(runButton, margins(0, 0, 0, 8));

        clearButton = secondaryButton("Clear shortlist");
        card.addView(clearButton);
        runButton.setOnClickListener(view -> runAnalysis());
        clearButton.setOnClickListener(view -> clearAll());
        return card;
    }

    private View buildResultsSection() {
        resultsSection = card(SURFACE);
        resultsSection.setVisibility(View.GONE);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = vertical();
        heading.addView(text("Results", 22, TEXT, Typeface.DEFAULT_BOLD));
        resultsMeta = text("", 10, MUTED, Typeface.MONOSPACE);
        heading.addView(resultsMeta, margins(0, 3, 0, 0));
        header.addView(heading, weightParams(1, 0, 0, 0, 0, 0, 0));
        shareButton = secondaryButton("Share CSV");
        header.addView(shareButton, widthParams(dp(102)));
        shareButton.setOnClickListener(view -> shareCsv());
        resultsSection.addView(header);

        LinearLayout sortRow = horizontal();
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        sortFieldButton = secondaryButton("Sort: Default order");
        sortRow.addView(sortFieldButton, weightParams(1, 0, 0, 0, 0, 0, 0));
        sortDirectionButton = secondaryButton("Low to high");
        sortDirectionButton.setEnabled(false);
        sortRow.addView(sortDirectionButton, wrapMargins(8, 0, 0, 0));
        resultsSection.addView(sortRow, margins(0, 14, 0, 8));
        sortFieldButton.setOnClickListener(view -> cycleSortField());
        sortDirectionButton.setOnClickListener(view -> {
            sortDescending = !sortDescending;
            updateSortControls();
            renderResults();
        });

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        resultsSection.addView(progressBar, centeredMargins(0, 2, 0, 2));
        progressLabel = text("", 11, MUTED, Typeface.MONOSPACE);
        progressLabel.setGravity(Gravity.CENTER);
        resultsSection.addView(progressLabel, margins(0, 3, 0, 10));

        resultsList = vertical();
        resultsSection.addView(resultsList);
        TextView note = text(
                "ZIP codes cover smaller geographies, so months of supply and price-drop metrics may be unavailable or less stable than city- or county-level readings.",
                10,
                MUTED,
                Typeface.MONOSPACE
        );
        note.setLineSpacing(dp(2), 1.0f);
        resultsSection.addView(note, margins(0, 12, 0, 0));
        return resultsSection;
    }

    private View buildFooter() {
        TextView footer = text("Market Scout  •  ©2026 SUPREME AI VENTURES LLC", 10, MUTED, Typeface.MONOSPACE);
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(dp(2), 1.0f);
        return footer;
    }

    private void loadBootstrapData() {
        setInputsEnabled(false);
        dataStatus = text("Connecting to the latest public market dataset...", 11, MUTED, Typeface.MONOSPACE);
        content.addView(dataStatus, 1, margins(0, -8, 0, 14));
        repository.loadBootstrap(new MarketDataRepository.BootstrapCallback() {
            @Override
            public void onSuccess(ManifestSnapshot loadedManifest, MarketSearchIndex loadedSearchIndex) {
                manifest = loadedManifest;
                searchIndex = loadedSearchIndex;
                setInputsEnabled(true);
                String date = formatSourceDate(manifest.latestSourceUpdatedAt);
                freshnessLabel.setText("Redfin updated " + date + "\n" + formatCount(manifest.searchIndexCount) + " markets");
                dataStatus.setText("Ready  •  " + formatCount(manifest.searchIndexCount) + " markets searchable");
                dataStatus.setTextColor(GREEN);
            }

            @Override
            public void onError(Exception error) {
                freshnessLabel.setText("Data unavailable");
                dataStatus.setText("Could not load market data. Check your connection and reopen the app to retry.");
                dataStatus.setTextColor(RED);
                showFeedback("Market data did not load.", true);
            }
        });
    }

    private void setInputsEnabled(boolean enabled) {
        if (marketInput != null) marketInput.setEnabled(enabled);
        if (bulkInput != null) bulkInput.setEnabled(enabled);
        if (addButton != null) addButton.setEnabled(enabled);
        if (bulkAddButton != null) bulkAddButton.setEnabled(enabled);
    }

    private void refreshSuggestions(String query) {
        if (searchIndex == null || query.trim().length() < 2) return;
        List<String> labels = searchIndex.suggestionLabels(query, 10);
        suggestionAdapter.clear();
        suggestionAdapter.addAll(labels);
        suggestionAdapter.notifyDataSetChanged();
        if (!labels.isEmpty() && marketInput.hasFocus()) marketInput.showDropDown();
    }

    private void addFromInput() {
        if (searchIndex == null) return;
        String raw = marketInput.getText().toString().trim();
        if (raw.isEmpty()) {
            showFeedback("Enter a city, ZIP code, or county.", true);
            return;
        }

        SearchItem direct = searchIndex.resolve(raw);
        if (direct != null) {
            addSelection(direct);
            marketInput.setText("");
            return;
        }
        BulkParseResult parsed = searchIndex.parseBulk(raw);
        if (parsed.items.isEmpty()) {
            showFeedback("Choose a market from the suggestions.", true);
            return;
        }
        addBulkResult(parsed);
        marketInput.setText("");
    }

    private void addBulkMarkets(String raw) {
        if (searchIndex == null) return;
        if (raw.trim().isEmpty()) {
            showFeedback("Paste a list of markets to add.", true);
            return;
        }
        BulkParseResult parsed = searchIndex.parseBulk(raw);
        if (parsed.items.isEmpty()) {
            showFeedback("No markets matched. Use city/state, county/state, or a 5-digit ZIP.", true);
            return;
        }
        addBulkResult(parsed);
        bulkInput.setText("");
    }

    private void addBulkResult(BulkParseResult parsed) {
        int added = 0;
        int skipped = 0;
        for (SearchItem item : parsed.items) {
            if (containsSelection(item)) skipped++;
            else {
                selections.add(item);
                added++;
            }
        }
        renderSelections();
        StringBuilder message = new StringBuilder("Added ").append(added).append(" market");
        if (added != 1) message.append('s');
        if (skipped > 0) message.append("  •  ").append(skipped).append(" already added");
        if (!parsed.unmatched.isEmpty()) {
            message.append("  •  Unmatched: ");
            int limit = Math.min(4, parsed.unmatched.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) message.append(", ");
                message.append(parsed.unmatched.get(i));
            }
            if (parsed.unmatched.size() > limit) message.append("...");
            showFeedback(message.toString(), true);
        } else {
            showFeedback(message.toString(), false);
        }
    }

    private void addSelection(SearchItem item) {
        if (containsSelection(item)) {
            showFeedback(item.label + " is already on the shortlist.", true);
            return;
        }
        selections.add(item);
        renderSelections();
        showFeedback(item.label + " added.", false);
    }

    private boolean containsSelection(SearchItem item) {
        for (SearchItem existing : selections) {
            if (existing.identity().equals(item.identity())) return true;
        }
        return false;
    }

    private void renderSelections() {
        if (selectedList == null) return;
        selectedList.removeAllViews();
        shortlistLabel.setText("SHORTLIST (" + selections.size() + ")");
        if (runButton != null) {
            runButton.setEnabled(!selections.isEmpty() && searchIndex != null);
        }
        if (selections.isEmpty()) {
            TextView empty = text("No markets added yet.", 12, MUTED, Typeface.MONOSPACE);
            empty.setGravity(Gravity.CENTER);
            selectedList.addView(empty, margins(0, 12, 0, 12));
            return;
        }
        for (int i = 0; i < selections.size(); i++) {
            SearchItem item = selections.get(i);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(9), dp(8), dp(9));
            row.setBackground(roundRect(BACKGROUND, BORDER, 9));

            LinearLayout labelColumn = vertical();
            labelColumn.addView(text(item.label, 13, TEXT, Typeface.DEFAULT_BOLD));
            labelColumn.addView(text(item.type.toUpperCase(Locale.US) + "  •  " + (item.state.isEmpty() ? "US" : item.state), 10, MUTED, Typeface.MONOSPACE), margins(0, 3, 0, 0));
            row.addView(labelColumn, weightParams(1, 0, 0, 0, 0, 0, 0));

            Button remove = secondaryButton("x");
            remove.setContentDescription("Remove " + item.label);
            remove.setPadding(0, 0, 0, 0);
            row.addView(remove, size(36, 34));
            final int index = i;
            remove.setOnClickListener(view -> {
                selections.remove(index);
                renderSelections();
            });
            selectedList.addView(row, margins(0, 0, 0, 6));
        }
    }

    private void clearAll() {
        selections.clear();
        results.clear();
        renderSelections();
        resultsSection.setVisibility(View.GONE);
        showFeedback("", false);
    }

    private void runAnalysis() {
        if (selections.isEmpty()) return;
        results.clear();
        completedLookups = 0;
        resultsSection.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        runButton.setEnabled(false);
        runButton.setText("Loading markets...");
        progressLabel.setText("Loading 0 of " + selections.size() + " markets...");
        resultsMeta.setText("Building your comparison...");
        runNextLookup(0);
    }

    private void runNextLookup(final int index) {
        if (index >= selections.size()) {
            progressBar.setVisibility(View.GONE);
            progressLabel.setText("Done  •  " + results.size() + " markets compared");
            resultsMeta.setText(formatCount(results.size()) + " markets  •  Redfin source updated " + formatSourceDate(manifest.latestSourceUpdatedAt));
            runButton.setEnabled(!selections.isEmpty());
            runButton.setText("Run Analysis");
            return;
        }

        SearchItem item = selections.get(index);
        progressLabel.setText("Loading " + (index + 1) + " of " + selections.size() + "  •  " + item.label);
        repository.lookup(item, new MarketDataRepository.LookupCallback() {
            @Override
            public void onSuccess(MarketResult result) {
                results.add(result == null ? MarketResult.missing(item, "Not found in static data") : result);
                completedLookups++;
                renderResults();
                runNextLookup(index + 1);
            }

            @Override
            public void onError(Exception error) {
                results.add(MarketResult.missing(item, "Error loading static data"));
                completedLookups++;
                renderResults();
                runNextLookup(index + 1);
            }
        });
    }

    private void cycleSortField() {
        int next = (sortField.ordinal() + 1) % SortField.values().length;
        sortField = SortField.values()[next];
        sortDescending = false;
        updateSortControls();
        renderResults();
    }

    private void updateSortControls() {
        sortFieldButton.setText("Sort: " + sortField.label);
        boolean sortable = sortField != SortField.DEFAULT;
        sortDirectionButton.setEnabled(sortable);
        if (!sortable) sortDirectionButton.setText("Low to high");
        else if (sortField == SortField.MARKET || sortField == SortField.MARKET_TYPE || sortField == SortField.DATE) {
            sortDirectionButton.setText(sortDescending ? "Z to A" : "A to Z");
        } else {
            sortDirectionButton.setText(sortDescending ? "High to low" : "Low to high");
        }
    }

    private void renderResults() {
        if (resultsList == null) return;
        resultsList.removeAllViews();
        List<MarketResult> ordered = new ArrayList<>(results);
        if (sortField != SortField.DEFAULT) Collections.sort(ordered, this::compareResults);
        for (MarketResult result : ordered) {
            resultsList.addView(buildResultCard(result), margins(0, 0, 0, 10));
        }
    }

    private int compareResults(MarketResult left, MarketResult right) {
        Object leftValue = sortableValue(left);
        Object rightValue = sortableValue(right);
        if (leftValue == null && rightValue != null) return 1;
        if (leftValue != null && rightValue == null) return -1;
        int comparison;
        if (leftValue == null) comparison = 0;
        else if (leftValue instanceof String) comparison = ((String) leftValue).compareToIgnoreCase((String) rightValue);
        else comparison = Double.compare((Double) leftValue, (Double) rightValue);
        if (comparison == 0) comparison = left.city.compareToIgnoreCase(right.city);
        return sortDescending ? -comparison : comparison;
    }

    private Object sortableValue(MarketResult result) {
        switch (sortField) {
            case MARKET:
                return result.city;
            case MARKET_TYPE:
                return result.marketTypeLabel();
            case PAR_RATIO:
                return result.parRatio;
            case MONTHS_SUPPLY:
                return result.monthsSupply;
            case MEDIAN_DOM:
                return result.medianDom == null ? null : result.medianDom.doubleValue();
            case MEDIAN_SALE:
                return parseMoney(result.medianSale);
            case SALE_TO_LIST:
                return parsePercent(result.saleToList);
            case SOLD_ABOVE_LIST:
                return parsePercent(result.soldAboveList);
            case PRICE_DROPS:
                return parsePercent(result.priceDrops);
            case OFF_MARKET:
                return parsePercent(result.offMarketTwoWeeks);
            case INCOME:
                return parseMoney(result.householdIncome);
            case DATE:
                return result.period;
            default:
                return null;
        }
    }

    private View buildResultCard(MarketResult result) {
        LinearLayout card = card(BACKGROUND);
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout marketColumn = vertical();
        TextView market = text(result.city, 16, TEXT, Typeface.DEFAULT_BOLD);
        market.setOnClickListener(view -> openExternal(zillowUrl(result.city)));
        market.setContentDescription("Open " + result.city + " on Zillow");
        marketColumn.addView(market);
        marketColumn.addView(text(result.type.toUpperCase(Locale.US), 10, MUTED, Typeface.MONOSPACE), margins(0, 3, 0, 0));
        header.addView(marketColumn, weightParams(1, 0, 0, 0, 0, 0, 0));

        TextView marketType = text(result.marketTypeLabel(), 10, marketTypeTextColor(result.marketTypeLabel()), Typeface.MONOSPACE);
        marketType.setGravity(Gravity.CENTER);
        marketType.setPadding(dp(8), dp(5), dp(8), dp(5));
        marketType.setBackground(roundRect(alphaColor(marketTypeTextColor(result.marketTypeLabel()), 35), 0, 20));
        if (result.redfinUrl != null) marketType.setOnClickListener(view -> openExternal(result.redfinUrl));
        marketType.setContentDescription("Open " + result.city + " on Redfin");
        header.addView(marketType, wrapMargins(8, 0, 0, 0));
        card.addView(header);

        if (!"OK".equals(result.status)) {
            TextView status = text(result.status, 10, YELLOW, Typeface.MONOSPACE);
            card.addView(status, margins(0, 8, 0, 0));
        }

        LinearLayout metrics = vertical();
        addMetricRow(metrics,
                "MEDIAN SALE", display(result.medianSale),
                "MONTHS SUPPLY", formatMonthsSupply(result.monthsSupply));
        addMetricRow(metrics,
                "AVG DOM", result.medianDom == null ? "—" : result.medianDom + " days",
                "PAR RATIO", formatPar(result));
        addMetricRow(metrics,
                "SALE VS LIST", display(result.saleToList),
                "% SOLD ABOVE", display(result.soldAboveList));
        addMetricRow(metrics,
                "PRICE DROPS", display(result.priceDrops),
                "OFF MKT 2WK", display(result.offMarketTwoWeeks));
        addMetricRow(metrics,
                "AVG HH INCOME", display(result.householdIncome),
                "DATA DATE", display(result.period));
        card.addView(metrics, margins(0, 12, 0, 10));

        LinearLayout actions = horizontal();
        Button zillow = secondaryButton("Open Zillow");
        Button redfin = secondaryButton("Open Redfin");
        actions.addView(zillow, weightParams(1, 0, 0, 0, 0, 0, 0));
        actions.addView(redfin, weightParams(1, 0, 0, 0, 8, 0, 0));
        zillow.setOnClickListener(view -> openExternal(zillowUrl(result.city)));
        redfin.setEnabled(result.redfinUrl != null);
        redfin.setOnClickListener(view -> openExternal(result.redfinUrl));
        card.addView(actions);
        return card;
    }

    private void addMetricRow(LinearLayout parent, String firstLabel, String firstValue, String secondLabel, String secondValue) {
        LinearLayout row = horizontal();
        row.addView(metricCell(firstLabel, firstValue), weightParams(1, 0, 0, 0, 0, 0, 0));
        row.addView(metricCell(secondLabel, secondValue), weightParams(1, 0, 0, 0, 8, 0, 0));
        parent.addView(row, margins(0, 0, 0, 8));
    }

    private View metricCell(String label, String value) {
        LinearLayout cell = vertical();
        cell.setPadding(dp(10), dp(9), dp(10), dp(9));
        cell.setBackground(roundRect(SURFACE_2, BORDER, 9));
        cell.addView(text(label, 9, MUTED, Typeface.MONOSPACE));
        TextView valueView = text(value, 13, metricValueColor(label, value), Typeface.DEFAULT_BOLD);
        cell.addView(valueView, margins(0, 5, 0, 0));
        return cell;
    }

    private int metricValueColor(String label, String value) {
        if ("SALE VS LIST".equals(label)) {
            if (value.startsWith("+")) return GREEN;
            if (value.startsWith("-")) return RED;
        }
        if ("AVG DOM".equals(label)) {
            Double days = parseNumber(value);
            if (days != null && days > 60) return RED;
        }
        return TEXT;
    }

    private String formatPar(MarketResult result) {
        if (result.parRatio == null) return "—";
        String ratio = formatNumber(result.parRatio) + "%";
        if (result.parPending != null && result.parTotal != null) {
            ratio += " (" + result.parPending + "p/" + result.parTotal + "t)";
        }
        return ratio;
    }

    private String formatMonthsSupply(Double value) {
        return value == null ? "—" : formatNumber(value) + " mo";
    }

    private String display(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private void shareCsv() {
        if (results.isEmpty()) return;
        List<MarketResult> ordered = new ArrayList<>(results);
        if (sortField != SortField.DEFAULT) Collections.sort(ordered, this::compareResults);
        StringBuilder csv = new StringBuilder();
        csv.append("Market,Market Type,PAR Ratio,Months Supply,Avg DOM,Median Sale,Sale vs List,% Sold Above,Price Drops,Off Mkt 2wk,Avg HH Income,Data Date\n");
        for (MarketResult result : ordered) {
            csv.append(csvCell(result.city)).append(',')
                    .append(csvCell(result.marketTypeLabel())).append(',')
                    .append(csvCell(formatPar(result))).append(',')
                    .append(csvCell(formatMonthsSupply(result.monthsSupply))).append(',')
                    .append(csvCell(result.medianDom == null ? "" : result.medianDom + " days")).append(',')
                    .append(csvCell(display(result.medianSale))).append(',')
                    .append(csvCell(display(result.saleToList))).append(',')
                    .append(csvCell(display(result.soldAboveList))).append(',')
                    .append(csvCell(display(result.priceDrops))).append(',')
                    .append(csvCell(display(result.offMarketTwoWeeks))).append(',')
                    .append(csvCell(display(result.householdIncome))).append(',')
                    .append(csvCell(display(result.period))).append('\n');
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_TEXT, csv.toString());
        try {
            startActivity(Intent.createChooser(share, "Share Market Scout results"));
        } catch (ActivityNotFoundException error) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Market Scout CSV", csv.toString()));
            Toast.makeText(this, "CSV copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private String csvCell(String value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private void showFeedback(String message, boolean warning) {
        if (feedback == null) return;
        feedback.setText(message);
        feedback.setTextColor(warning ? YELLOW : GREEN);
    }

    private void openExternal(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private String zillowUrl(String market) {
        String slug = market.toLowerCase(Locale.US)
                .replace(",", "")
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slug.isEmpty() ? null : "https://www.zillow.com/homes/" + slug + "/";
    }

    private int marketTypeTextColor(String label) {
        String value = label.toLowerCase(Locale.US);
        if (value.contains("hot") || value.contains("seller")) return GREEN;
        if (value.contains("balanced")) return YELLOW;
        if (value.contains("buyer")) return RED;
        return MUTED;
    }

    private int alphaColor(int color, int alpha) {
        return Color.argb(alpha * 255 / 100, Color.red(color), Color.green(color), Color.blue(color));
    }

    private Double parseMoney(String value) {
        if (value == null || value.isEmpty() || "—".equals(value)) return null;
        String normalized = value.replace("$", "").replace(",", "").trim();
        try {
            if (normalized.toLowerCase(Locale.US).endsWith("m")) {
                return Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 1_000_000;
            }
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parsePercent(String value) {
        if (value == null || value.isEmpty() || "—".equals(value)) return null;
        try {
            return Double.parseDouble(value.replace("%", "").replace("+", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseNumber(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("[^0-9.-]", "");
        if (digits.isEmpty()) return null;
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatSourceDate(String value) {
        if (value == null || value.length() < 10) return "unknown";
        String[] pieces = value.substring(0, 10).split("-");
        if (pieces.length != 3) return value;
        try {
            int month = Integer.parseInt(pieces[1]);
            String[] months = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
            return months[month] + " " + Integer.parseInt(pieces[2]) + ", " + pieces[0];
        } catch (Exception ignored) {
            return value;
        }
    }

    private String formatCount(int count) {
        return String.format(Locale.US, "%,d", count);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout card(int color) {
        LinearLayout layout = vertical();
        layout.setPadding(dp(14), dp(15), dp(14), dp(15));
        layout.setBackground(roundRect(color, BORDER, 15));
        return layout;
    }

    private TextView sectionLabel(String value) {
        return text(value, 10, MUTED, Typeface.MONOSPACE);
    }

    private TextView text(String value, float size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = button(value, ACCENT, BACKGROUND);
        button.setMinWidth(dp(68));
        return button;
    }

    private Button accentButton(String value) {
        return button(value, ACCENT, BACKGROUND);
    }

    private Button secondaryButton(String value) {
        return button(value, BACKGROUND, MUTED);
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setBackground(roundRect(background, background == BACKGROUND ? BORDER : 0, 10));
        return button;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams centeredMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = margins(left, top, right, bottom);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private LinearLayout.LayoutParams wrapMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams widthParams(int width) {
        return new LinearLayout.LayoutParams(width == ViewGroup.LayoutParams.MATCH_PARENT ? width : dp(width), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(dp(width), dp(height));
    }

    private LinearLayout.LayoutParams weightParams(float weight, int left, int top, int right, int marginLeft, int marginTop, int marginRight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(marginLeft), dp(marginTop), dp(marginRight), dp(0));
        return params;
    }

    private final class SuggestionAdapter extends ArrayAdapter<String> {
        SuggestionAdapter(Context context) {
            super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            view.setTextColor(TEXT);
            view.setTextSize(13);
            view.setPadding(dp(13), dp(11), dp(13), dp(11));
            view.setBackgroundColor(SURFACE_2);
            return view;
        }
    }
}
