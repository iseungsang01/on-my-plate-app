#!/usr/bin/env python3
"""End-to-end smoke checks for Availability Group Sharing.

Requires PLANNER_API_BASE_URL to point at a running planner-api Edge Function.
Creates disposable app-login users with unique ids and verifies the
create/proposal-before-join/join/respond/sort/finalize flow.
"""
from __future__ import annotations

import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta
from typing import Any
from urllib import error, parse, request


BASE_URL = os.environ.get("PLANNER_API_BASE_URL", "").rstrip("/")
PASSWORD = os.environ.get("OMP_SMOKE_PASSWORD", "SmokePass123!")


class ApiFailure(RuntimeError):
    def __init__(self, method: str, path: str, status: int, body: str) -> None:
        super().__init__(f"{method} {path} failed with {status}: {body}")
        self.status = status
        self.body = body


def api(method: str, path: str, token: str | None = None, body: dict[str, Any] | None = None) -> tuple[int, dict[str, Any]]:
    if not BASE_URL:
        raise RuntimeError("Set PLANNER_API_BASE_URL to run the availability group smoke test.")
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with request.urlopen(req, timeout=30) as response:
            text = response.read().decode("utf-8") or "{}"
            return response.status, json.loads(text)
    except error.HTTPError as exc:
        text = exc.read().decode("utf-8")
        raise ApiFailure(method, path, exc.code, text)


def signup(identifier: str) -> str:
    _, payload = api("POST", "/api/auth/signup", body={"id": identifier, "password": PASSWORD})
    token = payload.get("sessionToken") or payload.get("session_token") or payload.get("token")
    if not token:
        raise AssertionError(f"signup response missing session token: {payload}")
    return str(token)


def post_schedule(token: str, local_id: str, title: str, start: datetime, end: datetime) -> dict[str, Any]:
    _, payload = api(
        "POST",
        "/api/planner/schedules",
        token,
        {
            "localScheduleId": local_id,
            "title": title,
            "startAt": iso(start),
            "endAt": iso(end),
            "status": "planned",
            "sourceText": "availability smoke private source",
            "sourceApp": "smoke-test",
            "recurrence": None,
            "recurrenceExceptions": [],
        },
    )
    return payload["schedule"]


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def assert_no_private_fields(value: Any) -> None:
    text = json.dumps(value, ensure_ascii=False)
    forbidden = [
        "user_id",
        "created_by",
        "source_text",
        "sourceText",
        "source_app",
        "sourceApp",
        "availability smoke private source",
        "Private busy",
        "privateNote",
        "location",
        "memo",
        "reason",
    ]
    for needle in forbidden:
        if needle in text:
            raise AssertionError(f"privacy leak detected: {needle}")


def assert_chronological(slots: list[dict[str, Any]]) -> None:
    starts = [slot["startsAt"] for slot in slots]
    if starts != sorted(starts):
        raise AssertionError("slots are not chronological for sort=time")


def assert_ranked(slots: list[dict[str, Any]]) -> None:
    scores = [int(slot["rankScore"]) for slot in slots]
    if scores != sorted(scores, reverse=True):
        raise AssertionError("slots are not rank-sorted for sort=rank")


