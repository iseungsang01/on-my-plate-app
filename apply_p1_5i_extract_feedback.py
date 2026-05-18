#!/usr/bin/env python3
# apply_p1_5i_extract_feedback.py
# Purpose: Extract feedback endpoint logic from planner-api/index.ts into feedback.ts.
# Safe/idempotent: creates .bak, skips already-applied changes, and fails on unexpected route shapes.

from __future__ import annotations

import re
import sys
from pathlib import Path

FEEDBACK_TS = '/// <reference lib="deno.ns" />\n\nimport { db } from "./db.ts";\nimport { optionalUserId } from "./auth.ts";\nimport { apiError, jsonResponse, readJson } from "./http.ts";\n\ntype FeedbackPayload = {\n  message: string;\n  sourceScreen: string;\n  appVersionName: string;\n  appVersionCode: number;\n};\n\nexport async function submitFeedback(request: Request): Promise<Response> {\n  const payload = readFeedbackPayload(await readJson(request));\n  const { error } = await db.from("planner_feedback").insert({\n    user_id: await optionalUserId(request),\n    message: payload.message,\n    source_screen: payload.sourceScreen,\n    app_version_name: payload.appVersionName,\n    app_version_code: payload.appVersionCode,\n  });\n  if (error) throw apiError(500, error.message);\n  return jsonResponse({ ok: true });\n}\n\nfunction readFeedbackPayload(body: Record<string, unknown>): FeedbackPayload {\n  const message = requiredString(body.message, "피드백 내용을 입력하세요.").trim();\n  if (message.length > 2000) throw apiError(400, "피드백은 2000자 이내로 입력하세요.");\n  const sourceScreen = optionalString(body.sourceScreen) ?? "settings";\n  const appVersionName = requiredString(body.appVersionName, "앱 버전 정보가 없습니다.");\n  const appVersionCode = requiredPositiveInteger(body.appVersionCode, "앱 버전 코드가 없습니다.");\n  return {\n    message,\n    sourceScreen,\n    appVersionName,\n    appVersionCode,\n  };\n}\n\nfunction requiredString(value: unknown, message: string): string {\n  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);\n  return value;\n}\n\nfunction optionalString(value: unknown): string | null {\n  if (typeof value !== "string") return null;\n  const trimmed = value.trim();\n  return trimmed.length === 0 || trimmed === "null" ? null : value;\n}\n\nfunction requiredPositiveInteger(value: unknown, message: string): number {\n  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {\n    throw apiError(400, message);\n  }\n  return value;\n}\n'

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
API_DIR = ROOT / "supabase" / "functions" / "planner-api"
INDEX = API_DIR / "index.ts"
FEEDBACK = API_DIR / "feedback.ts"

IMPORT_LINE = 'import { submitFeedback } from "./feedback.ts";\n'
FUNCTION_NAMES = ["submitFeedback", "readFeedbackPayload"]

TYPE_BLOCK = """type FeedbackPayload = {
  message: string;
  sourceScreen: string;
  appVersionName: string;
  appVersionCode: number;
};
"""

def fail(message: str) -> None:
    print(f"[FAIL] {message}")
    sys.exit(1)

def backup(path: Path) -> None:
    bak = path.with_suffix(path.suffix + ".bak")
    if not bak.exists():
        bak.write_text(path.read_text(encoding="utf-8-sig"), encoding="utf-8")
        print(f"[OK] backup created: {bak}")
    else:
        print(f"[OK] backup exists: {bak}")

def write_if_needed(path: Path, content: str) -> None:
    if path.exists() and path.read_text(encoding="utf-8-sig") == content:
        print(f"[OK] already up to date: {path}")
        return
    if path.exists():
        backup(path)
    path.write_text(content, encoding="utf-8")
    print(f"[OK] wrote: {path}")

def find_matching_brace(text: str, open_index: int) -> int:
    depth = 0
    in_single = in_double = in_template = False
    escape = line_comment = block_comment = False
    for i in range(open_index, len(text)):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if ch == "\n":
                line_comment = False
            continue
        if block_comment:
            if ch == "*" and nxt == "/":
                block_comment = False
            continue
        if escape:
            escape = False
            continue
        if ch == "\\" and (in_single or in_double or in_template):
            escape = True
            continue
        if not (in_single or in_double or in_template):
            if ch == "/" and nxt == "/":
                line_comment = True
                continue
            if ch == "/" and nxt == "*":
                block_comment = True
                continue
        if not (in_double or in_template) and ch == "'":
            in_single = not in_single
            continue
        if not (in_single or in_template) and ch == '"':
            in_double = not in_double
            continue
        if not (in_single or in_double) and ch == "`":
            in_template = not in_template
            continue
        if in_single or in_double or in_template:
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1

