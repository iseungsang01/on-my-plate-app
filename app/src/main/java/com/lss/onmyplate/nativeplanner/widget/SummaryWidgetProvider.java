package com.lss.onmyplate.nativeplanner.widget;

import com.lss.onmyplate.nativeplanner.R;
import com.lss.onmyplate.nativeplanner.ui.MainActivity;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SummaryWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_OPEN_PLANNER = "com.lss.onmyplate.nativeplanner.action.OPEN_PLANNER";
    private static final String ACTION_PREVIOUS_WEEK = "com.lss.onmyplate.nativeplanner.action.WIDGET_PREVIOUS_WEEK";
    private static final String ACTION_NEXT_WEEK = "com.lss.onmyplate.nativeplanner.action.WIDGET_NEXT_WEEK";
    private static final String ACTION_TOGGLE_VIEWPORT = "com.lss.onmyplate.nativeplanner.action.WIDGET_TOGGLE_VIEWPORT";
    private static final String ACTION_REFRESH = "com.lss.onmyplate.nativeplanner.action.WIDGET_REFRESH";

    private static final int DEFAULT_WIDTH_DP = 320;
    private static final int DEFAULT_HEIGHT_DP = 220;
    private static final int FALLBACK_START_MINUTE = 8 * 60;
    private static final int FALLBACK_END_MINUTE = 24 * 60;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        PlannerWidgetSync.INSTANCE.syncFromPlannerApiSnapshot(context);
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetManager, appWidgetId));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        PlannerWidgetSync.INSTANCE.syncFromPlannerApiSnapshot(context);
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetManager, appWidgetId));
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        SharedPreferences prefs = PlannerWidgetStore.getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (int appWidgetId : appWidgetIds) {
            editor.remove(getWeekOffsetKey(appWidgetId));
            editor.remove(getViewportShrunkKey(appWidgetId));
        }
        editor.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;

        String action = intent.getAction();
        int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        if (ACTION_OPEN_PLANNER.equals(action)) {
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launchIntent);
            return;
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;

        if (ACTION_PREVIOUS_WEEK.equals(action)) {
            updateWeekOffset(context, appWidgetId, -1);
        } else if (ACTION_NEXT_WEEK.equals(action)) {
            updateWeekOffset(context, appWidgetId, 1);
        } else if (ACTION_TOGGLE_VIEWPORT.equals(action)) {
            SharedPreferences prefs = PlannerWidgetStore.getPrefs(context);
            boolean isShrunk = prefs.getBoolean(getViewportShrunkKey(appWidgetId), false);
            prefs.edit().putBoolean(getViewportShrunkKey(appWidgetId), !isShrunk).apply();
        } else if (ACTION_REFRESH.equals(action)) {
            int weekOffset = PlannerWidgetStore.getPrefs(context).getInt(getWeekOffsetKey(appWidgetId), 0);
            PlannerWidgetSync.INSTANCE.syncFromPlannerApiSnapshot(context, true, weekOffset);
        } else {
            return;
        }

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidget(appWidgetId, buildRemoteViews(context, manager, appWidgetId));
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, SummaryWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        for (int appWidgetId : ids) {
            manager.updateAppWidget(appWidgetId, buildRemoteViews(context, manager, appWidgetId));
        }
    }

    private static RemoteViews buildRemoteViews(Context context, AppWidgetManager manager, int appWidgetId) {
        SharedPreferencesSnapshot snapshot = SharedPreferencesSnapshot.from(context);
        WidgetState state = WidgetState.from(context, appWidgetId, snapshot);
        List<DaySnapshot> days = snapshot.buildWeekDays(state.weekOffset);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.summary_widget);

        views.setTextViewText(R.id.widget_week, formatWeekLabel(snapshot.getWeekStartForOffset(state.weekOffset)));
        views.setImageViewBitmap(R.id.widget_timetable, renderTimetableBitmap(context, manager, appWidgetId, state, days));

        bindAction(context, views, appWidgetId, R.id.summary_widget_root, ACTION_OPEN_PLANNER);
        bindAction(context, views, appWidgetId, R.id.widget_week_prev, ACTION_PREVIOUS_WEEK);
        bindAction(context, views, appWidgetId, R.id.widget_week_next, ACTION_NEXT_WEEK);
        bindAction(context, views, appWidgetId, R.id.widget_viewport_toggle, ACTION_TOGGLE_VIEWPORT);
        bindAction(context, views, appWidgetId, R.id.widget_refresh, ACTION_REFRESH);

        return views;
    }

    private static void bindAction(Context context, RemoteViews views, int appWidgetId, int viewId, String action) {
        Intent intent = new Intent(context, SummaryWidgetProvider.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.setData(android.net.Uri.parse("planner-widget://" + action + "/" + appWidgetId));

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + viewId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(viewId, pendingIntent);
    }

    private static Bitmap renderTimetableBitmap(
        Context context,
        AppWidgetManager manager,
        int appWidgetId,
        WidgetState state,
        List<DaySnapshot> days
    ) {
        WidgetSize size = getWidgetSize(context, manager, appWidgetId);
        int width = Math.max(dpToPx(context, 244), size.widthPx - dpToPx(context, 30));
        int height = Math.max(dpToPx(context, 128), size.heightPx - dpToPx(context, 64));

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        float density = context.getResources().getDisplayMetrics().density;
        float outerRadius = 18f * density;
        float railWidth = 26f * density;
        float headerHeight = 22f * density;
        float gridPadding = 6f * density;
        float bodyTop = headerHeight + (6f * density);
        float bodyHeight = Math.max(1f, height - bodyTop - gridPadding);
        float dayColumnWidth = Math.max(1f, (width - railWidth - gridPadding) / 7f);
        float gridLeft = railWidth;
        float gridRight = width;

        Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        panelPaint.setColor(Color.parseColor("#FFFFFF"));
        panelPaint.setAlpha(238);
        canvas.drawRoundRect(new RectF(0, 0, width, height), outerRadius, outerRadius, panelPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, density));
        borderPaint.setColor(Color.parseColor("#E8D9C7"));
        canvas.drawRoundRect(new RectF(0.5f, 0.5f, width - 0.5f, height - 0.5f), outerRadius, outerRadius, borderPaint);

        TextPaint weekdayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        weekdayPaint.setColor(Color.parseColor("#2F2924"));
        weekdayPaint.setTextSize(10f * density);
        weekdayPaint.setFakeBoldText(true);
        weekdayPaint.setTextAlign(Paint.Align.CENTER);

        TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        datePaint.setColor(Color.parseColor("#6F6258"));
        datePaint.setTextSize(9f * density);
        datePaint.setTextAlign(Paint.Align.CENTER);

        TextPaint hourPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        hourPaint.setColor(Color.parseColor("#A89C91"));
        hourPaint.setTextSize(8.5f * density);

        Paint gridLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridLinePaint.setColor(Color.parseColor("#E8D9C7"));
        gridLinePaint.setStrokeWidth(Math.max(1f, density * 0.8f));

        Paint fineLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fineLinePaint.setColor(Color.parseColor("#F1E8DD"));
        fineLinePaint.setStrokeWidth(Math.max(1f, density * 0.6f));

        Paint dayHeaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dayHeaderPaint.setColor(Color.parseColor("#FFF3E0"));
        dayHeaderPaint.setAlpha(235);

        canvas.drawRoundRect(new RectF(gridLeft, 0, gridRight, headerHeight), 12f * density, 12f * density, dayHeaderPaint);

        for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
            DaySnapshot day = days.get(dayIndex);
            float columnLeft = gridLeft + (dayIndex * dayColumnWidth);
            float columnRight = columnLeft + dayColumnWidth;
            float columnCenter = (columnLeft + columnRight) / 2f;

            if (dayIndex > 0) {
                canvas.drawLine(columnLeft, 0, columnLeft, height - gridPadding, fineLinePaint);
            }

            canvas.drawText(day.weekdayLabel, columnCenter, 10.5f * density, weekdayPaint);
            canvas.drawText(day.dayNumber, columnCenter, 19f * density, datePaint);
        }

        int startHour = state.viewportStartMinute / 60;
        int endHour = state.viewportEndMinute / 60;
        float minuteScale = bodyHeight / Math.max(60f, (float) (state.viewportEndMinute - state.viewportStartMinute));

        for (int hour = startHour; hour <= endHour; hour++) {
            float y = bodyTop + ((hour * 60f) - state.viewportStartMinute) * minuteScale;
            if (y < bodyTop || y > height - gridPadding) continue;

            canvas.drawLine(gridLeft, y, gridRight, y, gridLinePaint);
            canvas.drawText(formatHourLabel(hour), 0, 3, 2f * density, y + (3f * density), hourPaint);
        }

        boolean hasItems = false;
        for (DaySnapshot day : days) {
            if (!day.items.isEmpty()) {
                hasItems = true;
                break;
            }
        }

        if (!hasItems) {
            TextPaint emptyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            emptyPaint.setColor(Color.parseColor("#6F6258"));
            emptyPaint.setTextSize(11f * density);
            emptyPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No plans this week", width / 2f, bodyTop + (bodyHeight / 2f), emptyPaint);
            return bitmap;
        }

        Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint blockStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blockStrokePaint.setStyle(Paint.Style.STROKE);
        blockStrokePaint.setStrokeWidth(Math.max(1f, density * 0.8f));

        TextPaint blockTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        blockTimePaint.setColor(Color.parseColor("#7A4A1B"));
        blockTimePaint.setTextSize(6.8f * density);
        blockTimePaint.setFakeBoldText(true);

        TextPaint blockTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        blockTitlePaint.setColor(Color.parseColor("#2F2924"));
        blockTitlePaint.setTextSize(7.8f * density);
        blockTitlePaint.setFakeBoldText(true);

        for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
            DaySnapshot day = days.get(dayIndex);
            float columnLeft = gridLeft + (dayIndex * dayColumnWidth);
            List<EventLayout> layouts = buildEventLayouts(day.items);
            int laneCount = 1;
            for (EventLayout layout : layouts) {
                laneCount = Math.max(laneCount, layout.laneCount);
            }

            for (EventLayout layout : layouts) {
                float laneWidth = dayColumnWidth / laneCount;
                float left = columnLeft + (layout.lane * laneWidth) + (2f * density);
                float right = left + laneWidth - (4f * density);
                int boundedStart = Math.max(state.viewportStartMinute, layout.item.startMinute);
                int boundedEnd = Math.min(state.viewportEndMinute, layout.item.endMinute);
                if (boundedEnd <= state.viewportStartMinute || boundedStart >= state.viewportEndMinute) {
                    continue;
                }

                float top = bodyTop + ((boundedStart - state.viewportStartMinute) * minuteScale) + (2f * density);
                float bottom = bodyTop + ((boundedEnd - state.viewportStartMinute) * minuteScale) - (2f * density);
                float minHeight = 20f * density;
                if ((bottom - top) < minHeight) {
                    bottom = Math.min(height - gridPadding - (2f * density), top + minHeight);
                }

                RectF blockRect = new RectF(left, top, right, bottom);
                int fillColor = "auto".equals(layout.item.source)
                    ? Color.parseColor("#EAF7ED")
                    : Color.parseColor("#FFE3C2");
                int strokeColor = "auto".equals(layout.item.source)
                    ? Color.parseColor("#B9D8C2")
                    : Color.parseColor("#F4A261");
                blockPaint.setColor(fillColor);
                blockStrokePaint.setColor(strokeColor);
                canvas.drawRoundRect(blockRect, 8f * density, 8f * density, blockPaint);
                canvas.drawRoundRect(blockRect, 8f * density, 8f * density, blockStrokePaint);

                float textPadding = 5f * density;
                float textWidth = Math.max(0f, blockRect.width() - (textPadding * 2f));
                float titleY = blockRect.top + (9f * density);
                String titleText = fitWidgetText(buildWidgetTitle(layout.item), blockTitlePaint, textWidth);
                canvas.drawText(titleText, blockRect.left + textPadding, titleY, blockTitlePaint);

                if (blockRect.height() >= (24f * density)) {
                    float timeY = blockRect.top + (18f * density);
                    String timeText = fitWidgetText(formatCompactRange(layout.item.startMinute, layout.item.endMinute), blockTimePaint, textWidth);
                    canvas.drawText(timeText, blockRect.left + textPadding, timeY, blockTimePaint);
                }
            }
        }

        return bitmap;
    }

    private static WidgetSize getWidgetSize(Context context, AppWidgetManager manager, int appWidgetId) {
        Bundle options = manager.getAppWidgetOptions(appWidgetId);
        int widthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP) : DEFAULT_WIDTH_DP;
        int heightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP) : DEFAULT_HEIGHT_DP;
        return new WidgetSize(dpToPx(context, widthDp), dpToPx(context, heightDp));
    }

    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private static String formatHourLabel(int hour) {
        int normalized = ((hour % 24) + 24) % 24;
        return String.format(Locale.US, "%02d:00", normalized);
    }

    private static String formatMinute(int minute) {
        int bounded = Math.max(0, Math.min(24 * 60, minute));
        int hours = bounded / 60;
        int mins = bounded % 60;
        return String.format(Locale.US, "%02d:%02d", hours, mins);
    }

    private static String fitWidgetText(String text, TextPaint paint, float width) {
        if (width <= 0f || text == null) return "";
        if (paint.measureText(text) <= width) return text;

        String ellipsized = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString();
        String visibleChars = ellipsized.replace(".", "").replace("\u2026", "").trim();
        if (!visibleChars.isEmpty()) {
            return ellipsized;
        }

        int fallbackChars = Math.max(1, Math.min(text.length(), Math.round(width / Math.max(1f, paint.measureText("8")))));
        return text.substring(0, fallbackChars);
    }

    private static String buildWidgetTitle(ScheduleItemSnapshot item) {
        String suffix = item.isRecurring ? " (P)" : "";
        return item.title + suffix;
    }

    private static String formatCompactRange(int startMinute, int endMinute) {
        return formatCompactMinute(startMinute) + "-" + formatCompactMinute(endMinute);
    }

    private static String formatCompactMinute(int minute) {
        int bounded = Math.max(0, Math.min(24 * 60, minute));
        int hours = bounded / 60;
        int mins = bounded % 60;
        if (mins == 0) {
            return String.format(Locale.US, "%02d", hours);
        }
        return String.format(Locale.US, "%02d:%02d", hours, mins);
    }

    private static List<EventLayout> buildEventLayouts(List<ScheduleItemSnapshot> items) {
        ArrayList<EventLayout> layouts = new ArrayList<>();
        ArrayList<Integer> laneEndMinutes = new ArrayList<>();
        int laneCount = 0;

        for (ScheduleItemSnapshot item : items) {
            int lane = 0;
            while (lane < laneEndMinutes.size() && laneEndMinutes.get(lane) > item.startMinute) {
                lane += 1;
            }

            if (lane == laneEndMinutes.size()) {
                laneEndMinutes.add(item.endMinute);
            } else {
                laneEndMinutes.set(lane, item.endMinute);
            }

            laneCount = Math.max(laneCount, lane + 1);
            layouts.add(new EventLayout(item, lane, 1));
        }

        for (EventLayout layout : layouts) {
            layout.laneCount = Math.max(1, laneCount);
        }

        return layouts;
    }

    private static String getWeekOffsetKey(int appWidgetId) {
        return "summary_widget_week_offset_" + appWidgetId;
    }

    private static String getViewportShrunkKey(int appWidgetId) {
        return "summary_widget_viewport_shrunk_" + appWidgetId;
    }

    private static void updateWeekOffset(Context context, int appWidgetId, int delta) {
        SharedPreferences prefs = PlannerWidgetStore.getPrefs(context);
        int current = prefs.getInt(getWeekOffsetKey(appWidgetId), 0);
        prefs.edit().putInt(getWeekOffsetKey(appWidgetId), current + delta).apply();
    }

    private static String formatWeekLabel(Calendar weekStart) {
        Calendar weekEnd = (Calendar) weekStart.clone();
        weekEnd.add(Calendar.DAY_OF_MONTH, 6);

        boolean sameMonth = weekStart.get(Calendar.MONTH) == weekEnd.get(Calendar.MONTH)
            && weekStart.get(Calendar.YEAR) == weekEnd.get(Calendar.YEAR);
        String startLabel = formatMonthDay(weekStart, true);
        String endLabel = formatMonthDay(weekEnd, !sameMonth);
        return startLabel + " - " + endLabel;
    }

    private static String formatMonthDay(Calendar calendar, boolean includeMonth) {
        if (includeMonth) {
            return String.format(
                Locale.US,
                "%s %d",
                calendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US),
                calendar.get(Calendar.DAY_OF_MONTH)
            );
        }
        return String.format(Locale.US, "%d", calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static Calendar parseDate(String dateStr) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if (dateStr == null || dateStr.length() < 10) {
            return calendar;
        }

        try {
            int year = Integer.parseInt(dateStr.substring(0, 4));
            int month = Integer.parseInt(dateStr.substring(5, 7));
            int day = Integer.parseInt(dateStr.substring(8, 10));
            calendar.set(year, month - 1, day, 0, 0, 0);
            calendar.set(Calendar.MILLISECOND, 0);
        } catch (Exception ignored) {
        }
        return calendar;
    }

    private static String formatDate(Calendar calendar) {
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        );
    }

    private static class WidgetSize {
        final int widthPx;
        final int heightPx;

        WidgetSize(int widthPx, int heightPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
    }

    private static class EventLayout {
        final ScheduleItemSnapshot item;
        final int lane;
        int laneCount;

        EventLayout(ScheduleItemSnapshot item, int lane, int laneCount) {
            this.item = item;
            this.lane = lane;
            this.laneCount = laneCount;
        }
    }

    private static class WidgetState {
        final int weekOffset;
        final int viewportStartMinute;
        final int viewportEndMinute;

        WidgetState(int weekOffset, int viewportStartMinute, int viewportEndMinute) {
            this.weekOffset = weekOffset;
            this.viewportStartMinute = viewportStartMinute;
            this.viewportEndMinute = viewportEndMinute;
        }

        static WidgetState from(Context context, int appWidgetId, SharedPreferencesSnapshot snapshot) {
            SharedPreferences prefs = PlannerWidgetStore.getPrefs(context);
            // Default offset to 0 if not set, instead of using snapshot.weekOffset which may carry over incorrectly
            int weekOffset = prefs.getInt(getWeekOffsetKey(appWidgetId), 0);
            boolean isShrunk = prefs.getBoolean(getViewportShrunkKey(appWidgetId), false);
            
            return new WidgetState(
                weekOffset,
                8 * 60, // Start always at 08:00
                isShrunk ? 18 * 60 : 24 * 60 // End at 18:00 or 24:00
            );
        }
    }

    private static class SharedPreferencesSnapshot {
        final String weekStartDate;
        final int weekOffset;
        final int viewportStartMinute;
        final int viewportEndMinute;
        final Map<String, List<ScheduleItemSnapshot>> manualEventsByDate;

        SharedPreferencesSnapshot(
            String weekStartDate,
            int weekOffset,
            int viewportStartMinute,
            int viewportEndMinute,
            Map<String, List<ScheduleItemSnapshot>> manualEventsByDate
        ) {
            this.weekStartDate = weekStartDate;
            this.weekOffset = weekOffset;
            this.viewportStartMinute = viewportStartMinute;
            this.viewportEndMinute = viewportEndMinute;
            this.manualEventsByDate = manualEventsByDate;
        }

        Calendar getWeekStartForOffset(int offset) {
            Calendar calendar = parseDate(weekStartDate);
            calendar.add(Calendar.DAY_OF_MONTH, offset * 7);
            return calendar;
        }

        List<DaySnapshot> buildWeekDays(int offset) {
            Calendar weekStart = getWeekStartForOffset(offset);
            ArrayList<DaySnapshot> days = new ArrayList<>();
            String[] weekdayLabels = new String[] {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

            for (int index = 0; index < 7; index++) {
                Calendar date = (Calendar) weekStart.clone();
                date.add(Calendar.DAY_OF_MONTH, index);
                String dateStr = formatDate(date);
                List<ScheduleItemSnapshot> merged = new ArrayList<>();

                List<ScheduleItemSnapshot> manualItems = manualEventsByDate.get(dateStr);
                if (manualItems != null) {
                    merged.addAll(manualItems);
                }

                Collections.sort(merged, new Comparator<ScheduleItemSnapshot>() {
                    @Override
                    public int compare(ScheduleItemSnapshot left, ScheduleItemSnapshot right) {
                        if (left.startMinute != right.startMinute) {
                            return left.startMinute - right.startMinute;
                        }
                        if (left.endMinute != right.endMinute) {
                            return left.endMinute - right.endMinute;
                        }
                        return left.title.compareTo(right.title);
                    }
                });

                days.add(new DaySnapshot(
                    weekdayLabels[index],
                    String.format(Locale.US, "%02d", date.get(Calendar.DAY_OF_MONTH)),
                    merged
                ));
            }

            return days;
        }

        static SharedPreferencesSnapshot from(Context context) {
            try {
                String raw = PlannerWidgetStore.getPrefs(context).getString(PlannerWidgetStore.KEY_SUMMARY_SNAPSHOT, "{}");
                JSONObject json = new JSONObject(raw);
                JSONObject manualEventsByDateJson = json.optJSONObject("manualEventsByDate");

                HashMap<String, List<ScheduleItemSnapshot>> manualEventsByDate = new HashMap<>();
                if (manualEventsByDateJson != null) {
                    JSONArray keys = manualEventsByDateJson.names();
                    if (keys != null) {
                        for (int index = 0; index < keys.length(); index++) {
                            String dateKey = keys.optString(index, "");
                            JSONArray itemsJson = manualEventsByDateJson.optJSONArray(dateKey);
                            ArrayList<ScheduleItemSnapshot> items = new ArrayList<>();
                            if (itemsJson != null) {
                                for (int itemIndex = 0; itemIndex < itemsJson.length(); itemIndex++) {
                                    ScheduleItemSnapshot item = ScheduleItemSnapshot.from(itemsJson.optJSONObject(itemIndex));
                                    if (item != null) {
                                        items.add(item);
                                    }
                                }
                            }
                            manualEventsByDate.put(dateKey, items);
                        }
                    }
                }

                int parsedStart = json.optInt("viewportStartMinute", FALLBACK_START_MINUTE);
                int parsedEnd = json.optInt("viewportEndMinute", FALLBACK_END_MINUTE);
                if (parsedEnd <= parsedStart) {
                    parsedStart = FALLBACK_START_MINUTE;
                    parsedEnd = FALLBACK_END_MINUTE;
                }

                return new SharedPreferencesSnapshot(
                    json.optString("weekStart", formatDate(parseDate(null))),
                    0, // Always 0 from JS fallback
                    FALLBACK_START_MINUTE,
                    FALLBACK_END_MINUTE,
                    manualEventsByDate
                );
            } catch (Exception ignored) {
                return new SharedPreferencesSnapshot(
                    formatDate(parseDate(null)),
                    0,
                    FALLBACK_START_MINUTE,
                    FALLBACK_END_MINUTE,
                    new HashMap<String, List<ScheduleItemSnapshot>>()
                );
            }
        }
    }

    private static class DaySnapshot {
        final String weekdayLabel;
        final String dayNumber;
        final List<ScheduleItemSnapshot> items;

        DaySnapshot(String weekdayLabel, String dayNumber, List<ScheduleItemSnapshot> items) {
            this.weekdayLabel = weekdayLabel;
            this.dayNumber = dayNumber;
            this.items = items;
        }
    }

    private static class ScheduleItemSnapshot {
        final String title;
        final int startMinute;
        final int endMinute;
        final String source;
        final boolean isRecurring;

        ScheduleItemSnapshot(String title, int startMinute, int endMinute, String source, boolean isRecurring) {
            this.title = title;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.source = source;
            this.isRecurring = isRecurring;
        }

        static ScheduleItemSnapshot from(JSONObject json) {
            if (json == null) return null;

            int startMinute = json.optInt("startMinute", -1);
            int endMinute = json.optInt("endMinute", -1);
            if (startMinute < 0 || endMinute <= startMinute) return null;

            return new ScheduleItemSnapshot(
                json.optString("title", "Untitled"),
                startMinute,
                endMinute,
                json.optString("source", "manual"),
                json.optBoolean("isRecurring", false)
            );
        }
    }

}
