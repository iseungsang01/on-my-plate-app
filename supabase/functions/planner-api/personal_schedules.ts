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

export async function uploadPersonalSchedule(userId: string, request: Request): Promise<Response> {
  const payload = await readSchedulePayload(request);
  const { data, error } = await db
    .from("planner_personal_schedules")
    .upsert(
      { ...payload.schedule, created_by: userId },
      { onConflict: "created_by,local_schedule_id" },
    )
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  await saveScheduleRecurrence(
    String(data.id),
    payload.recurrence,
    payload.recurrenceExceptions,
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  const schedule = await attachRecurrenceToSchedules(
    [{ ...data, is_dummy: false }],
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  return jsonResponse({ schedule: toScheduleJson(schedule[0]) });
}

export async function listPersonalSchedules(userId: string): Promise<Response> {
  const { data: schedules, error } = await db
    .from("planner_personal_schedules")
    .select("id,local_schedule_id,title,start_at,end_at,location,memo,status,source_text,source_app,created_at,updated_at")
    .eq("created_by", userId)
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);
  const withRecurrence = await attachRecurrenceToSchedules(
    schedules ?? [],
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  return jsonResponse({ schedules: withRecurrence.map(toScheduleJson) });
}

export async function getPersonalSchedule(userId: string, scheduleId: string): Promise<Response> {
  const schedule = await personalScheduleForUser(userId, scheduleId);
  const withRecurrence = await attachRecurrenceToSchedules(
    [schedule],
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  return jsonResponse({ schedule: toScheduleJson(withRecurrence[0]) });
}

export async function updatePersonalSchedule(userId: string, scheduleId: string, request: Request): Promise<Response> {
  await personalScheduleForUser(userId, scheduleId);
  const payload = await readSchedulePayload(request);
  const { data, error } = await db
    .from("planner_personal_schedules")
    .update(payload.schedule)
    .eq("id", scheduleId)
    .eq("created_by", userId)
    .select("id,local_schedule_id,title,start_at,end_at,location,memo,status,source_text,source_app,created_at,updated_at")
    .single();
  if (error) throw apiError(500, error.message);
  await saveScheduleRecurrence(
    String(data.id),
    payload.recurrence,
    payload.recurrenceExceptions,
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  const withRecurrence = await attachRecurrenceToSchedules(
    [data],
    "planner_personal_schedule_recurrence_rules",
    "planner_personal_schedule_recurrence_exceptions",
  );
  return jsonResponse({ schedule: toScheduleJson(withRecurrence[0]) });
}

export async function deletePersonalSchedule(userId: string, scheduleId: string): Promise<Response> {
  await personalScheduleForUser(userId, scheduleId);
  const { data, error } = await db.rpc("delete_personal_schedule", {
    p_user_id: userId,
    p_schedule_id: scheduleId,
  });
  if (error) throw apiError(500, error.message);
  if (data !== true) {
    await personalScheduleForUser(userId, scheduleId);
    throw apiError(500, "Personal schedule delete did not affect a row.");
  }
  return jsonResponse({ ok: true });
}

export async function addPersonalScheduleRecurrenceException(userId: string, scheduleId: string, request: Request): Promise<Response> {
  await personalScheduleForUser(userId, scheduleId);
  const body = await readJson(request);
  const exception = readRecurrenceExceptions([body])[0];
  const { error } = await db
    .from("planner_personal_schedule_recurrence_exceptions")
    .upsert(
      {
        schedule_id: scheduleId,
        occurrence_start_at: exception.occurrenceStartAt,
        action: exception.action,
      },
      { onConflict: "schedule_id,occurrence_start_at" },
    );
  if (error) throw apiError(500, error.message);
  return await getPersonalSchedule(userId, scheduleId);
}

async function personalScheduleForUser(userId: string, scheduleId: string): Promise<Record<string, unknown>> {
  const { data, error } = await db
    .from("planner_personal_schedules")
    .select("id,local_schedule_id,title,start_at,end_at,location,memo,status,source_text,source_app,created_at,updated_at")
    .eq("id", scheduleId)
    .eq("created_by", userId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!data) throw apiError(404, "일정을 찾을 수 없습니다.");
  return data;
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
