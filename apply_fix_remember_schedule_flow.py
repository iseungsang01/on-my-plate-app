#!/usr/bin/env python3
from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path.cwd()
TARGET = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/ui/WeeklyScheduleScreen.kt"
BACKUP = TARGET.with_suffix(TARGET.suffix + ".bak")

OLD = """    val schedules by repository.observeExpandedSchedules(rangeStart, rangeEnd).collectAsState(initial = emptyList())
    val runtimeState by repository.runtimeState.collectAsState()
"""

NEW = """    val expandedSchedulesFlow = remember(repository, rangeStart, rangeEnd) {
        repository.observeExpandedSchedules(rangeStart, rangeEnd)
    }
    val schedules by expandedSchedulesFlow.collectAsState(initial = emptyList())
    val runtimeState by repository.runtimeState.collectAsState()
"""

MARKER = "val expandedSchedulesFlow = remember(repository, rangeStart, rangeEnd)"

def main() -> int:
    if not TARGET.exists():
        print(f"[FAIL] target not found: {TARGET}")
        return 1

    text = TARGET.read_text(encoding="utf-8")

    if MARKER in text:
        print("[OK] already patched: expanded schedule Flow is remembered")
        return 0

    if OLD not in text:
        print("[FAIL] expected snippet not found. File may have changed; paste this log and current diff.")
        return 1

    if not BACKUP.exists():
        shutil.copy2(TARGET, BACKUP)
        print(f"[OK] backup created: {BACKUP}")
    else:
        print(f"[OK] backup already exists: {BACKUP}")

    TARGET.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    print("[OK] patched WeeklyScheduleScreen to remember observeExpandedSchedules Flow")
    print("[NEXT] run: .\\gradlew.bat test")
    print("[NEXT] run: .\\gradlew.bat assembleDebug")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
