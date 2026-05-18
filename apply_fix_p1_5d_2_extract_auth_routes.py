
from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()
BASE = ROOT / "supabase" / "functions" / "planner-api"
INDEX = BASE / "index.ts"
AUTH = BASE / "auth.ts"
MODULES = BASE / "MODULES.md"

AUTH_BLOCK_START = "async function signUp(request: Request): Promise<Response> {"
AUTH_BLOCK_END = "\n\nasync function profile(userId: string): Promise<Response> {"

AUTH_TS_CONTENT = """/// <reference lib="deno.ns" />

import { db } from "./db.ts";
import { apiError, jsonResponse, readJson } from "./http.ts";

const PASSWORD_HASH_PREFIX = "pbkdf2-sha256$v1$";
const LEGACY_SHA256_HASH_PREFIX = "sha256$v1$";
const PASSWORD_HASH_ITERATIONS = 120_000;
const PUBLIC_ID_PREFIX = "pb";
const SESSION_TOKEN_PREFIX = "omp_session_v1_";
const SESSION_TTL_DAYS = Number(Deno.env.get("PLANNER_SESSION_TTL_DAYS") ?? "30");
const SESSION_TTL_MS = Math.max(1, SESSION_TTL_DAYS) * 24 * 60 * 60 * 1000;

export async function signUp(request: Request): Promise<Response> {
  const credentials = await readCredentials(request);
  const passwordHash = await hashPassword(credentials.id, credentials.password);
  const { error } = await db.from("planner_users").insert({
    id: credentials.id,
    password_hash: passwordHash,
  });
  if (error?.code === "23505") throw apiError(409, "이미 사용 중인 아이디입니다.");
  if (error) throw apiError(500, error.message);

  await ensureProfileForAuth(credentials.id);
  return await authResponse(credentials.id);
}

export async function login(request: Request): Promise<Response> {
  const credentials = await readCredentials(request);
  const { data: user, error } = await db
    .from("planner_users")
    .select("id,password_hash")
    .eq("id", credentials.id)
    .maybeSingle();
  if (error) throw apiError(500, error.message);

  if (!user) {
    throw apiError(401, "아이디 또는 비밀번호가 올바르지 않습니다.");
  }

  const matches = await verifyPassword(credentials.id, credentials.password, String(user.password_hash));
  if (!matches) {
    throw apiError(401, "아이디 또는 비밀번호가 올바르지 않습니다.");
  }

  if (!isPasswordHash(String(user.password_hash))) {
    await updatePasswordHash(credentials.id, credentials.password);
  }

  await ensureProfileForAuth(credentials.id);
  return await authResponse(credentials.id);
}

export async function changePassword(request: Request): Promise<Response> {
  const userId = await requireUserId(request);
  const body = await readJson(request);
  const currentPassword = requiredString(body.currentPassword, "현재 비밀번호를 입력하세요.");
  const newPassword = requiredString(body.newPassword, "새 비밀번호를 입력하세요.");
  if (newPassword.length < 6) throw apiError(400, "새 비밀번호는 6자 이상이어야 합니다.");

  const { data: user, error } = await db
    .from("planner_users")
    .select("id,password_hash")
    .eq("id", userId)
    .maybeSingle();
  if (error) throw apiError(500, error.message);
  if (!user) throw apiError(401, "로그인이 필요합니다.");

  const matches = await verifyPassword(userId, currentPassword, String(user.password_hash));
  if (!matches) throw apiError(401, "현재 비밀번호가 올바르지 않습니다.");

  await updatePasswordHash(userId, newPassword);
  return jsonResponse({ ok: true });
}

export async function requireUserId(request: Request): Promise<string> {
  const userId = await userIdFromSession(request, true);
  if (!userId) throw apiError(401, "로그인이 필요합니다.");
  return userId;
}

export async function optionalUserId(request: Request): Promise<string | null> {
  return await userIdFromSession(request, false);
}

async function readCredentials(request: Request): Promise<{ id: string; password: string }> {
  const body = await readJson(request);
  const id = normalizeIdentifier(body.id);
  const password = requiredString(body.password, "비밀번호를 입력하세요.");
  if (password.length < 6) throw apiError(400, "비밀번호는 6자 이상이어야 합니다.");
  return { id, password };
}

async function hashPassword(id: string, password: string): Promise<string> {
  return await hashPasswordWithIterations(id, password, PASSWORD_HASH_ITERATIONS);
}

async function hashPasswordWithIterations(id: string, password: string, iterations: number): Promise<string> {
  const encoder = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: encoder.encode(`planner-user:${id}`),
      iterations,
    },
    keyMaterial,
    256,
  );
  return `${PASSWORD_HASH_PREFIX}${iterations}$${toHex(bits)}`;
}

async function legacySha256HashPassword(id: string, password: string): Promise<string> {
  const bytes = new TextEncoder().encode(`${id}:${password}`);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return `${LEGACY_SHA256_HASH_PREFIX}${toHex(digest)}`;
}

function isPasswordHash(value: string): boolean {
  return value.startsWith(PASSWORD_HASH_PREFIX) || value.startsWith(LEGACY_SHA256_HASH_PREFIX);
}

async function verifyPassword(id: string, password: string, storedPasswordHash: string): Promise<boolean> {
  if (storedPasswordHash.startsWith(PASSWORD_HASH_PREFIX)) {
    const [rawIterations] = storedPasswordHash.slice(PASSWORD_HASH_PREFIX.length).split("$");
    const iterations = Number(rawIterations);
    if (!Number.isInteger(iterations) || iterations < 1) return false;
    return (await hashPasswordWithIterations(id, password, iterations)) === storedPasswordHash;
  }
  if (storedPasswordHash.startsWith(LEGACY_SHA256_HASH_PREFIX)) {
    return (await legacySha256HashPassword(id, password)) === storedPasswordHash;
  }
  if (!isPasswordHash(storedPasswordHash)) {
    return password === storedPasswordHash;
  }
  return false;
}

async function updatePasswordHash(id: string, password: string): Promise<void> {
  const { error } = await db
    .from("planner_users")
    .update({ password_hash: await hashPassword(id, password), updated_at: new Date().toISOString() })
    .eq("id", id);
  if (error) throw apiError(500, error.message);
}

async function authResponse(id: string): Promise<Response> {
  const token = createSessionToken();
  const tokenHash = await hashSessionToken(token);
  const expiresAt = new Date(Date.now() + SESSION_TTL_MS).toISOString();
  const { error } = await db.from("planner_sessions").insert({
    user_id: id,
    token_hash: tokenHash,
    expires_at: expiresAt,
  });
  if (error) throw apiError(500, error.message);
  return jsonResponse({ sessionToken: token, userId: id, expiresAt });
}

function createSessionToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return `${SESSION_TOKEN_PREFIX}${base64Url(bytes)}`;
}

async function userIdFromSession(request: Request, required: boolean): Promise<string | null> {
  const header = request.headers.get("authorization") ?? "";
  if (!header.trim()) {
    if (required) throw apiError(401, "로그인이 필요합니다.");
    return null;
  }
  const match = header.match(/^Bearer\\s+(.+)$/i);
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

function base64Url(bytes: Uint8Array): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let output = "";
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index];
    const second = bytes[index + 1];
    const third = bytes[index + 2];

    output += alphabet[first >> 2];
    output += alphabet[((first & 0x03) << 4) | ((second ?? 0) >> 4)];
    output += index + 1 < bytes.length ? alphabet[((second & 0x0f) << 2) | ((third ?? 0) >> 6)] : "=";
    output += index + 2 < bytes.length ? alphabet[(third ?? 0) & 0x3f] : "=";
  }
  return output.replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/g, "");
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function ensureProfileForAuth(userId: string): Promise<{ user_id: string; public_id: string }> {
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
"""

