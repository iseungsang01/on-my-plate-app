/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError, jsonResponse, readJson } from "./http.ts";

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };

type GroupRow = {
  id: string;
  owner_id: string;
  title: string;
  share_code: string;
  scope_start: string;
  scope_end: string;
  slot_minutes: number;
  search_start_time: string;
  search_end_time: string;
  visibility_mode: VisibilityMode;
  visibility_settings: JsonObject | null;
  suggestion_mode: SuggestionMode;
  status: "active" | "archived";
  created_at?: string;
  updated_at?: string;
};

type MemberRow = {
  id: string;
  group_id: string;
  user_id: string;
  role: MemberRole;
  joined_at?: string;
};

type ScheduleRow = {
  id: string;
  created_by: string;
  start_at: string;
  end_at: string | null;
};

type DummyScheduleRow = {
  id: string;
  group_id: string;
  created_by: string;
  start_at: string;
  end_at: string;
  private_note: string | null;
  created_at?: string;
  updated_at?: string;
};

type ProposalRow = {
  id: string;
  group_id: string;
  created_by: string;
  title: string;
  start_at: string;
  end_at: string;
  snapshot_available_count: number;
  snapshot_unavailable_count: number;
  snapshot_total_count: number;
  snapshot_visibility_mode: VisibilityMode;
  status: "pending" | "finalized" | "cancelled";
  finalized_at: string | null;
  created_at?: string;
  updated_at?: string;
};

type ProposalCommentRow = {
  id: string;
  proposal_id: string;
  group_id: string;
  created_by: string;
  member_id: string;
  body: string;
  created_at?: string;
  updated_at?: string;
};

type ProposalResponseRow = {
  id: string;
  proposal_id: string;
  member_id: string;
  user_id: string;
  response: "pending" | "accepted" | "rejected";
  responded_at: string | null;
  created_at?: string;
  updated_at?: string;
};

type RecurrenceRuleRow = {
  schedule_id: string;
  frequency: "daily" | "weekly" | "monthly";
  interval: number | null;
  interval_weeks: number | null;
  day_of_week: number | null;
  day_of_month: number | null;
  until_at: string | null;
  count: number | null;
};

type RecurrenceExceptionRow = {
  schedule_id: string;
  occurrence_start_at: string;
  action: "skip";
};

type BusyBlock = { userId: string; startsAt: Date; endsAt: Date };

type MemberRole = "owner" | "leader" | "member";
type SuggestionMode = "everyone" | "owner_leader" | "owner_only";
type VisibilityMode = "busy_only" | "expanded_limited";
type AvailabilitySort = "time" | "rank";

type SlotCount = {
  startsAt: string;
  endsAt: string;
  availableCount: number;
  unavailableCount: number;
  totalCount: number;
  rankScore: number;
};

const SHARE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const MS_PER_MINUTE = 60_000;
const MS_PER_DAY = 86_400_000;
const SCHEDULE_TIME_ZONE_OFFSET_MS = 9 * 60 * MS_PER_MINUTE;

export async function createAvailabilityGroup(userId: string, request: Request): Promise<Response> {
  const body = await readJson(request);
  const title = requiredString(body.title, "Group title is required.");
  const scopeStart = parseIsoDate(requiredString(body.scopeStart ?? body.scope_start, "Group scope start is required."), "scopeStart");
  const scopeEnd = parseIsoDate(requiredString(body.scopeEnd ?? body.scope_end, "Group scope end is required."), "scopeEnd");
  if (scopeStart >= scopeEnd) throw apiError(400, "Group scope start must be before scope end.");

  const slotMinutes = optionalNumber(body.slotMinutes ?? body.slot_minutes) ?? 60;
  if (![30, 60, 120].includes(slotMinutes)) throw apiError(400, "Slot size must be 30, 60, or 120 minutes.");
  const searchStartTime = normalizeTime(optionalString(body.searchStartTime ?? body.search_start_time) ?? "08:00");
  const searchEndTime = normalizeTime(optionalString(body.searchEndTime ?? body.search_end_time) ?? "24:00");
  if (timeToMinutes(searchStartTime) >= timeToMinutes(searchEndTime)) {
    throw apiError(400, "Search start time must be before search end time.");
  }

  const visibilityMode = readVisibilityMode(body.visibilityMode ?? body.visibility_mode);
  const suggestionMode = readSuggestionMode(body.suggestionMode ?? body.suggestion_mode);
  const visibilitySettings = readVisibilitySettings(body.visibilitySettings ?? body.visibility_settings);

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const shareCode = generateShareCode();
    const { data: group, error } = await db.rpc("create_availability_group_with_owner", {
      p_owner_id: userId,
      p_title: title,
      p_share_code: shareCode,
      p_scope_start: scopeStart.toISOString(),
      p_scope_end: scopeEnd.toISOString(),
      p_slot_minutes: slotMinutes,
      p_search_start_time: searchStartTime,
      p_search_end_time: searchEndTime,
      p_visibility_mode: visibilityMode,
      p_visibility_settings: visibilitySettings,
      p_suggestion_mode: suggestionMode,
    });
    if (error?.code === "23505") continue;
    if (error) throw apiError(500, error.message);
    return jsonResponse({ group: toGroupJson(group as GroupRow, true) }, 201);
  }

  throw apiError(500, "Could not generate a unique share code.");
}

