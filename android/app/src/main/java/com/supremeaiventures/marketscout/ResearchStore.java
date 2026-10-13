package com.supremeaiventures.marketscout;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

final class ResearchStore {
    private final SharedPreferences preferences;

    ResearchStore(Context context) {
        preferences = context.getSharedPreferences("market_research", Context.MODE_PRIVATE);
    }

    ResearchState load() {
        String saved = preferences.getString("research", "{}");
        try {
            return ResearchState.fromJson(new JSONObject(saved));
        } catch (Exception error) {
            // Keep a damaged record for recovery instead of silently overwriting it.
            preferences.edit().putString("recovery", saved).apply();
            return new ResearchState();
        }
    }

    void save(ResearchState state) {
        try {
            preferences.edit().putString("research", state.toJson().toString()).apply();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not save research", error);
        }
    }
}
