#!/usr/bin/env python3
# collect_android_test_failures.py
# Purpose: Print failing Android unit-test names, messages, and stack traces from Gradle XML reports.

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()

REPORT_DIRS = [
    ROOT / "app" / "build" / "test-results" / "testDebugUnitTest",
    ROOT / "app" / "build" / "test-results" / "testReleaseUnitTest",
]

SOURCE_HINTS = [
    ROOT / "app" / "src" / "test" / "java" / "com" / "lss" / "onmyplate" / "nativeplanner" / "widget" / "PlannerWidgetSyncTest.kt",
]

def print_source_hint(path: Path, center: int = 63, radius: int = 8) -> None:
    if not path.exists():
        return
    print(f"\n=== source hint: {path} lines {center-radius}-{center+radius} ===")
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    start = max(1, center - radius)
    end = min(len(lines), center + radius)
    for line_no in range(start, end + 1):
        marker = ">>" if line_no == center else "  "
        print(f"{marker} {line_no:4d}: {lines[line_no - 1]}")

def main() -> int:
    found = False
    for report_dir in REPORT_DIRS:
        if not report_dir.exists():
            continue
        print(f"\n=== {report_dir} ===")
        xml_files = sorted(report_dir.glob("TEST-*.xml"))
        if not xml_files:
            print("[WARN] no TEST-*.xml files")
            continue

        for xml_file in xml_files:
            try:
                root = ET.parse(xml_file).getroot()
            except ET.ParseError as error:
                print(f"[WARN] could not parse {xml_file}: {error}")
                continue

            for case in root.findall(".//testcase"):
                failures = list(case.findall("failure")) + list(case.findall("error"))
                if not failures:
                    continue
                found = True
                classname = case.attrib.get("classname", "")
                name = case.attrib.get("name", "")
                print(f"\n[FAIL] {classname}.{name}")
                for failure in failures:
                    ftype = failure.attrib.get("type", "")
                    msg = failure.attrib.get("message", "")
                    print(f"  type: {ftype}")
                    print(f"  message: {msg}")
                    text = (failure.text or "").strip()
                    if text:
                        print("  stack:")
                        print(text)

    for source in SOURCE_HINTS:
        print_source_hint(source)

    if not found:
        print("[OK] no failures found in known XML report dirs")
        return 0
    return 1

if __name__ == "__main__":
    raise SystemExit(main())
