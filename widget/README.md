# Widget bundle

This folder contains the planner widget code extracted for reuse in other apps.

## Contents

- `src/plannerWidgetBridge.js` - portable JS bridge that builds the summary snapshot and talks to the native plugin.
- `android/app/src/main/java/...` - Capacitor plugin and widget provider classes.
- `android/app/src/main/res/...` - widget layout, drawables, strings, and appwidget metadata.

## Snapshot contract

This reusable bundle expects a host-app planner database shaped around both manual schedules and category/auto plans:

- `dailySchedules` is converted to `manualEventsByDate`.
- Category columns (`study`, `club`, `money`, `etc`) are scanned for `autoSched` tasks and converted to `autoPlans`.
- The bridge also materializes `days` for the current week as a convenience for hosts that want a ready-to-render weekly view.

The native Android MVP in the repository root uses a narrower model. Its `PlannerWidgetSync` exports only Room-backed schedules as `manualEventsByDate` with schema `native-room-schedules-v1`; it does not export `autoPlans`, category columns, or `days`. That difference is intentional until the native app owns an auto/category planner model.

## Notes

- The Android code keeps the current package name used by this repo.
- If you copy this folder into another app, rename the package and wire the native sources into that app's Android module.
- The JS bridge is self-contained and only depends on `@capacitor/core`.