def remove_function(text: str, name: str) -> tuple[str, bool]:
    pattern = re.compile(rf"\n(?:export\s+)?(?:async\s+)?function\s+{re.escape(name)}\s*\(")
    match = pattern.search(text)
    if not match:
        return text, False
    brace = text.find("{", match.end())
    if brace < 0:
        fail(f"found function {name} but could not find opening brace")
    end = find_matching_brace(text, brace)
    if end < 0:
        fail(f"found function {name} but could not find matching closing brace")
    remove_end = end + 1
    while remove_end < len(text) and text[remove_end] in " \t\r\n":
        remove_end += 1
    return text[:match.start()] + "\n" + text[remove_end:], True

def remove_type_block(text: str) -> tuple[str, bool]:
    if TYPE_BLOCK in text:
        return text.replace(TYPE_BLOCK, "", 1), True
    if "type FeedbackPayload" not in text:
        return text, False
    pattern = re.compile(
        r"\ntype FeedbackPayload = \{\n"
        r"\s*message: string;\n"
        r"\s*sourceScreen: string;\n"
        r"\s*appVersionName: string;\n"
        r"\s*appVersionCode: number;\n"
        r"\};\n",
        re.DOTALL,
    )
    new_text, count = pattern.subn("\n", text, count=1)
    return new_text, count > 0

def ensure_import(text: str) -> str:
    if 'from "./feedback.ts"' in text:
        print("[OK] already imports ./feedback.ts")
        return text
    anchors = [
        'import { createGroup, listGroups, listSharedSchedules, profile, uploadSharedSchedule } from "./sharing.ts";\n',
        'import { createCandidate, discardCandidate, getCandidate, listCandidates, updateCandidate } from "./candidates.ts";\n',
        'import { addPersonalScheduleRecurrenceException, deletePersonalSchedule, getPersonalSchedule, listPersonalSchedules, updatePersonalSchedule, uploadPersonalSchedule } from "./personal_schedules.ts";\n',
        'import { changePassword, login, optionalUserId, requireUserId, signUp } from "./auth.ts";\n',
    ]
    for anchor in anchors:
        if anchor in text:
            return text.replace(anchor, anchor + IMPORT_LINE, 1)
    fail("could not find a stable import anchor for feedback.ts")

def main() -> None:
    if not INDEX.exists():
        fail(f"missing {INDEX}")
    for required in ["db.ts", "http.ts", "auth.ts"]:
        if not (API_DIR / required).exists():
            fail(f"missing {required}")

    write_if_needed(FEEDBACK, FEEDBACK_TS)

    text = INDEX.read_text(encoding="utf-8-sig")
    original = text
    backup(INDEX)

    text = ensure_import(text)

    text, did_type = remove_type_block(text)
    if did_type:
        print("[OK] removed FeedbackPayload type from index.ts")
    elif "FeedbackPayload" in text:
        fail("FeedbackPayload still appears in index.ts but could not be removed safely")
    else:
        print("[OK] FeedbackPayload already removed from index.ts")

    removed = []
    for name in FUNCTION_NAMES:
        text, did = remove_function(text, name)
        if did:
            removed.append(name)

    if removed:
        print("[OK] removed feedback functions from index.ts:")
        for name in removed:
            print(f"     - {name}")
    else:
        print("[OK] feedback functions already removed from index.ts")

    for name in FUNCTION_NAMES:
        if re.search(rf"\n(?:export\s+)?(?:async\s+)?function\s+{name}\s*\(", text):
            fail(f"duplicate local feedback function remains: {name}")

    route_call = "return await submitFeedback(request);"
    if route_call not in text:
        fail(f"expected feedback route call missing after extraction: {route_call}")

    if text == original and 'from "./feedback.ts"' in original:
        print("[OK] P1-5i already applied")
        return

    INDEX.write_text(text, encoding="utf-8")
    print("[OK] patched index.ts to use ./feedback.ts")
    print("[OK] P1-5i complete")

if __name__ == "__main__":
    main()
