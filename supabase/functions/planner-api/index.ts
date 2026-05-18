/// <reference lib="deno.ns" />

import { db, hasSupabaseConfig } from "./db.ts";
import { parseAppointment } from "./parser.ts";
import { apiError, corsHeaders, errorResponse, readJson, toApiError } from "./http.ts";
import { changePassword, login, requireUserId, signUp } from "./auth.ts";
import {
  addPersonalScheduleRecurrenceException,
  deletePersonalSchedule,
  getPersonalSchedule,
  listPersonalSchedules,
  updatePersonalSchedule,
  uploadPersonalSchedule,
} from "./personal_schedules.ts";
import { createCandidate, discardCandidate, getCandidate, listCandidates, updateCandidate } from "./candidates.ts";
import { createGroup, listGroups, listSharedSchedules, profile, uploadSharedSchedule } from "./sharing.ts";
import { submitFeedback } from "./feedback.ts";

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
