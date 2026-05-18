#!/usr/bin/env python3
# scripts/p1_6_smoke_planner_api.py
#
# Smoke-test deployed Supabase Edge Function planner-api.
#
# Required env:
#   PLANNER_API_BASE_URL=https://<project-ref>.supabase.co/functions/v1/planner-api
#   OMP_SMOKE_USER=<test user id>
#   OMP_SMOKE_PASSWORD=<test password, min 6 chars>
#
# Optional env:
#   OMP_SMOKE_PARTNER=<partner public id for share group smoke, optional>

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any


@dataclass
class ApiResponse:
    status: int
    body: dict[str, Any]
    text: str


def env(name: str, *, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None or not value.strip():
        raise SystemExit(f"[FAIL] missing env var: {name}")
    return value.strip()


BASE_URL = env("PLANNER_API_BASE_URL").rstrip("/")
USER_ID = env("OMP_SMOKE_USER")
PASSWORD = env("OMP_SMOKE_PASSWORD")

if len(PASSWORD) < 6:
    raise SystemExit("[FAIL] OMP_SMOKE_PASSWORD must be at least 6 characters.")


def request_json(
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    expected: set[int] | None = None,
) -> ApiResponse:
    url = f"{BASE_URL}{path}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as error:
        status = error.code
        text = error.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as error:
        raise SystemExit(f"[FAIL] network error calling {method} {path}: {error}") from error

    try:
        parsed = json.loads(text) if text.strip() else {}
    except json.JSONDecodeError:
        parsed = {"_raw": text}

    if expected is not None and status not in expected:
        pretty = json.dumps(parsed, ensure_ascii=False, indent=2)
        raise SystemExit(f"[FAIL] {method} {path} expected {sorted(expected)} got {status}\n{pretty}")

    return ApiResponse(status=status, body=parsed, text=text)


def ok(label: str, response: ApiResponse | None = None) -> None:
    if response is None:
        print(f"[OK] {label}")
    else:
        print(f"[OK] {label}: HTTP {response.status}")


def require_field(body: dict[str, Any], key: str) -> Any:
    if key not in body or body[key] in (None, ""):
        raise SystemExit(f"[FAIL] response missing field: {key}; body={json.dumps(body, ensure_ascii=False)}")
    return body[key]


def signup_or_login() -> tuple[str, str]:
    auth_body = {"id": USER_ID, "password": PASSWORD}
    signup = request_json("POST", "/api/auth/signup", auth_body, expected={200, 409})
    if signup.status == 200:
        ok("signup", signup)
        return str(require_field(signup.body, "sessionToken")), str(require_field(signup.body, "userId"))

    ok("signup already exists; falling back to login", signup)
    login = request_json("POST", "/api/auth/login", auth_body, expected={200})
    ok("login", login)
    return str(require_field(login.body, "sessionToken")), str(require_field(login.body, "userId"))


def main() -> int:
    print(f"[INFO] planner-api smoke base: {BASE_URL}")

    invalid = request_json(
        "GET",
        "/api/planner/candidates?status=pending",
        token="omp_session_v1_invalid",
        expected={401},
    )
    ok("invalid token rejected", invalid)

    token, user_id = signup_or_login()
    if user_id != USER_ID:
        raise SystemExit(f"[FAIL] auth returned unexpected userId: {user_id}")
    ok("session token received")

    pending = request_json(
        "GET",
        "/api/planner/candidates?status=pending",
        token=token,
        expected={200},
    )
    ok("list pending candidates", pending)
    if "candidates" not in pending.body or not isinstance(pending.body["candidates"], list):
        raise SystemExit(f"[FAIL] candidates response shape invalid: {pending.body}")

    unique = int(time.time())
    start = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=1)
    end = start + timedelta(hours=1)
    raw_text = f"smoke test appointment {unique} tomorrow 10am"

    candidate_body = {
        "localCandidateId": f"smoke-candidate-{unique}",
        "rawText": raw_text,
        "sourceApp": "p1_6_smoke",
        "extractedTitle": f"Smoke Candidate {unique}",
        "extractedStartAt": start.isoformat().replace("+00:00", "Z"),
        "extractedEndAt": end.isoformat().replace("+00:00", "Z"),
        "extractedLocation": "Smoke Test Location",
        "confidence": 0.99,
        "timeConfidence": "high",
        "parseSource": "local_only",
        "status": "pending",
        "createdAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    candidate = request_json("POST", "/api/planner/candidates", candidate_body, token=token, expected={200})
    ok("create candidate", candidate)
    candidate_obj = candidate.body.get("candidate")
    if not isinstance(candidate_obj, dict):
        raise SystemExit(f"[FAIL] candidate response shape invalid: {candidate.body}")
    candidate_id = str(require_field(candidate_obj, "id"))

    fetched_candidate = request_json("GET", f"/api/planner/candidates/{candidate_id}", token=token, expected={200})
    ok("get candidate", fetched_candidate)

    patched_candidate = request_json(
        "PATCH",
        f"/api/planner/candidates/{candidate_id}",
        {"status": "discarded"},
        token=token,
        expected={200},
    )
    ok("patch candidate discarded", patched_candidate)

    schedule_body = {
        "localScheduleId": f"smoke-schedule-{unique}",
        "title": f"Smoke Schedule {unique}",
        "startAt": start.isoformat().replace("+00:00", "Z"),
        "endAt": end.isoformat().replace("+00:00", "Z"),
        "location": "Smoke Test Location",
        "memo": "Created by P1-6 smoke test.",
        "status": "confirmed",
        "sourceText": raw_text,
        "sourceApp": "p1_6_smoke",
        "recurrence": None,
        "recurrenceExceptions": [],
    }
    schedule = request_json("POST", "/api/planner/schedules", schedule_body, token=token, expected={200})
    ok("create personal schedule", schedule)
    schedule_obj = schedule.body.get("schedule")
    if not isinstance(schedule_obj, dict):
        raise SystemExit(f"[FAIL] schedule response shape invalid: {schedule.body}")
    schedule_id = str(require_field(schedule_obj, "id"))

    listed = request_json("GET", "/api/planner/schedules", token=token, expected={200})
    ok("list personal schedules", listed)
    if "schedules" not in listed.body or not isinstance(listed.body["schedules"], list):
        raise SystemExit(f"[FAIL] schedules response shape invalid: {listed.body}")

    feedback = request_json(
        "POST",
        "/api/planner/feedback",
        {
            "message": f"P1-6 smoke feedback {unique}",
            "sourceScreen": "smoke",
            "appVersionName": "smoke",
            "appVersionCode": 1,
        },
        token=token,
        expected={200},
    )
    ok("submit feedback", feedback)

    deleted = request_json("DELETE", f"/api/planner/schedules/{schedule_id}", token=token, expected={200})
    ok("delete smoke schedule", deleted)

    print("[OK] P1-6 planner-api smoke test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