def main() -> int:
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    user_a = f"smoke_ag_a_{run_id}"
    user_b = f"smoke_ag_b_{run_id}"
    token_a = signup(user_a)
    token_b = signup(user_b)

    scope_start = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=3)
    scope_end = scope_start + timedelta(days=1)
    proposal_start = scope_start + timedelta(hours=6)
    proposal_end = proposal_start + timedelta(hours=1)

    _, create_payload = api(
        "POST",
        "/api/planner/availability-groups",
        token_a,
        {
            "title": f"Smoke availability {run_id}",
            "scopeStart": iso(scope_start),
            "scopeEnd": iso(scope_end),
            "slotMinutes": 60,
            "searchStartTime": "08:00",
            "searchEndTime": "20:00",
            "visibilityMode": "busy_only",
            "suggestionMode": "everyone",
        },
    )
    group = create_payload["group"]
    group_id = group["id"]
    share_code = group["shareCode"]

    _, list_payload = api("GET", "/api/planner/availability-groups", token_a)
    if not any(item.get("group", {}).get("id") == group_id for item in list_payload.get("groups", [])):
        raise AssertionError("created group is missing from owner group list")

    post_schedule(token_a, f"smoke-a-{run_id}", "Private busy A", proposal_start, proposal_end)

    _, proposal_payload = api(
        "POST",
        f"/api/planner/availability-groups/{parse.quote(group_id)}/proposals",
        token_a,
        {"title": f"Smoke proposal {run_id}", "startAt": iso(proposal_start), "endAt": iso(proposal_end)},
    )
    proposal = proposal_payload["proposal"]
    proposal_id = proposal["id"]
    if proposal["responseSummary"]["totalCount"] != 1:
        raise AssertionError("proposal before join should have one initial response")

    _, join_payload = api("POST", "/api/planner/availability-groups/join", token_b, {"shareCode": share_code.lower()})
    if join_payload["member"]["role"] != "member":
        raise AssertionError("join did not return member role")

    _, member_payload = api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/members", token_a)
    members = member_payload.get("members", [])
    if len(members) != 2 or any("user_id" in member or "userId" in member for member in members):
        raise AssertionError(f"unsafe or incomplete member payload: {members}")

    _, proposals_after_join = api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/proposals", token_b)
    matching = [item for item in proposals_after_join.get("proposals", []) if item["id"] == proposal_id]
    if len(matching) != 1:
        raise AssertionError("B cannot see the pre-join proposal")
    joined_proposal = matching[0]
    if joined_proposal["myResponse"]["response"] != "pending":
        raise AssertionError("late join did not create B's pending response")
    if joined_proposal["responseSummary"]["totalCount"] != 2:
        raise AssertionError("late join did not produce exactly two proposal responses")
    assert_no_private_fields(joined_proposal)

    api(
        "POST",
        f"/api/planner/availability-groups/{parse.quote(group_id)}/dummy-schedules",
        token_b,
        {
            "startAt": iso(proposal_start + timedelta(hours=2)),
            "endAt": iso(proposal_start + timedelta(hours=3)),
            "privateNote": "privateNote should not leak through availability",
        },
    )

    _, default_availability = api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/availability", token_a)
    _, time_availability = api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/availability?sort=time", token_a)
    _, rank_availability = api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/availability?sort=rank", token_a)
    assert_chronological(default_availability["slots"])
    assert_chronological(time_availability["slots"])
    assert_ranked(rank_availability["slots"])
    assert_no_private_fields(default_availability)
    if not any(slot["unavailableCount"] > 0 for slot in default_availability["slots"]):
        raise AssertionError("busy and dummy schedules did not affect availability")
    try:
        api("GET", f"/api/planner/availability-groups/{parse.quote(group_id)}/availability?sort=bad", token_a)
        raise AssertionError("invalid availability sort did not fail")
    except ApiFailure as exc:
        if exc.status != 400:
            raise

    api(
        "POST",
        f"/api/planner/availability-groups/{parse.quote(group_id)}/proposals/{parse.quote(proposal_id)}/response",
        token_b,
        {"response": "accepted"},
    )
    _, finalize_payload = api(
        "POST",
        f"/api/planner/availability-groups/{parse.quote(group_id)}/proposals/{parse.quote(proposal_id)}/finalize",
        token_a,
    )
    if int(finalize_payload["createdScheduleCount"]) != 1:
        raise AssertionError(f"expected one finalized schedule for accepted user B: {finalize_payload}")

    _, schedules_a = api("GET", "/api/planner/schedules", token_a)
    _, schedules_b = api("GET", "/api/planner/schedules", token_b)
    title = f"Smoke proposal {run_id}"
    if any(schedule.get("title") == title for schedule in schedules_a.get("schedules", [])):
        raise AssertionError("owner A should not receive a schedule without accepting")
    if not any(schedule.get("title") == title for schedule in schedules_b.get("schedules", [])):
        raise AssertionError("accepted user B did not receive finalized schedule")

    print("PASS: availability group API smoke")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise
