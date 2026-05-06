package com.lss.onmyplatemobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "PlannerWidget")
public class PlannerWidgetPlugin extends Plugin {
    static final String PREFS_NAME = "planner_widget_prefs";
    static final String KEY_SUMMARY_SNAPSHOT = "summary_snapshot";
    static final String KEY_PENDING_ROUTE = "pending_route";

    static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static void savePendingRoute(Context context, String route) {
        getPrefs(context).edit().putString(KEY_PENDING_ROUTE, route).apply();
    }

    @PluginMethod
    public void saveSummarySnapshot(PluginCall call) {
        String snapshotJson = call.getString("snapshotJson", "{}");
        Context context = getContext();
        getPrefs(context).edit().putString(KEY_SUMMARY_SNAPSHOT, snapshotJson).apply();
        SummaryWidgetProvider.refreshAll(context);
        call.resolve();
    }

    @PluginMethod
    public void consumeLaunchRoute(PluginCall call) {
        SharedPreferences prefs = getPrefs(getContext());
        String route = prefs.getString(KEY_PENDING_ROUTE, null);
        if (route != null) {
            prefs.edit().remove(KEY_PENDING_ROUTE).apply();
        }

        JSObject result = new JSObject();
        if (route != null) {
            result.put("route", route);
        }
        call.resolve(result);
    }
}
