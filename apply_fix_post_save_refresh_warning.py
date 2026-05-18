#!/usr/bin/env python3
from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path.cwd()
TARGET = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt"
BACKUP = TARGET.with_suffix(TARGET.suffix + ".bak")

OLD = """        rememberCandidate(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        refreshPendingCandidates()
        refreshSchedules()
        refreshCurrentWeekWidgetSnapshotFromCache()
        return@withLock if (scheduleStatus == ScheduleStatus.Uncertain) SaveResult.SavedAsUncertain(savedRecord.schedule) else SaveResult.Saved(savedRecord.schedule)
"""

NEW = """        rememberCandidate(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        try {
            refreshPendingCandidates()
            refreshSchedules()
            refreshCurrentWeekWidgetSnapshotFromCache()
        } catch (error: Throwable) {
            Log.w(TAG, "saveFromCandidate succeeded but post-save refresh failed. candidateId=$candidateId", error)
            clearRuntimeError()
        }
        return@withLock if (scheduleStatus == ScheduleStatus.Uncertain) SaveResult.SavedAsUncertain(savedRecord.schedule) else SaveResult.Saved(savedRecord.schedule)
"""

MARKER = "saveFromCandidate succeeded but post-save refresh failed"

def main() -> int:
    if not TARGET.exists():
        print(f"[FAIL] target not found: {TARGET}")
        return 1

    text = TARGET.read_text(encoding="utf-8")

    if MARKER in text:
        print("[OK] already patched: post-save refresh failure is downgraded to warning")
        return 0

    if OLD not in text:
        print("[FAIL] expected snippet not found. File may have changed; paste the log and current diff.")
        return 1

    if not BACKUP.exists():
        shutil.copy2(TARGET, BACKUP)
        print(f"[OK] backup created: {BACKUP}")
    else:
        print(f"[OK] backup already exists: {BACKUP}")

    TARGET.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    print("[OK] patched PlannerRepository.saveFromCandidate post-save refresh handling")
    print("[NEXT] run: .\\gradlew.bat test")
    print("[NEXT] run: .\\gradlew.bat assembleDebug")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
