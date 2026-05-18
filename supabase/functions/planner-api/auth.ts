/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError } from "./http.ts";

const SESSION_TOKEN_PREFIX = "omp_session_v1_";

export async function requireUserId(request: Request): Promise<string> {
  const userId = await userIdFromSession(request, true);
  if (!userId) throw apiError(401, "로그인이 필요합니다.");
  return userId;
}

export async function optionalUserId(request: Request): Promise<string | null> {
  return await userIdFromSession(request, false);
}

async function userIdFromSession(request: Request, required: boolean): Promise<string | null> {
  const header = request.headers.get("authorization") ?? "";
  if (!header.trim()) {
    if (required) throw apiError(401, "로그인이 필요합니다.");
    return null;
  }
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) throw apiError(401, "로그인이 필요합니다.");

  const token = match[1].trim();
  if (!token.startsWith(SESSION_TOKEN_PREFIX)) {
    throw apiError(401, "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
  }

  const tokenHash = await hashSessionToken(token);
  const { data: session, error } = await db
    .from("planner_sessions")
    .select("user_id,expires_at,revoked_at")
    .eq("token_hash", tokenHash)
    .maybeSingle();

  if (error) throw apiError(500, error.message);
  if (!session || session.revoked_at) {
    throw apiError(401, "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
  }

  const expiresAt = Date.parse(String(session.expires_at));
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    throw apiError(401, "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
  }

  return String(session.user_id);
}

async function hashSessionToken(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return toHex(digest);
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
