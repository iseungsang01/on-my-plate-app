/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError, jsonResponse, readJson } from "./http.ts";
import {
  attachRecurrenceToSchedules,
  readRecurrenceExceptions,
  readRecurrenceRule,
  saveScheduleRecurrence,
  type RecurrenceExceptionInput,
  type RecurrenceRuleInput,
} from "./recurrence.ts";

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };
type SchedulePayload = {
  schedule: JsonObject;
  recurrence: RecurrenceRuleInput | null;
  recurrenceExceptions: RecurrenceExceptionInput[];
};

const PUBLIC_ID_PREFIX = "pb";

export async function profile(userId: string): Promise<Response> {
  const plannerProfile = await ensureProfile(userId);
  return jsonResponse({ userId: plannerProfile.user_id, publicId: plannerProfile.public_id });
}

export async function createGroup(userId: string, request: Request): Promise<Response> {
  const body = await readJson(request);
  const partnerPublicId = requiredString(body.partnerPublicId, "상대 공유 ID를 입력하세요.").trim();
  const currentProfile = await ensureProfile(userId);
  if (partnerPublicId === currentProfile.public_id) {
    throw apiError(400, "내 공유 ID로는 그룹을 만들 수 없습니다.");
  }

  const { data: partner, error: partnerError } = await db
    .from("planner_profiles")
    .select("user_id,public_id")
    .eq("public_id", partnerPublicId)
    .maybeSingle();
  if (partnerError) throw apiError(500, partnerError.message);
  if (!partner) throw apiError(404, "상대 공유 ID를 찾을 수 없습니다.");

  const existing = await findExistingGroup(userId, partner.user_id);
  if (existing) return jsonResponse(toGroupJson(existing));

  const { data: group, error: groupError } = await db
    .from("planner_groups")
    .insert({ name: "공유 그룹", created_by: userId })
    .select("id,name")
    .single();
  if (groupError) throw apiError(500, groupError.message);

  const { error: membersError } = await db.from("planner_group_members").insert([
    { group_id: group.id, user_id: userId, role: "owner" },
    { group_id: group.id, user_id: partner.user_id, role: "member" },
  ]);
  if (membersError) throw apiError(500, membersError.message);
  return jsonResponse(toGroupJson(group));
}

export async function listGroups(userId: string): Promise<Response> {
  const groups = await groupsForUser(userId);
  return jsonResponse({ groups: groups.map(toGroupJson) });
}

export async function uploadSharedSchedule(userId: string, groupId: string, request: Request): Promise<Response> {
  await requireGroupMember(userId, groupId);
  const payload = await readSchedulePayload(request);
  const { data, error } = await db
    .from("planner_schedules")
    .upsert(
      { ...payload.schedule, group_id: groupId, created_by: userId },
      { onConflict: "group_id,created_by,local_schedule_id" },
    )
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  await saveScheduleRecurrence(
    String(data.id),
    payload.recurrence,
    payload.recurrenceExceptions,
    "planner_schedule_recurrence_rules",
    "planner_schedule_recurrence_exceptions",
  );
  const schedule = await attachRecurrenceToSchedules(
    [{ ...data, is_dummy: false }],
    "planner_schedule_recurrence_rules",
    "planner_schedule_recurrence_exceptions",
  );
  return jsonResponse({ schedule: toScheduleJson(schedule[0]) });
}

export async function listSharedSchedules(userId: string, groupId: string, includeDummy: boolean): Promise<Response> {
  await requireGroupMember(userId, groupId);
  const { data: schedules, error } = await db
    .from("planner_schedules")
    .select("id,title,start_at,end_at,location,status")
    .eq("group_id", groupId)
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);

  const shared = await attachRecurrenceToSchedules(
    (schedules ?? []).map((item) => ({ ...item, is_dummy: false })),
    "planner_schedule_recurrence_rules",
    "planner_schedule_recurrence_exceptions",
  );
  let all = shared.map(toScheduleJson);
  if (includeDummy) {
    const { data: dummy, error: dummyError } = await db
      .from("planner_dummy_schedules")
      .select("id,title,start_at,end_at,location,status")
      .eq("group_id", groupId)
      .order("start_at", { ascending: true });
    if (dummyError) throw apiError(500, dummyError.message);
    all = all.concat((dummy ?? []).map((item) => toScheduleJson({ ...item, is_dummy: true })));
  }
  all.sort((left, right) => String(left.startAt).localeCompare(String(right.startAt)));
  return jsonResponse({ schedules: all });
}