export async function listAvailabilityGroups(userId: string): Promise<Response> {
  const { data: memberships, error: membershipError } = await db
    .from("availability_group_members")
    .select("id,group_id,user_id,role,joined_at")
    .eq("user_id", userId)
    .order("joined_at", { ascending: false });
  if (membershipError) throw apiError(500, membershipError.message);

  const memberRows = (memberships ?? []) as MemberRow[];
  const groupIds = memberRows.map((member) => member.group_id);
  if (groupIds.length === 0) return jsonResponse({ groups: [] });

  const { data: groups, error: groupError } = await db
    .from("availability_groups")
    .select("*")
    .in("id", groupIds)
    .order("created_at", { ascending: false });
  if (groupError) throw apiError(500, groupError.message);

  const allMembersByGroup = await membersForGroups(groupIds);
  const membershipByGroup = new Map(memberRows.map((member) => [member.group_id, member]));
  return jsonResponse({
    groups: ((groups ?? []) as GroupRow[]).map((group) => {
      const members = allMembersByGroup.get(group.id) ?? [];
      const membership = membershipByGroup.get(group.id) ?? null;
      return {
        group: toGroupJson(group, group.owner_id === userId),
        membership: membership ? toMemberJson(membership, userId) : null,
        members: toMembersSummary(members, userId),
      };
    }),
  });
}

export async function joinAvailabilityGroup(userId: string, request: Request): Promise<Response> {
  const body = await readJson(request);
  const shareCode = requiredString(body.shareCode ?? body.share_code, "Share code is required.").trim().toUpperCase();
  const { data: group, error } = await db
    .from("availability_groups")
    .select("*")
    .eq("share_code", shareCode)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!group) throw apiError(404, "Availability group was not found.");
  if (String(group.status) !== "active") throw apiError(400, "Availability group is not active.");

  const existing = await membershipForUser(userId, String(group.id));
  if (existing) throw apiError(409, "You are already a member of this availability group.");

  const { data: member, error: memberError } = await db
    .from("availability_group_members")
    .insert({ group_id: group.id, user_id: userId, role: "member" })
    .select("id,group_id,user_id,role,joined_at")
    .single();
  if (memberError?.code === "23505") throw apiError(409, "You are already a member of this availability group.");
  if (memberError) throw apiError(500, memberError.message);

  return jsonResponse({ group: toGroupJson(group as GroupRow, false), member: toMemberJson(member as MemberRow, userId) }, 201);
}

export async function getAvailabilityGroup(userId: string, groupId: string): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  const members = await membersForGroup(groupId);
  const membership = members.find((member) => member.user_id === userId);
  return jsonResponse({
    group: toGroupJson(group, group.owner_id === userId),
    membership: membership ? toMemberJson(membership, userId) : null,
    members: toMembersSummary(members, userId),
  });
}

export async function listAvailabilityGroupMembers(userId: string, groupId: string): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  if (group.owner_id !== userId) throw apiError(403, "Only the group owner can list members.");
  const members = await membersForGroup(groupId);
  return jsonResponse({ members: members.map((member) => toMemberJson(member, userId)) });
}

export async function updateAvailabilityGroupSettings(userId: string, groupId: string, request: Request): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  if (group.owner_id !== userId) throw apiError(403, "Only the group owner can update availability group settings.");
  const body = await readJson(request);
  const update: Record<string, unknown> = { updated_at: new Date().toISOString() };
  if (Object.hasOwn(body, "suggestionMode") || Object.hasOwn(body, "suggestion_mode")) {
    update.suggestion_mode = readSuggestionMode(body.suggestionMode ?? body.suggestion_mode);
  }
  if (Object.hasOwn(body, "visibilityMode") || Object.hasOwn(body, "visibility_mode")) {
    update.visibility_mode = readVisibilityMode(body.visibilityMode ?? body.visibility_mode);
  }
  if (Object.hasOwn(body, "visibilitySettings") || Object.hasOwn(body, "visibility_settings")) {
    update.visibility_settings = readVisibilitySettings(body.visibilitySettings ?? body.visibility_settings);
  }

  const { data, error } = await db
    .from("availability_groups")
    .update(update)
    .eq("id", groupId)
    .select("*")
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ group: toGroupJson(data as GroupRow, group.owner_id === userId) });
}

export async function assignAvailabilityGroupLeader(userId: string, groupId: string, memberId: string): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  if (group.owner_id !== userId) throw apiError(403, "Only the group owner can assign leaders.");
  const target = await memberById(groupId, memberId);
  if (target.role === "owner") throw apiError(400, "Owner role cannot be changed by leader assignment.");
  const { data, error } = await db
    .from("availability_group_members")
    .update({ role: "leader" })
    .eq("id", memberId)
    .eq("group_id", groupId)
    .neq("role", "owner")
    .select("id,group_id,user_id,role,joined_at")
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ member: toMemberJson(data as MemberRow, userId) });
}

export async function unassignAvailabilityGroupLeader(userId: string, groupId: string, memberId: string): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  if (group.owner_id !== userId) throw apiError(403, "Only the group owner can unassign leaders.");
  const target = await memberById(groupId, memberId);
  if (target.role === "owner") throw apiError(400, "Owner role cannot be changed by leader assignment.");
  const { data, error } = await db
    .from("availability_group_members")
    .update({ role: "member" })
    .eq("id", memberId)
    .eq("group_id", groupId)
    .neq("role", "owner")
    .select("id,group_id,user_id,role,joined_at")
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ member: toMemberJson(data as MemberRow, userId) });
}

export async function getAvailability(userId: string, groupId: string, request: Request): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  const members = await membersForGroup(groupId);
  const sort = readAvailabilitySort(new URL(request.url).searchParams.get("sort"));
  const slots = await calculateAvailability(group, members, sort);
  return jsonResponse({
    groupId: group.id,
    slotMinutes: group.slot_minutes,
    visibilityMode: "busy_only",
    recurrence: { included: true, timeZone: "Asia/Seoul" },
    sort,
    totalMembers: members.length,
    slots,
  });
}

export async function listAvailabilityGroupDummySchedules(userId: string, groupId: string): Promise<Response> {
  await requireGroup(userId, groupId);
  const { data, error } = await db
    .from("availability_group_dummy_schedules")
    .select("id,group_id,created_by,start_at,end_at,private_note,created_at,updated_at")
    .eq("group_id", groupId)
    .eq("created_by", userId)
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  return jsonResponse({ dummySchedules: ((data ?? []) as DummyScheduleRow[]).map((row) => toDummyScheduleJson(row, userId)) });
}

export async function createAvailabilityGroupDummySchedule(userId: string, groupId: string, request: Request): Promise<Response> {
  assertGroupActive(await requireGroup(userId, groupId));
  const body = await readJson(request);
  const startAt = parseIsoDate(requiredString(body.startAt ?? body.start_at, "Dummy schedule start is required."), "startAt");
  const endAt = parseIsoDate(requiredString(body.endAt ?? body.end_at, "Dummy schedule end is required."), "endAt");
  if (startAt >= endAt) throw apiError(400, "Dummy schedule start must be before end.");
  const { data, error } = await db
    .from("availability_group_dummy_schedules")
    .insert({
      group_id: groupId,
      created_by: userId,
      start_at: startAt.toISOString(),
      end_at: endAt.toISOString(),
      private_note: optionalString(body.privateNote ?? body.private_note),
    })
    .select("id,group_id,created_by,start_at,end_at,private_note,created_at,updated_at")
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ dummySchedule: toDummyScheduleJson(data as DummyScheduleRow, userId) }, 201);
}

export async function deleteAvailabilityGroupDummySchedule(userId: string, groupId: string, dummyScheduleId: string): Promise<Response> {
  await requireGroup(userId, groupId);
  const { data, error } = await db
    .from("availability_group_dummy_schedules")
    .delete()
    .eq("id", dummyScheduleId)
    .eq("group_id", groupId)
    .eq("created_by", userId)
    .select("id")
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "Dummy schedule was not found.");
  return jsonResponse({ ok: true });
}

export async function listAvailabilityGroupProposals(userId: string, groupId: string): Promise<Response> {
  await requireGroup(userId, groupId);
  const [proposals, responses] = await Promise.all([
    proposalsForGroup(groupId),
    proposalResponsesForGroup(groupId),
  ]);
  return jsonResponse({ proposals: proposals.map((proposal) => toProposalJson(proposal, responses.get(proposal.id) ?? [], userId)) });
}

export async function createAvailabilityGroupProposal(userId: string, groupId: string, request: Request): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  const membership = await membershipForUser(userId, groupId);
  if (!membership) throw apiError(403, "Availability group membership is required.");
  assertCanCreateProposal(group, membership);
  const members = await membersForGroup(groupId);
  const body = await readJson(request);
  const title = requiredString(body.title, "Proposal title is required.");
  const startAt = parseIsoDate(requiredString(body.startAt ?? body.start_at, "Proposal start is required."), "startAt");
  const endAt = parseIsoDate(requiredString(body.endAt ?? body.end_at, "Proposal end is required."), "endAt");
  if (startAt >= endAt) throw apiError(400, "Proposal start must be before end.");
  if (startAt < new Date(group.scope_start) || endAt > new Date(group.scope_end)) {
    throw apiError(400, "Proposal must be inside the group scope.");
  }

  const snapshot = await availabilityCountForRange(group, members, startAt, endAt);
  const { data: proposal, error } = await db
    .from("availability_group_proposals")
    .insert({
      group_id: groupId,
      created_by: userId,
      title,
      start_at: startAt.toISOString(),
      end_at: endAt.toISOString(),
      snapshot_available_count: snapshot.availableCount,
      snapshot_unavailable_count: snapshot.unavailableCount,
      snapshot_total_count: snapshot.totalCount,
      snapshot_visibility_mode: group.visibility_mode,
      status: "pending",
    })
    .select("id,group_id,created_by,title,start_at,end_at,snapshot_available_count,snapshot_unavailable_count,snapshot_total_count,snapshot_visibility_mode,status,finalized_at,created_at,updated_at")
    .single();
  if (error) throw apiError(500, error.message);

  const responses = (await proposalResponsesForGroup(groupId)).get(String(proposal.id)) ?? [];

  return jsonResponse({ proposal: toProposalJson(proposal as ProposalRow, responses, userId) }, 201);
}

export async function respondToAvailabilityGroupProposal(userId: string, groupId: string, proposalId: string, request: Request): Promise<Response> {
  await requireGroup(userId, groupId);
  assertGroupActive(await groupById(groupId));
  const proposal = await proposalForGroup(groupId, proposalId);
  if (proposal.status !== "pending") throw apiError(409, "Proposal is not pending.");
  const body = await readJson(request);
  const response = requiredString(body.response, "Response is required.");
  if (response !== "accepted" && response !== "rejected") throw apiError(400, "Response must be accepted or rejected.");
  const { data, error } = await db
    .from("availability_group_proposal_responses")
    .update({ response, responded_at: new Date().toISOString(), updated_at: new Date().toISOString() })
    .eq("proposal_id", proposalId)
    .eq("user_id", userId)
    .select("id,proposal_id,member_id,user_id,response,responded_at,created_at,updated_at")
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "Proposal response was not found for this member.");
  return jsonResponse({ response: toProposalResponseJson(data as ProposalResponseRow, userId) });
}

export async function finalizeAvailabilityGroupProposal(userId: string, groupId: string, proposalId: string): Promise<Response> {
  const group = await requireGroup(userId, groupId);
  assertGroupActive(group);
  if (group.owner_id !== userId) throw apiError(403, "Only the group owner can finalize proposals.");
  const proposal = await proposalForGroup(groupId, proposalId);
  if (proposal.status !== "pending") throw apiError(409, "Proposal is not pending.");
  const { data, error } = await db.rpc("finalize_availability_group_proposal", {
    p_owner_id: userId,
    p_group_id: groupId,
    p_proposal_id: proposalId,
  });
  if (error) throw apiError(500, error.message);
  const finalized = await proposalForGroup(groupId, proposalId);
  const responses = (await proposalResponsesForGroup(groupId)).get(proposalId) ?? [];
  return jsonResponse({ proposal: toProposalJson(finalized, responses, userId), createdScheduleCount: Number(data ?? 0) });
}

export async function listAvailabilityGroupProposalComments(userId: string, groupId: string, proposalId: string): Promise<Response> {
  await requireProposalParticipant(userId, groupId, proposalId);
  const { data, error } = await db
    .from("availability_group_proposal_comments")
    .select("id,proposal_id,group_id,created_by,member_id,body,created_at,updated_at")
    .eq("group_id", groupId)
    .eq("proposal_id", proposalId)
    .order("created_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  return jsonResponse({ comments: ((data ?? []) as ProposalCommentRow[]).map((comment) => toProposalCommentJson(comment, userId)) });
}

export async function createAvailabilityGroupProposalComment(userId: string, groupId: string, proposalId: string, request: Request): Promise<Response> {
  const { membership } = await requireProposalParticipant(userId, groupId, proposalId);
  const body = await readJson(request);
  const commentBody = requiredString(body.body ?? body.comment, "Comment body is required.");
  if (commentBody.length > 1000) throw apiError(400, "Comment body must be 1000 characters or less.");
  const { data, error } = await db
    .from("availability_group_proposal_comments")
    .insert({
      proposal_id: proposalId,
      group_id: groupId,
      created_by: userId,
      member_id: membership.id,
      body: commentBody,
    })
    .select("id,proposal_id,group_id,created_by,member_id,body,created_at,updated_at")
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ comment: toProposalCommentJson(data as ProposalCommentRow, userId) }, 201);
}


async function calculateAvailability(group: GroupRow, members: MemberRow[], sort: AvailabilitySort): Promise<SlotCount[]> {
  const memberIds = members.map((member) => member.user_id);
  const busyBlocks = await busyBlocksForGroup(group, memberIds);
  const totalCount = members.length;
  const slots: SlotCount[] = [];
  for (const [slotStart, slotEnd] of slotRanges(group)) {
    const unavailableMembers = new Set(
      busyBlocks
        .filter((block) => rangesOverlap(block.startsAt, block.endsAt, slotStart, slotEnd))
        .map((block) => block.userId),
    );
    const unavailableCount = unavailableMembers.size;
    const availableCount = totalCount - unavailableCount;
    const ownerTieBreak = unavailableMembers.has(group.owner_id) ? 0 : 1;
    slots.push({
      startsAt: slotStart.toISOString(),
      endsAt: slotEnd.toISOString(),
      availableCount,
      unavailableCount,
      totalCount,
      rankScore: availableCount * 1000 + ownerTieBreak,
    });
  }
  return slots.sort((left, right) => sortAvailabilitySlots(left, right, sort));
}

function sortAvailabilitySlots(left: SlotCount, right: SlotCount, sort: AvailabilitySort): number {
  if (sort === "rank") return right.rankScore - left.rankScore || left.startsAt.localeCompare(right.startsAt);
  return left.startsAt.localeCompare(right.startsAt);
}

async function busyBlocksForGroup(group: GroupRow, memberIds: string[]): Promise<BusyBlock[]> {
  if (memberIds.length === 0) return [];
  const scopeStart = new Date(group.scope_start);
  const scopeEnd = new Date(group.scope_end);
  const { data: schedules, error } = await db
    .from("planner_personal_schedules")
    .select("id,created_by,start_at,end_at")
    .in("created_by", memberIds)
    .lt("start_at", scopeEnd.toISOString())
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);

  const scheduleRows = (schedules ?? []) as ScheduleRow[];
  const scheduleIds = scheduleRows.map((schedule) => schedule.id);
  const [rules, exceptions, dummyBlocks] = await Promise.all([
    recurrenceRulesForSchedules(scheduleIds),
    recurrenceExceptionsForSchedules(scheduleIds),
    dummyBusyBlocksForGroup(group.id, memberIds, scopeStart, scopeEnd),
  ]);

  const blocks: BusyBlock[] = [...dummyBlocks];
  for (const schedule of scheduleRows) {
    const baseStart = new Date(schedule.start_at);
    const baseEnd = schedule.end_at ? new Date(schedule.end_at) : new Date(baseStart.getTime() + group.slot_minutes * MS_PER_MINUTE);
    if (Number.isNaN(baseStart.getTime()) || Number.isNaN(baseEnd.getTime()) || baseStart >= baseEnd) continue;
    const durationMs = baseEnd.getTime() - baseStart.getTime();
    const rule = rules.get(schedule.id);
    const exceptionStarts = exceptions.get(schedule.id) ?? new Set<string>();

    if (!rule) {
      if (rangesOverlap(baseStart, baseEnd, scopeStart, scopeEnd)) blocks.push({ userId: schedule.created_by, startsAt: baseStart, endsAt: baseEnd });
      continue;
    }

    for (const occurrenceStart of occurrenceStarts(baseStart, rule, scopeStart, scopeEnd)) {
      if (exceptionStarts.has(occurrenceStart.toISOString())) continue;
      const occurrenceEnd = new Date(occurrenceStart.getTime() + durationMs);
      if (rangesOverlap(occurrenceStart, occurrenceEnd, scopeStart, scopeEnd)) {
        blocks.push({ userId: schedule.created_by, startsAt: occurrenceStart, endsAt: occurrenceEnd });
      }
    }
  }
  return blocks;
}

async function dummyBusyBlocksForGroup(groupId: string, memberIds: string[], scopeStart: Date, scopeEnd: Date): Promise<BusyBlock[]> {
  if (memberIds.length === 0) return [];
  const { data, error } = await db
    .from("availability_group_dummy_schedules")
    .select("created_by,start_at,end_at")
    .eq("group_id", groupId)
    .in("created_by", memberIds)
    .lt("start_at", scopeEnd.toISOString())
    .gt("end_at", scopeStart.toISOString());
  if (error) throw apiError(500, error.message);
  return ((data ?? []) as Array<{ created_by: string; start_at: string; end_at: string }>).flatMap((row) => {
    const start = new Date(row.start_at);
    const end = new Date(row.end_at);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || start >= end) return [];
    return [{ userId: row.created_by, startsAt: start, endsAt: end }];
  });
}

async function availabilityCountForRange(group: GroupRow, members: MemberRow[], startsAt: Date, endsAt: Date): Promise<Pick<SlotCount, "availableCount" | "unavailableCount" | "totalCount">> {
  const memberIds = members.map((member) => member.user_id);
  const busyBlocks = await busyBlocksForGroup(group, memberIds);
  const unavailableMembers = new Set(
    busyBlocks
      .filter((block) => rangesOverlap(block.startsAt, block.endsAt, startsAt, endsAt))
      .map((block) => block.userId),
  );
  const totalCount = members.length;
  const unavailableCount = unavailableMembers.size;
  return { availableCount: totalCount - unavailableCount, unavailableCount, totalCount };
}

async function recurrenceRulesForSchedules(scheduleIds: string[]): Promise<Map<string, RecurrenceRuleRow>> {
  if (scheduleIds.length === 0) return new Map();
  const { data, error } = await db
    .from("planner_personal_schedule_recurrence_rules")
    .select("schedule_id,frequency,interval,interval_weeks,day_of_week,day_of_month,until_at,count")
    .in("schedule_id", scheduleIds);
  if (error) throw apiError(500, error.message);
  return new Map(((data ?? []) as RecurrenceRuleRow[]).map((rule) => [rule.schedule_id, rule]));
}

async function recurrenceExceptionsForSchedules(scheduleIds: string[]): Promise<Map<string, Set<string>>> {
  if (scheduleIds.length === 0) return new Map();
  const { data, error } = await db
    .from("planner_personal_schedule_recurrence_exceptions")
    .select("schedule_id,occurrence_start_at,action")
    .in("schedule_id", scheduleIds)
    .eq("action", "skip");
  if (error) throw apiError(500, error.message);
  const bySchedule = new Map<string, Set<string>>();
  for (const exception of (data ?? []) as RecurrenceExceptionRow[]) {
    const existing = bySchedule.get(exception.schedule_id) ?? new Set<string>();
    existing.add(new Date(exception.occurrence_start_at).toISOString());
    bySchedule.set(exception.schedule_id, existing);
  }
  return bySchedule;
}

function* occurrenceStarts(baseStart: Date, rule: RecurrenceRuleRow, scopeStart: Date, scopeEnd: Date): Iterable<Date> {
  const interval = Math.max(1, Number(rule.interval ?? rule.interval_weeks ?? 1));
  const untilAt = rule.until_at ? new Date(rule.until_at) : null;
  const maxCount = rule.count == null ? Number.POSITIVE_INFINITY : Math.max(0, Number(rule.count));
  const firstAllowedStart = scopeStart > baseStart ? scopeStart : baseStart;
  let occurrenceIndex = estimateOccurrenceIndex(baseStart, firstAllowedStart, rule, interval);
  let current = occurrenceStartAt(baseStart, rule, interval, occurrenceIndex);
  while (current < firstAllowedStart) {
    occurrenceIndex += 1;
    current = occurrenceStartAt(baseStart, rule, interval, occurrenceIndex);
  }

  while (current < scopeEnd && occurrenceIndex < maxCount) {
    if (!untilAt || current <= untilAt) yield new Date(current);
    occurrenceIndex += 1;
    current = occurrenceStartAt(baseStart, rule, interval, occurrenceIndex);
    if (untilAt && current > untilAt) break;
  }
}

function estimateOccurrenceIndex(baseStart: Date, rangeStart: Date, rule: RecurrenceRuleRow, interval: number): number {
  if (rangeStart <= baseStart) return 0;
  if (rule.frequency === "daily") {
    return Math.floor((scheduleLocalDayNumber(rangeStart) - scheduleLocalDayNumber(baseStart)) / interval);
  }
  if (rule.frequency === "weekly") {
    return Math.floor((scheduleLocalDayNumber(rangeStart) - scheduleLocalDayNumber(baseStart)) / (interval * 7));
  }
  const rangeLocal = toScheduleLocalDate(rangeStart);
  const baseLocal = toScheduleLocalDate(baseStart);
  const rawMonths = (rangeLocal.getUTCFullYear() - baseLocal.getUTCFullYear()) * 12 + rangeLocal.getUTCMonth() - baseLocal.getUTCMonth();
  return Math.floor(Math.max(0, rawMonths) / interval);
}

function occurrenceStartAt(baseStart: Date, rule: RecurrenceRuleRow, interval: number, occurrenceIndex: number): Date {
  const baseLocal = toScheduleLocalDate(baseStart);
  if (rule.frequency === "daily") return fromScheduleLocalDate(addScheduleLocalDays(baseLocal, occurrenceIndex * interval));
  if (rule.frequency === "weekly") {
    const targetDay = rule.day_of_week ?? scheduleLocalDayOfWeekMonday1(baseStart);
    const firstAnchorOffset = (targetDay - scheduleLocalDayOfWeekMonday1(baseStart) + 7) % 7;
    return fromScheduleLocalDate(addScheduleLocalDays(baseLocal, firstAnchorOffset + occurrenceIndex * interval * 7));
  }

  const targetDay = rule.day_of_month ?? baseLocal.getUTCDate();
  const monthOffset = occurrenceIndex * interval;
  const firstOfMonth = new Date(Date.UTC(
    baseLocal.getUTCFullYear(),
    baseLocal.getUTCMonth() + monthOffset,
    1,
    baseLocal.getUTCHours(),
    baseLocal.getUTCMinutes(),
    baseLocal.getUTCSeconds(),
    baseLocal.getUTCMilliseconds(),
  ));
  const clampedDay = Math.min(targetDay, daysInScheduleLocalMonth(firstOfMonth.getUTCFullYear(), firstOfMonth.getUTCMonth()));
  firstOfMonth.setUTCDate(clampedDay);
  return fromScheduleLocalDate(firstOfMonth);
}

function addScheduleLocalDays(value: Date, days: number): Date {
  const result = new Date(value);
  result.setUTCDate(result.getUTCDate() + days);
  return result;
}

function toScheduleLocalDate(value: Date): Date {
  return new Date(value.getTime() + SCHEDULE_TIME_ZONE_OFFSET_MS);
}

function fromScheduleLocalDate(value: Date): Date {
  return new Date(value.getTime() - SCHEDULE_TIME_ZONE_OFFSET_MS);
}

function scheduleLocalDayNumber(value: Date): number {
  const local = toScheduleLocalDate(value);
  return Math.floor(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()) / MS_PER_DAY);
}

function scheduleLocalDayOfWeekMonday1(value: Date): number {
  const day = toScheduleLocalDate(value).getUTCDay();
  return day === 0 ? 7 : day;
}

function daysInScheduleLocalMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
}

function* slotRanges(group: GroupRow): Iterable<[Date, Date]> {
  const scopeStart = new Date(group.scope_start);
  const scopeEnd = new Date(group.scope_end);
  const startMinutes = timeToMinutes(group.search_start_time);
  const endMinutes = timeToMinutes(group.search_end_time);
  const slotMillis = group.slot_minutes * MS_PER_MINUTE;
  const firstDay = scheduleLocalDayNumber(scopeStart);
  const lastDay = scheduleLocalDayNumber(scopeEnd);
  for (let day = firstDay; day <= lastDay; day += 1) {
    for (let minute = startMinutes; minute + group.slot_minutes <= endMinutes; minute += group.slot_minutes) {
      const slotStart = fromScheduleLocalDate(new Date(day * MS_PER_DAY + minute * MS_PER_MINUTE));
      const slotEnd = new Date(slotStart.getTime() + slotMillis);
      if (slotStart >= scopeStart && slotEnd <= scopeEnd) yield [slotStart, slotEnd];
    }
  }
}

async function requireGroup(userId: string, groupId: string): Promise<GroupRow> {
  const membership = await membershipForUser(userId, groupId);
  if (!membership) throw apiError(403, "Availability group membership is required.");
  return await groupById(groupId);
}

async function groupById(groupId: string): Promise<GroupRow> {
  const { data, error } = await db.from("availability_groups").select("*").eq("id", groupId).maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "Availability group was not found.");
  return data as GroupRow;
}

async function membershipForUser(userId: string, groupId: string): Promise<MemberRow | null> {
  const { data, error } = await db
    .from("availability_group_members")
    .select("id,group_id,user_id,role,joined_at")
    .eq("group_id", groupId)
    .eq("user_id", userId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  return (data as MemberRow | null) ?? null;
}

async function membersForGroup(groupId: string): Promise<MemberRow[]> {
  const { data, error } = await db
    .from("availability_group_members")
    .select("id,group_id,user_id,role,joined_at")
    .eq("group_id", groupId)
    .order("joined_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  return (data ?? []) as MemberRow[];
}

async function membersForGroups(groupIds: string[]): Promise<Map<string, MemberRow[]>> {
  if (groupIds.length === 0) return new Map();
  const { data, error } = await db
    .from("availability_group_members")
    .select("id,group_id,user_id,role,joined_at")
    .in("group_id", groupIds)
    .order("joined_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  const grouped = new Map<string, MemberRow[]>();
  for (const member of (data ?? []) as MemberRow[]) {
    const existing = grouped.get(member.group_id) ?? [];
    existing.push(member);
    grouped.set(member.group_id, existing);
  }
  return grouped;
}

async function memberById(groupId: string, memberId: string): Promise<MemberRow> {
  const { data, error } = await db
    .from("availability_group_members")
    .select("id,group_id,user_id,role,joined_at")
    .eq("id", memberId)
    .eq("group_id", groupId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "Availability group member was not found.");
  return data as MemberRow;
}

async function proposalsForGroup(groupId: string): Promise<ProposalRow[]> {
  const { data, error } = await db
    .from("availability_group_proposals")
    .select("id,group_id,created_by,title,start_at,end_at,snapshot_available_count,snapshot_unavailable_count,snapshot_total_count,snapshot_visibility_mode,status,finalized_at,created_at,updated_at")
    .eq("group_id", groupId)
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  return (data ?? []) as ProposalRow[];
}

async function proposalForGroup(groupId: string, proposalId: string): Promise<ProposalRow> {
  const { data, error } = await db
    .from("availability_group_proposals")
    .select("id,group_id,created_by,title,start_at,end_at,snapshot_available_count,snapshot_unavailable_count,snapshot_total_count,snapshot_visibility_mode,status,finalized_at,created_at,updated_at")
    .eq("id", proposalId)
    .eq("group_id", groupId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "Proposal was not found.");
  return data as ProposalRow;
}

async function proposalResponsesForGroup(groupId: string): Promise<Map<string, ProposalResponseRow[]>> {
  const proposals = await proposalsForGroup(groupId);
  const proposalIds = proposals.map((proposal) => proposal.id);
  if (proposalIds.length === 0) return new Map();
  const { data, error } = await db
    .from("availability_group_proposal_responses")
    .select("id,proposal_id,member_id,user_id,response,responded_at,created_at,updated_at")
    .in("proposal_id", proposalIds);
  if (error) throw apiError(500, error.message);
  const grouped = new Map<string, ProposalResponseRow[]>();
  for (const response of (data ?? []) as ProposalResponseRow[]) {
    const existing = grouped.get(response.proposal_id) ?? [];
    existing.push(response);
    grouped.set(response.proposal_id, existing);
  }
  return grouped;
}

async function proposalResponseForUser(proposalId: string, userId: string): Promise<ProposalResponseRow | null> {
  const { data, error } = await db
    .from("availability_group_proposal_responses")
    .select("id,proposal_id,member_id,user_id,response,responded_at,created_at,updated_at")
    .eq("proposal_id", proposalId)
    .eq("user_id", userId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  return (data as ProposalResponseRow | null) ?? null;
}

async function requireProposalParticipant(
  userId: string,
  groupId: string,
  proposalId: string,
): Promise<{ group: GroupRow; membership: MemberRow; proposal: ProposalRow }> {
  const group = await requireGroup(userId, groupId);
  const membership = await membershipForUser(userId, groupId);
  if (!membership) throw apiError(403, "Availability group membership is required.");
  const proposal = await proposalForGroup(groupId, proposalId);
  const response = await proposalResponseForUser(proposalId, userId);
  if (!response && proposal.created_by !== userId) {
    throw apiError(403, "Proposal membership is required.");
  }
  return { group, membership, proposal };
}

function toGroupJson(group: GroupRow, includeShareCode: boolean): JsonObject {
  const result: JsonObject = {
    id: group.id,
    groupId: group.id,
    title: group.title,
    scopeStart: group.scope_start,
    scopeEnd: group.scope_end,
    slotMinutes: group.slot_minutes,
    searchStartTime: normalizeTime(group.search_start_time),
    searchEndTime: normalizeTime(group.search_end_time),
    visibilityMode: group.visibility_mode,
    visibilitySettings: sanitizeVisibilitySettings(group.visibility_mode, group.visibility_settings),
    suggestionMode: group.suggestion_mode,
    status: group.status,
    createdAt: optionalString(group.created_at),
    updatedAt: optionalString(group.updated_at),
  };
  if (includeShareCode) result.shareCode = group.share_code;
  return result;
}

function toMemberJson(member: MemberRow, viewerUserId: string): JsonObject {
  return {
    id: optionalString(member.id),
    groupId: member.group_id,
    role: member.role,
    isMe: member.user_id === viewerUserId,
    joinedAt: optionalString(member.joined_at),
  };
}

function toMembersSummary(members: MemberRow[], viewerUserId: string): JsonObject {
  return {
    totalCount: members.length,
    ownerCount: members.filter((member) => member.role === "owner").length,
    myRole: members.find((member) => member.user_id === viewerUserId)?.role ?? null,
  };
}

function toDummyScheduleJson(schedule: DummyScheduleRow, viewerUserId: string): JsonObject {
  const result: JsonObject = {
    id: schedule.id,
    groupId: schedule.group_id,
    startAt: schedule.start_at,
    endAt: schedule.end_at,
    isMine: schedule.created_by === viewerUserId,
    createdAt: optionalString(schedule.created_at),
    updatedAt: optionalString(schedule.updated_at),
  };
  if (schedule.created_by === viewerUserId) result.privateNote = optionalString(schedule.private_note);
  return result;
}

function toProposalJson(proposal: ProposalRow, responses: ProposalResponseRow[], viewerUserId: string): JsonObject {
  const counts = responseCounts(responses);
  const mine = responses.find((response) => response.user_id === viewerUserId) ?? null;
  return {
    id: proposal.id,
    groupId: proposal.group_id,
    title: proposal.title,
    startAt: proposal.start_at,
    endAt: proposal.end_at,
    visibilityMode: proposal.snapshot_visibility_mode ?? "busy_only",
    availabilitySnapshot: {
      availableCount: Number(proposal.snapshot_available_count),
      unavailableCount: Number(proposal.snapshot_unavailable_count),
      totalCount: Number(proposal.snapshot_total_count),
    },
    responseSummary: counts,
    myResponse: mine ? toProposalResponseJson(mine, viewerUserId) : null,
    status: proposal.status,
    finalizedAt: optionalString(proposal.finalized_at),
    createdAt: optionalString(proposal.created_at),
    updatedAt: optionalString(proposal.updated_at),
  };
}

function toProposalResponseJson(response: ProposalResponseRow, viewerUserId: string): JsonObject {
  return {
    id: response.id,
    proposalId: response.proposal_id,
    response: response.response,
    isMine: response.user_id === viewerUserId,
    respondedAt: optionalString(response.responded_at),
    createdAt: optionalString(response.created_at),
    updatedAt: optionalString(response.updated_at),
  };
}

function toProposalCommentJson(comment: ProposalCommentRow, viewerUserId: string): JsonObject {
  return {
    id: comment.id,
    proposalId: comment.proposal_id,
    groupId: comment.group_id,
    body: comment.body,
    author: { isMe: comment.created_by === viewerUserId },
    createdAt: optionalString(comment.created_at),
    updatedAt: optionalString(comment.updated_at),
  };
}

function responseCounts(responses: ProposalResponseRow[]): JsonObject {
  return {
    pendingCount: responses.filter((response) => response.response === "pending").length,
    acceptedCount: responses.filter((response) => response.response === "accepted").length,
    rejectedCount: responses.filter((response) => response.response === "rejected").length,
    totalCount: responses.length,
  };
}

function assertGroupActive(group: GroupRow): void {
  if (group.status !== "active") throw apiError(400, "Availability group is not active.");
}

function assertCanCreateProposal(group: GroupRow, membership: MemberRow): void {
  if (group.suggestion_mode === "everyone") return;
  if (group.suggestion_mode === "owner_only" && membership.role === "owner") return;
  if (group.suggestion_mode === "owner_leader" && (membership.role === "owner" || membership.role === "leader")) return;
  throw apiError(403, "Current suggestion mode does not allow this member to create proposals.");
}

function readVisibilityMode(value: unknown): VisibilityMode {
  const mode = optionalString(value) ?? "busy_only";
  if (mode === "busy_only" || mode === "expanded_limited") return mode;
  throw apiError(400, "Visibility mode must be busy_only or expanded_limited.");
}

function readSuggestionMode(value: unknown): SuggestionMode {
  const mode = optionalString(value) ?? "everyone";
  if (mode === "everyone" || mode === "owner_leader" || mode === "owner_only") return mode;
  throw apiError(400, "Suggestion mode must be everyone, owner_leader, or owner_only.");
}

function readAvailabilitySort(value: unknown): AvailabilitySort {
  const sort = optionalString(value) ?? "time";
  if (sort === "time" || sort === "rank") return sort;
  throw apiError(400, "Availability sort must be time or rank.");
}

function readVisibilitySettings(value: unknown): JsonObject {
  if (value == null) return {};
  if (!isJsonObject(value)) throw apiError(400, "Visibility settings must be an object.");
  const allowedKeys = new Set(["modeNote", "showDisplayNames"]);
  const settings: JsonObject = {};
  for (const [key, settingValue] of Object.entries(value)) {
    if (!allowedKeys.has(key)) throw apiError(400, `Unsupported visibility setting: ${key}`);
    if (typeof settingValue !== "string" && typeof settingValue !== "boolean" && settingValue !== null) {
      throw apiError(400, `Invalid visibility setting: ${key}`);
    }
    settings[key] = settingValue;
  }
  return settings;
}

function sanitizeVisibilitySettings(mode: VisibilityMode, settings: JsonObject | null): JsonObject {
  if (mode === "busy_only") return {};
  const safe = settings ?? {};
  return {
    modeNote: optionalString(safe.modeNote) ?? "expanded_limited does not expose personal schedule title, memo, location, source, reason, or raw member ids.",
    showDisplayNames: typeof safe.showDisplayNames === "boolean" ? safe.showDisplayNames : false,
  };
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rangesOverlap(leftStart: Date, leftEnd: Date, rightStart: Date, rightEnd: Date): boolean {
  return leftStart < rightEnd && leftEnd > rightStart;
}

function generateShareCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (byte) => SHARE_CODE_ALPHABET[byte % SHARE_CODE_ALPHABET.length]).join("");
}

function normalizeTime(value: string): string {
  const match = value.trim().match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!match) throw apiError(400, "Time must use HH:mm format.");
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour < 0 || hour > 24 || minute < 0 || minute > 59 || (hour === 24 && minute !== 0)) throw apiError(400, "Time is out of range.");
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function timeToMinutes(value: string): number {
  const [hour, minute] = normalizeTime(value).split(":").map(Number);
  return hour * 60 + minute;
}

function parseIsoDate(value: string, name: string): Date {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw apiError(400, `${name} must be a valid ISO timestamp.`);
  return date;
}

function requiredString(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);
  return value.trim();
}

function optionalString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed === "null" ? null : value;
}

function optionalNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

