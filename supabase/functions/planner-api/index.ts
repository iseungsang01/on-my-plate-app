/// <reference lib="deno.ns" />

import { db, hasSupabaseConfig } from "./db.ts";
import { parseAppointment } from "./parser.ts";
import { apiError, corsHeaders, errorResponse, jsonResponse, readJson, toApiError } from "./http.ts";
import { changePassword, login, optionalUserId, requireUserId, signUp } from "./auth.ts";
import { attachRecurrenceToSchedules, readRecurrenceExceptions, readRecurrenceRule, saveScheduleRecurrence, type RecurrenceExceptionInput, type RecurrenceRuleInput } from "./recurrence.ts";
import { addPersonalScheduleRecurrenceException, deletePersonalSchedule, getPersonalSchedule, listPersonalSchedules, updatePersonalSchedule, uploadPersonalSchedule } from "./personal_schedules.ts";

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };
type SchedulePayload = {
  schedule: JsonObject;
  recurrence: RecurrenceRuleInput | null;
  recurrenceExceptions: RecurrenceExceptionInput[];
};
type FeedbackPayload = {
  message: string;
  sourceScreen: string;
  appVersionName: string;
  appVersionCode: number;
};

const CANDIDATE_PARSE_SOURCES = new Set([
  "llm_success",
  "llm_with_local_supplement",
  "local_fallback",
  "parser_error",
  "local_only",
  "unknown",
]);

const FUNCTION_NAME = "planner-api";
const PUBLIC_ID_PREFIX = "pb";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    if (!hasSupabaseConfig()) {
      return errorResponse(500, "Supabase Edge Function 환경 변수가 설정되지 않았습니다.");
    }

    const url = new URL(request.url);
    const path = normalizePath(url.pathname);
    const method = request.method.toUpperCase();

    if (method === "POST" && path === "/api/auth/signup") return await signUp(request);
    if (method === "POST" && path === "/api/auth/login") return await login(request);
    if (method === "POST" && path === "/api/auth/password") return await changePassword(request);

    if (method === "POST" && path === "/api/parser/appointment") {
      await requireUserId(request);
      return await parseAppointment(request);
    }

    if (method === "POST" && path === "/api/planner/share/profile") {
      const userId = await requireUserId(request);
      return await profile(userId);
    }

    if (path === "/api/planner/share/groups") {
      const userId = await requireUserId(request);
      if (method === "GET") return await listGroups(userId);
      if (method === "POST") return await createGroup(userId, request);
    }

    const groupSchedulesMatch = path.match(/^\/api\/planner\/share\/groups\/([^/]+)\/schedules$/);
    if (groupSchedulesMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(groupSchedulesMatch[1]);
      if (method === "GET") {
        const includeDummy = url.searchParams.get("includeDummy") === "true";
        return await listSharedSchedules(userId, groupId, includeDummy);
      }
      if (method === "POST") return await uploadSharedSchedule(userId, groupId, request);
    }

    if (path === "/api/planner/schedules") {
      const userId = await requireUserId(request);
      if (method === "GET") return await listPersonalSchedules(userId);
      if (method === "POST") return await uploadPersonalSchedule(userId, request);
    }

    const personalScheduleMatch = path.match(/^\/api\/planner\/schedules\/([^/]+)$/);
    if (personalScheduleMatch) {
      const userId = await requireUserId(request);
      const scheduleId = decodeURIComponent(personalScheduleMatch[1]);
      if (method === "GET") return await getPersonalSchedule(userId, scheduleId);
      if (method === "PATCH") return await updatePersonalSchedule(userId, scheduleId, request);
      if (method === "DELETE") return await deletePersonalSchedule(userId, scheduleId);
    }

    const personalScheduleExceptionMatch = path.match(/^\/api\/planner\/schedules\/([^/]+)\/recurrence-exceptions$/);
    if (personalScheduleExceptionMatch) {
      const userId = await requireUserId(request);
      const scheduleId = decodeURIComponent(personalScheduleExceptionMatch[1]);
      if (method === "POST") return await addPersonalScheduleRecurrenceException(userId, scheduleId, request);
    }

    if (path === "/api/planner/candidates") {
      const userId = await requireUserId(request);
      if (method === "GET") return await listCandidates(userId, url.searchParams.get("status"));
      if (method === "POST") return await createCandidate(userId, request);
    }

    const candidateMatch = path.match(/^\/api\/planner\/candidates\/([^/]+)$/);
    if (candidateMatch) {
      const userId = await requireUserId(request);
      const candidateId = decodeURIComponent(candidateMatch[1]);
      if (method === "GET") return await getCandidate(userId, candidateId);
      if (method === "PATCH") return await updateCandidate(userId, candidateId, request);
    }

    const candidateDiscardMatch = path.match(/^\/api\/planner\/candidates\/([^/]+)\/discard$/);
    if (candidateDiscardMatch) {
      const userId = await requireUserId(request);
      const candidateId = decodeURIComponent(candidateDiscardMatch[1]);
      if (method === "POST") return await discardCandidate(userId, candidateId);
    }

    if (method === "POST" && path === "/api/planner/feedback") {
      return await submitFeedback(request);
    }

    return errorResponse(404, "약속 바구니 API 경로를 찾을 수 없습니다.");
  } catch (error) {
    const apiError = toApiError(error);
    return errorResponse(apiError.status, apiError.message);
  }
});

