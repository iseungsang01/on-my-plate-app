/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { optionalUserId } from "./auth.ts";
import { apiError, jsonResponse, readJson } from "./http.ts";

type FeedbackPayload = {
  message: string;
  sourceScreen: string;
  appVersionName: string;
  appVersionCode: number;
};

export async function submitFeedback(request: Request): Promise<Response> {
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

function requiredString(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);
  return value;
}

function optionalString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed === "null" ? null : value;
}

function requiredPositiveInteger(value: unknown, message: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {
    throw apiError(400, message);
  }
  return value;
}
