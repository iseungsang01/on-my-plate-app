/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError } from "./http.ts";

export type RecurrenceRuleInput = {
  frequency: "daily" | "weekly" | "monthly";
  interval: number;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  untilAt: string | null;
  count: number | null;
};

export type RecurrenceExceptionInput = {
  occurrenceStartAt: string;
  action: "skip";
};

export function readRecurrenceRule(value: unknown): RecurrenceRuleInput | null {
  if (value == null) return null;
  if (!isPlainObject(value)) throw apiError(400, "recurrence must be an object or null.");
  const frequency = optionalString(value.frequency) ?? "weekly";
  if (frequency !== "daily" && frequency !== "weekly" && frequency !== "monthly") {
    throw apiError(400, "recurrence.frequency must be daily, weekly, or monthly.");
  }
  const interval = optionalPositiveInteger(value.interval == null ? value.intervalWeeks : value.interval, 1);
  const dayOfWeek = optionalPositiveIntegerOrNull(value.dayOfWeek);
  const dayOfMonth = optionalPositiveIntegerOrNull(value.dayOfMonth);
  if (frequency === "weekly" && (dayOfWeek == null || dayOfWeek < 1 || dayOfWeek > 7)) {
    throw apiError(400, "recurrence.dayOfWeek must be 1-7 for weekly recurrence.");
  }
  if (frequency === "monthly" && (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31)) {
    throw apiError(400, "recurrence.dayOfMonth must be 1-31 for monthly recurrence.");
  }
  return {
    frequency,
    interval,
    dayOfWeek: frequency === "weekly" ? dayOfWeek : null,
    dayOfMonth: frequency === "monthly" ? dayOfMonth : null,
    untilAt: optionalString(value.untilAt),
    count: optionalPositiveIntegerOrNull(value.count),
  };
}

export function readRecurrenceExceptions(value: unknown): RecurrenceExceptionInput[] {
  if (value == null) return [];
  if (!Array.isArray(value)) throw apiError(400, "recurrenceExceptions must be an array.");
  return value.map((item) => {
    if (!isPlainObject(item)) throw apiError(400, "recurrenceExceptions entries must be objects.");
    const action = optionalString(item.action) ?? "skip";
    if (action !== "skip") throw apiError(400, "Only skip recurrence exceptions are supported.");
    return {
      occurrenceStartAt: requiredString(item.occurrenceStartAt, "recurrence exception occurrenceStartAt is required."),
      action,
    };
  });
}

export async function saveScheduleRecurrence(
  scheduleId: string,
  recurrence: RecurrenceRuleInput | null,
  exceptions: RecurrenceExceptionInput[],
  ruleTable: string,
  exceptionTable: string,
): Promise<void> {
  if (!recurrence) {
    const { error: deleteExceptionsError } = await db.from(exceptionTable).delete().eq("schedule_id", scheduleId);
    if (deleteExceptionsError) throw apiError(500, deleteExceptionsError.message);
    const { error: deleteRuleError } = await db.from(ruleTable).delete().eq("schedule_id", scheduleId);
    if (deleteRuleError) throw apiError(500, deleteRuleError.message);
    return;
  }

  const now = new Date().toISOString();
  const { error: ruleError } = await db
    .from(ruleTable)
    .upsert(
      {
        schedule_id: scheduleId,
        frequency: recurrence.frequency,
        interval: recurrence.interval,
        interval_weeks: recurrence.frequency === "weekly" ? recurrence.interval : null,
        day_of_week: recurrence.dayOfWeek,
        day_of_month: recurrence.dayOfMonth,
        until_at: recurrence.untilAt,
        count: recurrence.count,
        updated_at: now,
      },
      { onConflict: "schedule_id" },
    );
  if (ruleError) throw apiError(500, ruleError.message);

  const { error: deleteExceptionsError } = await db.from(exceptionTable).delete().eq("schedule_id", scheduleId);
  if (deleteExceptionsError) throw apiError(500, deleteExceptionsError.message);
  if (exceptions.length === 0) return;

  const { error: exceptionsError } = await db.from(exceptionTable).insert(
    exceptions.map((exception) => ({
      schedule_id: scheduleId,
      occurrence_start_at: exception.occurrenceStartAt,
      action: exception.action,
    })),
  );
  if (exceptionsError) throw apiError(500, exceptionsError.message);
}

export async function attachRecurrenceToSchedules(
  schedules: Array<Record<string, unknown>>,
  ruleTable: string,
  exceptionTable: string,
): Promise<Array<Record<string, unknown>>> {
  const ids = schedules.map((schedule) => String(schedule.id));
  if (ids.length === 0) return schedules;

  const { data: rules, error: rulesError } = await db
    .from(ruleTable)
    .select("schedule_id,frequency,interval,interval_weeks,day_of_week,day_of_month,until_at,count")
    .in("schedule_id", ids);
  if (rulesError) throw apiError(500, rulesError.message);

  const { data: exceptions, error: exceptionsError } = await db
    .from(exceptionTable)
    .select("schedule_id,occurrence_start_at,action")
    .in("schedule_id", ids);
  if (exceptionsError) throw apiError(500, exceptionsError.message);

  const rulesByScheduleId = new Map((rules ?? []).map((rule) => [String(rule.schedule_id), rule]));
  const exceptionsByScheduleId = new Map<string, Record<string, unknown>[]>();
  for (const exception of exceptions ?? []) {
    const scheduleId = String(exception.schedule_id);
    const existing = exceptionsByScheduleId.get(scheduleId) ?? [];
    existing.push(exception);
    exceptionsByScheduleId.set(scheduleId, existing);
  }

  return schedules.map((schedule) => {
    const scheduleId = String(schedule.id);
    return {
      ...schedule,
      recurrence: recurrenceRuleToJson(rulesByScheduleId.get(scheduleId)),
      recurrence_exceptions: (exceptionsByScheduleId.get(scheduleId) ?? []).map(recurrenceExceptionToJson),
    };
  });
}

function recurrenceRuleToJson(rule: Record<string, unknown> | undefined): Record<string, unknown> | null {
  if (!rule) return null;
  return {
    frequency: optionalString(rule.frequency) ?? "weekly",
    interval: Number(rule.interval ?? rule.interval_weeks ?? 1),
    intervalWeeks: rule.frequency === "weekly" ? Number(rule.interval ?? rule.interval_weeks ?? 1) : null,
    dayOfWeek: rule.day_of_week == null ? null : Number(rule.day_of_week),
    dayOfMonth: rule.day_of_month == null ? null : Number(rule.day_of_month),
    untilAt: optionalString(rule.until_at),
    count: typeof rule.count === "number" ? rule.count : null,
  };
}

function recurrenceExceptionToJson(exception: Record<string, unknown>): Record<string, unknown> {
  return {
    occurrenceStartAt: String(exception.occurrence_start_at),
    action: optionalString(exception.action) ?? "skip",
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

function optionalPositiveInteger(value: unknown, fallback: number): number {
  if (value == null) return fallback;
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {
    throw apiError(400, "Expected a positive integer.");
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