def ok(msg: str) -> None:
    print(f"[OK] {msg}")

def fail(msg: str) -> None:
    print(f"[FAIL] {msg}")
    sys.exit(1)

def backup(path: Path) -> None:
    bak = path.with_suffix(path.suffix + ".bak")
    if not bak.exists():
        shutil.copy2(path, bak)
        ok(f"backup created: {bak}")
    else:
        ok(f"backup exists: {bak}")

for path in [INDEX, AUTH]:
    if not path.exists():
        fail(f"missing file: {path}")

index_text = INDEX.read_text(encoding="utf-8")
auth_text = AUTH.read_text(encoding="utf-8")

if "export async function signUp" in auth_text and "async function signUp(request: Request)" not in index_text:
    ok("auth routes already extracted")
else:
    if AUTH_BLOCK_START not in index_text:
        fail("index.ts auth route block start not found")
    if AUTH_BLOCK_END not in index_text:
        fail("index.ts auth route block end not found")
    before, rest = index_text.split(AUTH_BLOCK_START, 1)
    _, after = rest.split(AUTH_BLOCK_END, 1)
    index_text = before + "async function profile(userId: string): Promise<Response> {" + after
    ok("removed auth route handler block from index.ts")

    old_import = 'import { optionalUserId, requireUserId } from "./auth.ts";'
    new_import = 'import { changePassword, login, optionalUserId, requireUserId, signUp } from "./auth.ts";'
    if old_import in index_text:
        index_text = index_text.replace(old_import, new_import, 1)
        ok("expanded auth import in index.ts")
    elif new_import in index_text:
        ok("auth import already expanded")
    else:
        fail("auth import anchor not found in index.ts")

    constants_old = """const FUNCTION_NAME = "planner-api";
const PASSWORD_HASH_PREFIX = "pbkdf2-sha256$v1$";
const LEGACY_SHA256_HASH_PREFIX = "sha256$v1$";
const PASSWORD_HASH_ITERATIONS = 120_000;
const PUBLIC_ID_PREFIX = "pb";
const SESSION_TOKEN_PREFIX = "omp_session_v1_";
const SESSION_TTL_DAYS = Number(Deno.env.get("PLANNER_SESSION_TTL_DAYS") ?? "30");
const SESSION_TTL_MS = Math.max(1, SESSION_TTL_DAYS) * 24 * 60 * 60 * 1000;
"""
    constants_new = """const FUNCTION_NAME = "planner-api";
const PUBLIC_ID_PREFIX = "pb";
"""
    if constants_old in index_text:
        index_text = index_text.replace(constants_old, constants_new, 1)
        ok("removed auth-only constants from index.ts")
    else:
        ok("auth-only constants already absent or manually changed")

    backup(INDEX)
    INDEX.write_text(index_text, encoding="utf-8")
    ok("updated index.ts")

    backup(AUTH)
    AUTH.write_text(AUTH_TS_CONTENT, encoding="utf-8")
    ok("rewrote auth.ts with route handlers")

