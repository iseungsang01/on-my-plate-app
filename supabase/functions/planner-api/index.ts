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
import {
  assignAvailabilityGroupLeader,
  createAvailabilityGroup,
  createAvailabilityGroupDummySchedule,
  createAvailabilityGroupProposal,
  createAvailabilityGroupProposalComment,
  deleteAvailabilityGroupDummySchedule,
  finalizeAvailabilityGroupProposal,
  getAvailability,
  getAvailabilityGroup,
  joinAvailabilityGroup,
  listAvailabilityGroupDummySchedules,
  listAvailabilityGroupProposalComments,
  listAvailabilityGroupProposals,
  respondToAvailabilityGroupProposal,
  unassignAvailabilityGroupLeader,
  updateAvailabilityGroupSettings,
} from "./availability_groups.ts";

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

    if (path === "/api/planner/availability-groups") {
      const userId = await requireUserId(request);
      if (method === "POST") return await createAvailabilityGroup(userId, request);
    }

    if (path === "/api/planner/availability-groups/join") {
      const userId = await requireUserId(request);
      if (method === "POST") return await joinAvailabilityGroup(userId, request);
    }

    const availabilityGroupMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)$/);
    if (availabilityGroupMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityGroupMatch[1]);
      if (method === "GET") return await getAvailabilityGroup(userId, groupId);
      if (method === "PATCH") return await updateAvailabilityGroupSettings(userId, groupId, request);
    }

    const availabilityMemberLeaderMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/members\/([^/]+)\/leader$/);
    if (availabilityMemberLeaderMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityMemberLeaderMatch[1]);
      const memberId = decodeURIComponent(availabilityMemberLeaderMatch[2]);
      if (method === "POST") return await assignAvailabilityGroupLeader(userId, groupId, memberId);
      if (method === "DELETE") return await unassignAvailabilityGroupLeader(userId, groupId, memberId);
    }

    const availabilityMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/availability$/);
    if (availabilityMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityMatch[1]);
      if (method === "GET") return await getAvailability(userId, groupId);
    }

    const availabilityDummyMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/dummy-schedules$/);
    if (availabilityDummyMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityDummyMatch[1]);
      if (method === "GET") return await listAvailabilityGroupDummySchedules(userId, groupId);
      if (method === "POST") return await createAvailabilityGroupDummySchedule(userId, groupId, request);
    }

    const availabilityDummyItemMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/dummy-schedules\/([^/]+)$/);
    if (availabilityDummyItemMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityDummyItemMatch[1]);
      const dummyScheduleId = decodeURIComponent(availabilityDummyItemMatch[2]);
      if (method === "DELETE") return await deleteAvailabilityGroupDummySchedule(userId, groupId, dummyScheduleId);
    }

    const availabilityProposalsMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/proposals$/);
    if (availabilityProposalsMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityProposalsMatch[1]);
      if (method === "GET") return await listAvailabilityGroupProposals(userId, groupId);
      if (method === "POST") return await createAvailabilityGroupProposal(userId, groupId, request);
    }

    const availabilityProposalResponseMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/proposals\/([^/]+)\/response$/);
    if (availabilityProposalResponseMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityProposalResponseMatch[1]);
      const proposalId = decodeURIComponent(availabilityProposalResponseMatch[2]);
      if (method === "POST" || method === "PATCH") return await respondToAvailabilityGroupProposal(userId, groupId, proposalId, request);
    }

    const availabilityProposalCommentsMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/proposals\/([^/]+)\/comments$/);
    if (availabilityProposalCommentsMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityProposalCommentsMatch[1]);
      const proposalId = decodeURIComponent(availabilityProposalCommentsMatch[2]);
      if (method === "GET") return await listAvailabilityGroupProposalComments(userId, groupId, proposalId);
      if (method === "POST") return await createAvailabilityGroupProposalComment(userId, groupId, proposalId, request);
    }

    const availabilityProposalFinalizeMatch = path.match(/^\/api\/planner\/availability-groups\/([^/]+)\/proposals\/([^/]+)\/finalize$/);
    if (availabilityProposalFinalizeMatch) {
      const userId = await requireUserId(request);
      const groupId = decodeURIComponent(availabilityProposalFinalizeMatch[1]);
      const proposalId = decodeURIComponent(availabilityProposalFinalizeMatch[2]);
      if (method === "POST") return await finalizeAvailabilityGroupProposal(userId, groupId, proposalId);
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
