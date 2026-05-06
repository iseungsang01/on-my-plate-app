package com.lss.onmyplate.nativeplanner.widget;

import android.content.Context;
import android.content.SharedPreferences;

public final class PlannerWidgetStore {
    static final String PREFS_NAME = "planner_widget_prefs";
    public static final String KEY_SUMMARY_SNAPSHOT = "summary_snapshot";
    private static final String KEY_PENDING_ROUTE = "pending_route";

    private PlannerWidgetStore() {}

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void savePendingRoute(Context context, String route) {
        getPrefs(context).edit().putString(KEY_PENDING_ROUTE, route).apply();
    }

    public static void saveSummarySnapshot(Context context, String snapshotJson) {
        getPrefs(context).edit().putString(KEY_SUMMARY_SNAPSHOT, snapshotJson).apply();
        SummaryWidgetProvider.refreshAll(context);
    }
}
