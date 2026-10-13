package com.supremeaiventures.marketscout;

import java.util.Comparator;
import java.util.Locale;

enum MarketSort {
    DEFAULT("Shortlist order"), MARKET("Market"), MARKET_TYPE("Market type"),
    PAR_RATIO("PAR ratio"), MONTHS_SUPPLY("Months of supply"), MEDIAN_DOM("Median days on market"),
    MEDIAN_SALE("Median sale price"), SALE_TO_LIST("Sale vs. list"), SOLD_ABOVE_LIST("Sold above list"),
    PRICE_DROPS("Price drops"), OFF_MARKET("Off market in 2 weeks"), INCOME("Median household income"),
    DATE("Reporting period");

    final String label;

    MarketSort(String label) {
        this.label = label;
    }

    Comparator<MarketResult> comparator(boolean descending) {
        return (left, right) -> {
            Object a = value(left);
            Object b = value(right);
            if (a == null && b != null) return 1;
            if (a != null && b == null) return -1;
            int order = a == null ? 0 : a instanceof String
                    ? ((String) a).compareToIgnoreCase((String) b)
                    : Double.compare((Double) a, (Double) b);
            if (order != 0) return descending ? -order : order;
            return left.city.compareToIgnoreCase(right.city);
        };
    }

    private Object value(MarketResult result) {
        switch (this) {
            case MARKET: return result.city;
            case MARKET_TYPE: return result.marketTypeLabel();
            case PAR_RATIO: return result.parRatio;
            case MONTHS_SUPPLY: return result.monthsSupply;
            case MEDIAN_DOM: return result.medianDom == null ? null : result.medianDom.doubleValue();
            case MEDIAN_SALE: return number(result.medianSale);
            case SALE_TO_LIST: return number(result.saleToList);
            case SOLD_ABOVE_LIST: return number(result.soldAboveList);
            case PRICE_DROPS: return number(result.priceDrops);
            case OFF_MARKET: return number(result.offMarketTwoWeeks);
            case INCOME: return number(result.householdIncome);
            case DATE: return result.period;
            default: return null;
        }
    }

    static Double number(String value) {
        if (value == null) return null;
        String cleaned = value.trim().toLowerCase(Locale.US).replace("$", "")
                .replace(",", "").replace("%", "").replace("+", "");
        double multiplier = 1;
        if (cleaned.endsWith("m") || cleaned.endsWith("k")) {
            multiplier = cleaned.endsWith("m") ? 1_000_000 : 1_000;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        try {
            double number = Double.parseDouble(cleaned) * multiplier;
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
