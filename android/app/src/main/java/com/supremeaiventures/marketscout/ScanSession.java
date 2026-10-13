package com.supremeaiventures.marketscout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ScanSession {
    private long generation;
    private boolean active;
    private List<SearchItem> markets = Collections.emptyList();

    long begin(List<SearchItem> selected) {
        markets = Collections.unmodifiableList(new ArrayList<>(selected));
        active = !markets.isEmpty();
        return ++generation;
    }

    boolean accepts(long token) {
        return active && token == generation;
    }

    boolean isActive() {
        return active;
    }

    List<SearchItem> markets() {
        return markets;
    }

    void finish(long token) {
        if (accepts(token)) active = false;
    }

    void cancel() {
        generation++;
        active = false;
    }
}