if MODULES.exists():
    modules = MODULES.read_text(encoding="utf-8")
    original_modules = modules
    if "- `auth.ts`" not in modules:
        parser_anchor = "\n- `parser.ts`\n"
        auth_current = "\n- `auth.ts`\n  - signup/login/change-password routes\n  - session validation\n  - password hashing/session issuing helpers\n"
        if parser_anchor in modules:
            modules = modules.replace(parser_anchor, auth_current + parser_anchor, 1)
            ok("added auth.ts to MODULES.md current map")
    if "  - auth routes\n" in modules:
        modules = modules.replace("  - auth routes\n", "", 1)
        ok("removed stale auth route bullet from index.ts module map")
    if "`auth.ts` ✅ route/session extracted in P1-5d" not in modules and "`auth.ts`" in modules:
        modules = modules.replace(
            "3. `auth.ts`",
            "3. `auth.ts` ✅ route/session extracted in P1-5d",
            1,
        )
        ok("marked auth.ts target status")
    if modules != original_modules:
        backup(MODULES)
        MODULES.write_text(modules, encoding="utf-8")
        ok("updated MODULES.md")
    else:
        ok("MODULES.md unchanged")
else:
    ok("MODULES.md missing; skipped docs update")

ok("P1-5d-2 fix complete: auth routes extracted")