function normalizePath(pathname: string): string {
  const marker = `/${FUNCTION_NAME}`;
  const index = pathname.indexOf(marker);
  const stripped = index >= 0
    ? pathname.slice(index + marker.length)
    : pathname.replace(/^\/functions\/v1(?=\/|$)/, "");
  return stripped.startsWith("/") ? stripped : `/${stripped}`;
}

async function profile(userId: string): Promise<Response> {
  const plannerProfile = await ensureProfile(userId);
  return jsonResponse({
    userId: plannerProfile.user_id,
    publicId: plannerProfile.public_id,
  });
}

async function createGroup(userId: string, request: Request): Promise<Response> {
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

async function listGroups(userId: string): Promise<Response> {
  const groups = await groupsForUser(userId);
  return jsonResponse({ groups: groups.map(toGroupJson) });
}

async function uploadSharedSchedule(userId: string, groupId: string, request: Request): Promise<Response> {
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

async function createCandidate(userId: string, request: Request): Promise<Response> {
  const payload = readCandidatePayload(await readJson(request));
  const { data, error } = await db
    .from("planner_candidates")
    .insert({ ...payload, created_by: userId })
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ candidate: toCandidateJson(data) });
}

async function listCandidates(userId: string, status: string | null): Promise<Response> {
  let query = db
    .from("planner_candidates")
    .select()
    .eq("created_by", userId)
    .order("created_at", { ascending: false });
  if (status) query = query.eq("status", status);
  const { data, error } = await query;
  if (error) throw apiError(500, error.message);
  return jsonResponse({ candidates: (data ?? []).map(toCandidateJson) });
}

async function getCandidate(userId: string, candidateId: string): Promise<Response> {
  const candidate = await candidateForUser(userId, candidateId);
  return jsonResponse({ candidate: toCandidateJson(candidate) });
}

async function updateCandidate(userId: string, candidateId: string, request: Request): Promise<Response> {
  await candidateForUser(userId, candidateId);
  const body = await readJson(request);
  const updates: Record<string, unknown> = { updated_at: new Date().toISOString() };
  if ("extractedTitle" in body) updates.extracted_title = optionalString(body.extractedTitle) ?? "";
  if ("extractedStartAt" in body) updates.extracted_start_at = optionalString(body.extractedStartAt);
  if ("extractedEndAt" in body) updates.extracted_end_at = optionalString(body.extractedEndAt);
  if ("extractedLocation" in body) updates.extracted_location = optionalString(body.extractedLocation);
  if ("status" in body) {
    const status = optionalString(body.status);
    if (status !== "pending" && status !== "confirmed" && status !== "discarded") throw apiError(400, "Invalid candidate status.");
    updates.status = status;
  }
  const { data, error } = await db
    .from("planner_candidates")
    .update(updates)
    .eq("id", candidateId)
    .eq("created_by", userId)
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ candidate: toCandidateJson(data) });
}

async function discardCandidate(userId: string, candidateId: string): Promise<Response> {
  const request = new Request("http://local/api/planner/candidates", {
    method: "PATCH",
    body: JSON.stringify({ status: "discarded" }),
  });
  return await updateCandidate(userId, candidateId, request);
}

async function candidateForUser(userId: string, candidateId: string): Promise<Record<string, unknown>> {
  const { data, error } = await db
    .from("planner_candidates")
    .select()
    .eq("id", candidateId)
    .eq("created_by", userId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "약속 후보를 찾을 수 없습니다.");
  return data;
}

