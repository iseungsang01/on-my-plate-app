/// <reference lib="deno.ns" />

import { db, hasSupabaseConfig } from "./db.ts";
import { parseAppointment } from "./parser.ts";
import { apiError, corsHeaders, errorResponse, jsonResponse, readJson, toApiError } from "./http.ts";
import { changePassword, login, optionalUserId, requireUserId, signUp } from "./auth.ts";
import { attachRecurrenceToSchedules, readRecurrenceExceptions, readRecurrenceRule, saveScheduleRecurrence, type RecurrenceExceptionInput, type RecurrenceRuleInput } from "./recurrence.ts";
import { addPersonalScheduleRecurrenceException, deletePersonalSchedule, getPersonalSchedule, listPersonalSchedules, updatePersonalSchedule, uploadPersonalSchedule } from "./personal_schedules.ts";
import { createCandidate, discardCandidate, getCandidate, listCandidates, updateCandidate } from "./candidates.ts";
import { createGroup, listGroups, listSharedSchedules, profile, uploadSharedSchedule } from "./sharing.ts";

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


const FUNCTION_NAME = "planner-api";

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

> {
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

| null> {
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

>> {
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

>> {
  const { data, error } = await db
    .from("planner_group_members")
    .select("group_id")
    .eq("user_id", userId);
  if (error) throw apiError(500, error.message);
  return data ?? [];
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

): JsonObject {
  return { groupId: group.id, id: group.id, name: group.name };
}

