# Widget bundle

This folder contains the planner widget code extracted for reuse in other apps.

## Contents

- `src/plannerWidgetBridge.js` — portable JS bridge that builds the summary snapshot and talks to the native plugin.
- `android/app/src/main/java/...` — Capacitor plugin and widget provider classes.
- `android/app/src/main/res/...` — widget layout, drawables, strings, and appwidget metadata.

## Notes

- The Android code keeps the current package name used by this repo.
- If you copy this folder into another app, rename the package and wire the native sources into that app's Android module.
- The JS bridge is self-contained and only depends on `@capacitor/core`.