async function ensureProfile(userId: string): Promise<{ user_id: string; public_id: string }> {
  const { data: existing, error: existingError } = await db
    .from("planner_profiles")
    .select("user_id,public_id")
    .eq("user_id", userId)
    .maybeSingle();
  if (existingError) throw apiError(500, existingError.message);
  if (existing) return existing;

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const publicId = `${PUBLIC_ID_PREFIX}-${crypto.randomUUID().replace(/-/g, "").toUpperCase().slice(0, 10)}`;
    const { data, error } = await db
      .from("planner_profiles")
      .insert({ user_id: userId, public_id: publicId, display_name: userId })
      .select("user_id,public_id")
      .single();
    if (!error) return data;
    if (error.code !== "23505") throw apiError(500, error.message);
  }
  throw apiError(500, "공유 ID를 생성하지 못했습니다.");
}

async function findExistingGroup(userId: string, partnerUserId: string): Promise<{ id: string; name: string } | null> {
  const memberships = await membershipsForUser(userId);
  const groupIds = memberships.map((membership) => membership.group_id);
  if (groupIds.length === 0) return null;

  const { data: partnerMemberships, error } = await db
    .from("planner_group_members")
    .select("group_id")
    .eq("user_id", partnerUserId)
    .in("group_id", groupIds);
  if (error) throw apiError(500, error.message);

  const sharedGroupId = partnerMemberships?.[0]?.group_id;
  if (!sharedGroupId) return null;
  const { data: group, error: groupError } = await db
    .from("planner_groups")
    .select("id,name")
    .eq("id", sharedGroupId)
    .single();
  if (groupError) throw apiError(500, groupError.message);
  return group;
}

async function groupsForUser(userId: string): Promise<Array<{ id: string; name: string }>> {
  const memberships = await membershipsForUser(userId);
  const groupIds = memberships.map((membership) => membership.group_id);
  if (groupIds.length === 0) return [];

  const { data: groups, error } = await db
    .from("planner_groups")
    .select("id,name")
    .in("id", groupIds)
    .order("created_at", { ascending: false });
  if (error) throw apiError(500, error.message);
  return groups ?? [];
}

async function membershipsForUser(userId: string): Promise<Array<{ group_id: string }>> {
  const { data, error } = await db
    .from("planner_group_members")
    .select("group_id")
    .eq("user_id", userId);
  if (error) throw apiError(500, error.message);
  return data ?? [];
}

async function requireGroupMember(userId: string, groupId: string): Promise<void> {
  const { data, error } = await db
    .from("planner_group_members")
    .select("group_id")
    .eq("user_id", userId)
    .eq("group_id", groupId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(403, "공유 그룹 권한이 없습니다.");
}

async function readSchedulePayload(request: Request): Promise<SchedulePayload> {
  const body = await readJson(request);
  return {
    schedule: {
      local_schedule_id: optionalString(body.localScheduleId),
      title: requiredString(body.title, "일정 제목을 입력하세요."),
      start_at: requiredString(body.startAt, "시작 시간이 필요합니다."),
      end_at: optionalString(body.endAt),
      location: optionalString(body.location),
      memo: optionalString(body.memo),
      status: optionalString(body.status) ?? "planned",
      source_text: optionalString(body.sourceText),
      source_app: optionalString(body.sourceApp),
      updated_at: new Date().toISOString(),
    },
    recurrence: readRecurrenceRule(body.recurrence),
    recurrenceExceptions: readRecurrenceExceptions(body.recurrenceExceptions),
  };
}

function toGroupJson(group: { id: string; name: string }): JsonObject {
  return { groupId: group.id, id: group.id, name: group.name };
}

function toScheduleJson(schedule: Record<string, unknown>): JsonObject {
  return {
    id: String(schedule.id),
    localScheduleId: optionalString(schedule.local_schedule_id),
    title: String(schedule.title),
    startAt: String(schedule.start_at),
    endAt: optionalString(schedule.end_at),
    location: optionalString(schedule.location),
    memo: optionalString(schedule.memo),
    status: optionalString(schedule.status) ?? "planned",
    sourceText: optionalString(schedule.source_text),
    sourceApp: optionalString(schedule.source_app),
    createdAt: optionalString(schedule.created_at),
    updatedAt: optionalString(schedule.updated_at),
    isDummy: Boolean(schedule.is_dummy),
    recurrence: (schedule.recurrence as JsonObject | null | undefined) ?? null,
    recurrenceExceptions: (schedule.recurrence_exceptions as JsonValue[] | undefined) ?? [],
  };
}

function requiredString(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);
  return value;
}

function optionalString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed === "null" ? null : value;
}
