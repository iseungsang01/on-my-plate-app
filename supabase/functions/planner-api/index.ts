import { createClient } from "npm:@supabase/supabase-js@2";

type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const FUNCTION_NAME = "planner-api";
const PASSWORD_HASH_PREFIX = "pbkdf2-sha256$v1$";
const LEGACY_SHA256_HASH_PREFIX = "sha256$v1$";
const PASSWORD_HASH_ITERATIONS = 120_000;
const PUBLIC_ID_PREFIX = "pb";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const db = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      return errorResponse(500, "Supabase Edge Function 환경 변수가 설정되지 않았습니다.");
    }

    const url = new URL(request.url);
    const path = normalizePath(url.pathname);
    const method = request.method.toUpperCase();

    if (method === "POST" && path === "/api/auth/signup") return await signUp(request);
    if (method === "POST" && path === "/api/auth/login") return await login(request);
    if (method === "POST" && path === "/api/auth/password") return await changePassword(request);

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

    if (method === "POST" && path === "/api/planner/schedules") {
      const userId = await requireUserId(request);
      return await uploadPersonalSchedule(userId, request);
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
  const stripped = index >= 0 ? pathname.slice(index + marker.length) : pathname;
  return stripped.startsWith("/") ? stripped : `/${stripped}`;
}

async function signUp(request: Request): Promise<Response> {
  const credentials = await readCredentials(request);
  const passwordHash = await hashPassword(credentials.id, credentials.password);
  const { error } = await db.from("planner_users").insert({
    id: credentials.id,
    password_hash: passwordHash,
  });
  if (error?.code === "23505") throw apiError(409, "이미 사용 중인 아이디입니다.");
  if (error) throw apiError(500, error.message);

  await ensureProfile(credentials.id);
  return authResponse(credentials.id);
}

async function login(request: Request): Promise<Response> {
  const credentials = await readCredentials(request);
  const { data: user, error } = await db
    .from("planner_users")
    .select("id,password_hash")
    .eq("id", credentials.id)
    .maybeSingle();
  if (error) throw apiError(500, error.message);

  if (!user) {
    return await createUserFromLogin(credentials.id, credentials.password);
  }

  const matches = await verifyPassword(credentials.id, credentials.password, String(user.password_hash));
  if (!matches) {
    throw apiError(401, "아이디 또는 비밀번호가 올바르지 않습니다.");
  }

  if (!isPasswordHash(String(user.password_hash))) {
    await updatePasswordHash(credentials.id, credentials.password);
  }

  await ensureProfile(credentials.id);
  return authResponse(credentials.id);
}

async function changePassword(request: Request): Promise<Response> {
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

async function createUserFromLogin(id: string, password: string): Promise<Response> {
  const passwordHash = await hashPassword(id, password);
  const { error } = await db.from("planner_users").insert({ id, password_hash: passwordHash });
  if (error?.code === "23505") {
    const retryRequest = new Request("http://local/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ id, password }),
    });
    return await login(retryRequest);
  }
  if (error) throw apiError(500, error.message);

  await ensureProfile(id);
  return authResponse(id);
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

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function authResponse(id: string): Response {
  return jsonResponse({ sessionToken: id, userId: id });
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
  const schedule = await readScheduleJson(request);
  const { data, error } = await db
    .from("planner_schedules")
    .upsert(
      { ...schedule, group_id: groupId, created_by: userId },
      { onConflict: "group_id,created_by,local_schedule_id" },
    )
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ schedule: toScheduleJson(data) });
}

async function uploadPersonalSchedule(userId: string, request: Request): Promise<Response> {
  const schedule = await readScheduleJson(request);
  const { data, error } = await db
    .from("planner_personal_schedules")
    .upsert(
      { ...schedule, created_by: userId },
      { onConflict: "created_by,local_schedule_id" },
    )
    .select()
    .single();
  if (error) throw apiError(500, error.message);
  return jsonResponse({ schedule: toScheduleJson(data) });
}

async function listSharedSchedules(userId: string, groupId: string, includeDummy: boolean): Promise<Response> {
  await requireGroupMember(userId, groupId);
  const { data: schedules, error } = await db
    .from("planner_schedules")
    .select("id,title,start_at,end_at,location,status")
    .eq("group_id", groupId)
    .order("start_at", { ascending: true });
  if (error) throw apiError(500, error.message);

  let all = (schedules ?? []).map((item) => toScheduleJson({ ...item, is_dummy: false }));
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

async function requireUserId(request: Request): Promise<string> {
  const header = request.headers.get("authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) throw apiError(401, "로그인이 필요합니다.");
  return normalizeIdentifier(match[1].trim());
}

async function readScheduleJson(request: Request): Promise<JsonObject> {
  const body = await readJson(request);
  return {
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
  };
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (!text.trim()) return {};
  try {
    const value = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("invalid");
    return value;
  } catch {
    throw apiError(400, "JSON 형식이 올바르지 않습니다.");
  }
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

function toGroupJson(group: { id: string; name: string }): JsonObject {
  return { groupId: group.id, id: group.id, name: group.name };
}

function toScheduleJson(schedule: Record<string, unknown>): JsonObject {
  return {
    id: String(schedule.id),
    title: String(schedule.title),
    startAt: String(schedule.start_at),
    endAt: optionalString(schedule.end_at),
    location: optionalString(schedule.location),
    status: optionalString(schedule.status) ?? "planned",
    isDummy: Boolean(schedule.is_dummy),
  };
}

function jsonResponse(body: JsonObject, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function errorResponse(status: number, message: string): Response {
  return jsonResponse({ message, error: message }, status);
}

function apiError(status: number, message: string): Error & { status: number } {
  const error = new Error(message) as Error & { status: number };
  error.status = status;
  return error;
}

function toApiError(error: unknown): { status: number; message: string } {
  if (error instanceof Error && "status" in error && typeof error.status === "number") {
    return { status: error.status, message: error.message };
  }
  return { status: 500, message: error instanceof Error ? error.message : "약속 바구니 API 요청이 실패했습니다." };
}
