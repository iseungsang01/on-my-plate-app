#!/usr/bin/env python3
"""Static P0/P1/P2 contract checks for availability group sharing.

These checks intentionally inspect the committed API/migration surface without a
local Supabase database. They guard privacy and phase boundaries:
- P0 availability remains busy-only and does not serialize schedule details.
- P1 adds dummy schedules, proposals, responses, and owner finalize.
- P2 adds leader assignment, server-side suggestion gating, visibility settings
  skeleton, proposal comments, and keeps busy_only privacy as the default.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = ROOT / "supabase/functions/planner-api/availability_groups.ts"
INDEX = ROOT / "supabase/functions/planner-api/index.ts"
MIGRATION = ROOT / "supabase/migrations/20260518153220_availability_group_sharing.sql"


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    sys.exit(1)


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"missing {label}: {needle}")


def require_regex(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.S):
        fail(f"missing {label}: {pattern}")


def forbid_regex(text: str, pattern: str, label: str) -> None:
    if re.search(pattern, text, re.I | re.S):
        fail(f"forbidden {label}: {pattern}")


def main() -> int:
    api = API.read_text(encoding="utf-8")
    index = INDEX.read_text(encoding="utf-8")
    migration = MIGRATION.read_text(encoding="utf-8")

    # P0 privacy/static checks: availability response is aggregate-only.
    get_availability = re.search(r"export async function getAvailability\(.*?\n}\n", api, re.S)
    if not get_availability:
        fail("getAvailability function not found")
    availability_body = get_availability.group(0)
    response_match = re.search(r"return jsonResponse\(\{([\s\S]*?)\n  \}\);", availability_body)
    if not response_match:
        fail("getAvailability jsonResponse body not found")
    availability_response = response_match.group(1)
    for forbidden in ["title", "memo", "location", "source", "reason", "userId", "memberId", "created_by"]:
        if forbidden in availability_response:
            fail(f"availability response may expose forbidden field '{forbidden}'")
    for required in ["visibilityMode: \"busy_only\"", "totalMembers", "slots"]:
        require(availability_response, required, f"P0 aggregate availability field {required}")
    require_regex(api, r"createAvailabilityGroup[\s\S]*toGroupJson\(group as GroupRow, true\)", "creator-only share code response")
    require_regex(api, r"joinAvailabilityGroup[\s\S]*toGroupJson\(group as GroupRow, false\)", "join response excludes reusable share code")

    # P1 contract: dummy schedules exist, are group/user scoped, and private note is conditional on author.
    require(migration, "create table if not exists public.availability_group_dummy_schedules", "dummy schedule table")
    require(migration, "group_id uuid not null references public.availability_groups", "dummy group scope")
    require(migration, "created_by text not null references public.planner_users", "dummy user scope")
    require(migration, "private_note text", "dummy private note storage")
    require_regex(api, r"function toDummyScheduleJson[\s\S]*if \(schedule\.created_by === viewerUserId\) result\.privateNote", "private note author-only serializer")
    require_regex(api, r"dummyBusyBlocksForGroup[\s\S]*\.eq\(\"group_id\", groupId\)[\s\S]*\.in\(\"created_by\", memberIds\)", "dummy schedules included only in group/member availability math")

    # P1 contract: proposals snapshot busy-only counts and create pending member responses.
    require(migration, "create table if not exists public.availability_group_proposals", "proposal table")
    require(migration, "snapshot_available_count integer not null", "proposal available snapshot")
    require(migration, "snapshot_unavailable_count integer not null", "proposal unavailable snapshot")
    require(migration, "snapshot_total_count integer not null", "proposal total snapshot")
    require(migration, "create table if not exists public.availability_group_proposal_responses", "proposal responses table")
    require_regex(api, r"availabilitySnapshot:[\s\S]*availableCount[\s\S]*unavailableCount[\s\S]*totalCount", "proposal busy-only snapshot response")
    require_regex(api, r"members\.map\(\(member\) => \(\{ proposal_id: proposal\.id, member_id: member\.id, user_id: member\.user_id, response: \"pending\"", "pending responses for members")
    require_regex(api, r"\.eq\(\"proposal_id\", proposalId\)[\s\S]*\.eq\(\"user_id\", userId\)", "members update only own response")

    # P1 finalize contract: owner-only and accepted-only real personal schedule creation with exact proposal title.
    require_regex(api, r"if \(group\.owner_id !== userId\) throw apiError\(403, \"Only the group owner can finalize proposals\.\"\)", "owner-only finalize API gate")
    require(migration, "create or replace function public.finalize_availability_group_proposal", "finalize RPC")
    require_regex(migration, r"where response\.proposal_id = p_proposal_id\s+and response\.response = 'accepted'", "accepted-only finalize insert")
    require_regex(migration, r"select\s+response\.user_id,\s+target_proposal\.title,\s+target_proposal\.start_at", "finalize uses proposal title unchanged")

    # Router exposes P1 endpoints.
    for route in ["dummy-schedules", "proposals", "response", "finalize"]:
        require(index, route, f"P1 route {route}")

    # P2 contract: owner-only leader assignment, and owner rows cannot be changed through leader routes.
    require(migration, "role text not null default 'member' check (role in ('owner', 'leader', 'member'))", "leader role schema")
    require_regex(api, r"export async function assignAvailabilityGroupLeader[\s\S]*Only the group owner can assign leaders", "owner-only leader assign")
    require_regex(api, r"export async function unassignAvailabilityGroupLeader[\s\S]*Only the group owner can unassign leaders", "owner-only leader unassign")
    require_regex(api, r"assignAvailabilityGroupLeader[\s\S]*target\.role === \"owner\"[\s\S]*Owner role cannot be changed", "leader assign preserves owner role")
    require_regex(api, r"unassignAvailabilityGroupLeader[\s\S]*target\.role === \"owner\"[\s\S]*Owner role cannot be changed", "leader unassign preserves owner role")
    require_regex(index, r"availability-groups\\/\(\[\^/\]\+\)\\/members\\/\(\[\^/\]\+\)\\/leader", "leader member route")

    # P2 contract: suggestion mode is stored and proposal creation is server-gated.
    require(migration, "suggestion_mode text not null default 'everyone' check (suggestion_mode in ('everyone', 'owner_leader', 'owner_only'))", "suggestion mode schema")
    require_regex(api, r"function assertCanCreateProposal[\s\S]*owner_only[\s\S]*owner_leader[\s\S]*throw apiError\(403", "server-side suggestion mode gate")
    require_regex(api, r"createAvailabilityGroupProposal[\s\S]*assertCanCreateProposal\(group, membership\)", "proposal creation invokes suggestion gate")

    # P2 contract: visibility settings skeleton exists, defaults to busy_only, and expanded mode remains limited.
    require(migration, "visibility_mode text not null default 'busy_only' check (visibility_mode in ('busy_only', 'expanded_limited'))", "visibility mode schema")
    require(migration, "visibility_settings jsonb not null default '{}'::jsonb", "visibility settings schema")
    require_regex(api, r"function sanitizeVisibilitySettings[\s\S]*busy_only[\s\S]*return \{\}", "busy_only visibility settings stay empty")
    require_regex(api, r"expanded_limited does not expose personal schedule title, memo, location, source, reason, or raw member ids", "expanded visibility limited policy text")

    # P2 contract: proposal snapshots remain aggregate and record the visibility policy used.
    require(migration, "snapshot_visibility_mode text not null default 'busy_only'", "proposal snapshot visibility schema")
    require_regex(api, r"snapshot_visibility_mode: group\.visibility_mode", "proposal snapshots capture visibility mode")
    proposal_snapshot = re.search(r"availabilitySnapshot:\s*\{([\s\S]*?)\n    \},\n    responseSummary", api)
    if not proposal_snapshot:
        fail("proposal availabilitySnapshot serializer not found")
    for forbidden in ["title", "memo", "location", "source", "reason", "userId", "memberId", "created_by"]:
        if forbidden in proposal_snapshot.group(1):
            fail(f"proposal availabilitySnapshot may expose forbidden field '{forbidden}'")

    # P2 contract: proposal comments require group membership and proposal participation/visibility.
    require(migration, "create table if not exists public.availability_group_proposal_comments", "proposal comments table")
    require_regex(api, r"requireProposalParticipant[\s\S]*requireGroup\(userId, groupId\)[\s\S]*proposalResponseForUser", "comments validate group and proposal participation")
    require_regex(api, r"listAvailabilityGroupProposalComments[\s\S]*requireProposalParticipant", "comment list auth gate")
    require_regex(api, r"createAvailabilityGroupProposalComment[\s\S]*requireProposalParticipant", "comment create auth gate")
    comment_serializer = re.search(r"function toProposalCommentJson[\s\S]*?return \{([\s\S]*?)\n  \};\n}", api)
    if not comment_serializer:
        fail("comment serializer not found")
    for forbidden in ["userId:", "memberId:", "createdBy:", "memberId:"]:
        if forbidden in comment_serializer.group(1):
            fail(f"comment serializer may expose raw id field '{forbidden}'")

    print("PASS: availability group P0/P1/P2 static contract checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