async function submitFeedback(request: Request): Promise<Response> {
  const payload = readFeedbackPayload(await readJson(request));
  const { error } = await db.from("planner_feedback").insert({
    user_id: await optionalUserId(request),
    message: payload.message,
    source_screen: payload.sourceScreen,
    app_version_name: payload.appVersionName,
    app_version_code: payload.appVersionCode,
  });
  if (error) throw apiError(500, error.message);
  return jsonResponse({ ok: true });
}

async function listSharedSchedules(userId: string, groupId: string, includeDummy: boolean): Promise<Response> {
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

function readFeedbackPayload(body: Record<string, unknown>): FeedbackPayload {
  const message = requiredString(body.message, "피드백 내용을 입력하세요.").trim();
  if (message.length > 2000) throw apiError(400, "피드백은 2000자 이내로 입력하세요.");
  const sourceScreen = optionalString(body.sourceScreen) ?? "settings";
  const appVersionName = requiredString(body.appVersionName, "앱 버전 정보가 없습니다.");
  const appVersionCode = requiredPositiveInteger(body.appVersionCode, "앱 버전 코드가 없습니다.");
  return {
    message,
    sourceScreen,
    appVersionName,
    appVersionCode,
  };
}

function readCandidatePayload(body: Record<string, unknown>): Record<string, unknown> {
  const status = optionalString(body.status) ?? "pending";
  if (status !== "pending" && status !== "confirmed" && status !== "discarded") {
    throw apiError(400, "Invalid candidate status.");
  }
  const parseSource = readCandidateParseSource(body.parseSource);
  return {
    local_candidate_id: optionalString(body.localCandidateId),
    raw_text: requiredString(body.rawText, "약속 텍스트가 필요합니다."),
    source_app: optionalString(body.sourceApp),
    extracted_title: optionalString(body.extractedTitle) ?? "",
    extracted_start_at: optionalString(body.extractedStartAt),
    extracted_end_at: optionalString(body.extractedEndAt),
    extracted_location: optionalString(body.extractedLocation),
    confidence: typeof body.confidence === "number" ? body.confidence : 0,
    time_confidence: optionalString(body.timeConfidence) ?? "",
    parse_source: parseSource,
    status,
    created_at: optionalString(body.createdAt) ?? new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

function readCandidateParseSource(value: unknown): string {
  const parseSource = optionalString(value) ?? "unknown";
  if (!CANDIDATE_PARSE_SOURCES.has(parseSource)) {
    throw apiError(400, "Invalid candidate parse source.");
  }
  return parseSource;
}

function normalizeIdentifier(value: unknown): string {
  const id = requiredString(value, "아이디를 입력하세요.").trim();
  if (!/^[A-Za-z0-9._-]{3,40}$/.test(id)) {
    throw apiError(400, "아이디는 영문, 숫자, ., _, - 조합 3~40자로 입력하세요.");
  }
  return id;
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

function optionalPositiveInteger(value: unknown, fallback: number): number {
  if (value == null) return fallback;
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {
    throw apiError(400, "Expected a positive integer.");
  }
  return value;
}

function requiredPositiveInteger(value: unknown, message: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {
    throw apiError(400, message);
  }
  return value;
}

function optionalPositiveIntegerOrNull(value: unknown): number | null {
  if (value == null) return null;
  return optionalPositiveInteger(value, 0);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
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

function toCandidateJson(candidate: Record<string, unknown>): JsonObject {
  return {
    id: String(candidate.id),
    localCandidateId: optionalString(candidate.local_candidate_id),
    rawText: String(candidate.raw_text),
    sourceApp: optionalString(candidate.source_app),
    extractedTitle: optionalString(candidate.extracted_title) ?? "",
    extractedStartAt: optionalString(candidate.extracted_start_at),
    extractedEndAt: optionalString(candidate.extracted_end_at),
    extractedLocation: optionalString(candidate.extracted_location),
    confidence: Number(candidate.confidence ?? 0),
    timeConfidence: optionalString(candidate.time_confidence) ?? "",
    parseSource: optionalString(candidate.parse_source) ?? "unknown",
    status: optionalString(candidate.status) ?? "pending",
    createdAt: optionalString(candidate.created_at),
    updatedAt: optionalString(candidate.updated_at),
  };
}

