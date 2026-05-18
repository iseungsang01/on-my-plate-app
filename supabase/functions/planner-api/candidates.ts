/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError, jsonResponse, readJson } from "./http.ts";

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };

const CANDIDATE_PARSE_SOURCES = new Set([
  "llm_success",
  "llm_with_local_supplement",
  "local_fallback",
  "parser_error",
  "local_only",
  "unknown",
]);

export async function createCandidate(userId: string, request: Request): Promise<Response> {
  const payload = readCandidatePayload(await readJson(request));
  const { data, error } = await db
    .from("planner_candidates")
    .insert({ ...payload, created_by: userId })
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ candidate: toCandidateJson(data) });
}

export async function listCandidates(userId: string, status: string | null): Promise<Response> {
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

export async function getCandidate(userId: string, candidateId: string): Promise<Response> {
  const candidate = await candidateForUser(userId, candidateId);
  return jsonResponse({ candidate: toCandidateJson(candidate) });
}

export async function updateCandidate(userId: string, candidateId: string, request: Request): Promise<Response> {
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

export async function discardCandidate(userId: string, candidateId: string): Promise<Response> {
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

function requiredString(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);
  return value;
}

function optionalString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed === "null" ? null : value;
}
