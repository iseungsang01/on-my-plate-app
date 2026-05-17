from pathlib import Path

ROOT = Path(".")
BUILD = ROOT / "app/build.gradle.kts"
APP = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/OnMyPlateApp.kt"
PARSER = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/GeminiAppointmentParser.kt"
INDEX = ROOT / "supabase/functions/planner-api/index.ts"
ENV_EXAMPLE = ROOT / ".env.example"
README = ROOT / "README.md"

def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing file: {path}")
    return path.read_text(encoding="utf-8-sig")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Could not find expected block for {label}. File may have changed.")
    return text.replace(old, new, 1)

def patch_build_gradle() -> None:
    text = read(BUILD)

    old = '''        buildConfigField("String", "GEMINI_API_KEY", "\\"${envOrDotenv("GEMINI_API_KEY").orEmpty()}\\"")
        buildConfigField("String", "GEMINI_MODEL", "\\"${envOrDotenv("GEMINI_MODEL") ?: "gemma-4-26b-it"}\\"")
        buildConfigField(
            "String",
            "GEMINI_API_BASE_URL",
            "\\"${envOrDotenv("GEMINI_API_BASE_URL") ?: "https://generativelanguage.googleapis.com/v1beta"}\\"",
        )
'''
    if old in text:
        text = text.replace(old, "", 1)
        write(BUILD, text)
        print(f"Patched: {BUILD}")
    elif "GEMINI_API_KEY" not in text and "GEMINI_MODEL" not in text and "GEMINI_API_BASE_URL" not in text:
        print("Already patched: Gemini BuildConfig fields removed.")
    else:
        raise RuntimeError("Gemini BuildConfig block changed; inspect app/build.gradle.kts manually.")

def patch_on_my_plate_app() -> None:
    text = read(APP)

    old = '''            llmParser = GeminiAppointmentParser(
                apiKey = BuildConfig.GEMINI_API_KEY,
                model = BuildConfig.GEMINI_MODEL,
                baseUrl = BuildConfig.GEMINI_API_BASE_URL,
                diagnostics = { message, error ->
                    if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
                },
            ),
'''
    new = '''            llmParser = GeminiAppointmentParser(
                apiBaseUrl = BuildConfig.PLANNER_API_BASE_URL,
                sessionTokenProvider = {
                    getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, MODE_PRIVATE)
                        .getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)
                },
                diagnostics = { message, error ->
                    if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
                },
            ),
'''
    if old in text:
        text = text.replace(old, new, 1)
        write(APP, text)
        print(f"Patched: {APP}")
    elif "apiBaseUrl = BuildConfig.PLANNER_API_BASE_URL" in text:
        print("Already patched: OnMyPlateApp uses planner-api parser proxy.")
    else:
        raise RuntimeError("Could not patch OnMyPlateApp Gemini parser construction.")

def patch_parser() -> None:
    new_text = '''package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

class GeminiAppointmentParser(
    private val apiBaseUrl: String,
    private val sessionTokenProvider: () -> String?,
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val diagnostics: ((String, Throwable?) -> Unit)? = null,
) : AppointmentLlmParser {
    override suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult? {
        val cleanBaseUrl = apiBaseUrl.trim().trimEnd('/')
        val token = sessionTokenProvider()?.takeIf { it.isNotBlank() }
        if (cleanBaseUrl.isBlank()) {
            diagnostics?.invoke("Gemini proxy parser skipped because apiBaseUrl is blank.", null)
            return null
        }
        if (token == null) {
            diagnostics?.invoke("Gemini proxy parser skipped because session token is blank.", null)
            return null
        }

        return runCatching {
            withContext(Dispatchers.IO) {
                val responseText = post(cleanBaseUrl, token, rawText, receivedAt)
                parseProxyResponse(responseText)
            }
        }.onFailure { error ->
            diagnostics?.invoke(
                "Gemini proxy parser failed. textLength=${rawText.length}, apiBaseUrlConfigured=${cleanBaseUrl.isNotBlank()}",
                error,
            )
        }.getOrNull()
    }

    private fun post(baseUrl: String, token: String, rawText: String, receivedAt: Long): String {
        val endpoint = "$baseUrl/api/parser/appointment"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        val payload = JSONObject()
            .put("rawText", rawText)
            .put("receivedAt", receivedAt)
            .toString()

        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { input -> BufferedReader(InputStreamReader(input)).readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            error("Gemini proxy parse failed: ${connection.responseCode} $body")
        }
        return body
    }

    private fun parseProxyResponse(responseText: String): AppointmentParseResult? {
        val json = JSONObject(responseText)
        val startAt = json.optNullableLong("start_at_epoch_millis", "startAt")
        val location = json.optNullableString("location")
        val endAt = startAt?.let {
            AppointmentTitleFormatter.defaultEnd(
                it,
                json.optNullableLong("end_at_epoch_millis", "endAt"),
            )
        }
        val title = if (startAt == null) {
            ""
        } else {
            json.optNullableString("title")
                ?: AppointmentTitleFormatter.format(startAt, endAt, location, zoneId)
        }
        return AppointmentParseResult(
            title = title,
            startAt = startAt,
            endAt = endAt,
            location = location,
            confidence = json.optDouble("confidence", if (startAt != null) 0.85 else 0.5).toFloat().coerceIn(0f, 1f),
            timeConfidence = if (startAt != null) TimeConfidence.High else TimeConfidence.Low,
        )
    }

    private fun JSONObject.optNullableLong(vararg names: String): Long? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) {
                return when (val value = get(name)) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
            }
        }
        return null
    }

    private fun JSONObject.optNullableString(vararg names: String): String? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) {
                val value = optString(name).takeIf { it.isNotBlank() && it != "null" }
                if (value != null) return value
            }
        }
        return null
    }
}
'''
    current = read(PARSER)
    if "apiBaseUrl: String" in current and "/api/parser/appointment" in current:
        print("Already patched: GeminiAppointmentParser uses planner-api proxy.")
        return
    write(PARSER, new_text)
    print(f"Replaced: {PARSER}")

def patch_index_ts() -> None:
    text = read(INDEX)

    if 'const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";' not in text:
        old = '''const SESSION_TTL_MS = Math.max(1, SESSION_TTL_DAYS) * 24 * 60 * 60 * 1000;
'''
        if old in text:
            new = old + '''const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-1.5-flash";
const GEMINI_API_BASE_URL = Deno.env.get("GEMINI_API_BASE_URL") ?? "https://generativelanguage.googleapis.com/v1beta";
'''
        else:
            old = '''const PUBLIC_ID_PREFIX = "pb";
'''
            new = '''const PUBLIC_ID_PREFIX = "pb";
const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-1.5-flash";
const GEMINI_API_BASE_URL = Deno.env.get("GEMINI_API_BASE_URL") ?? "https://generativelanguage.googleapis.com/v1beta";
'''
        text = replace_once(text, old, new, "Gemini server env constants")

    route = '''    if (method === "POST" && path === "/api/parser/appointment") {
      const userId = await requireUserId(request);
      return await parseAppointment(request, userId);
    }

'''
    if route not in text:
        marker = '''    if (method === "POST" && path === "/api/auth/password") return await changePassword(request);

'''
        text = replace_once(text, marker, marker + route, "parser appointment route")

    if "async function parseAppointment(" not in text:
        marker = '''async function signUp(request: Request): Promise<Response> {
'''
        parser_funcs = r'''async function parseAppointment(request: Request, userId: string): Promise<Response> {
  if (!GEMINI_API_KEY) throw apiError(500, "Gemini API key is not configured.");
  const body = await readJson(request);
  const rawText = requiredString(body.rawText, "파싱할 텍스트가 필요합니다.");
  if (rawText.length > 5000) throw apiError(400, "공유 텍스트가 너무 깁니다.");
  const receivedAt = typeof body.receivedAt === "number" && Number.isFinite(body.receivedAt)
    ? body.receivedAt
    : Date.now();

  const prompt = appointmentPrompt(rawText, receivedAt);
  const geminiResponse = await callGemini(prompt);
  const parsed = parseGeminiAppointmentResponse(geminiResponse);
  return jsonResponse(parsed);
}

async function callGemini(prompt: string): Promise<string> {
  const endpoint = `${GEMINI_API_BASE_URL.replace(/\/+$/, "")}/models/${encodeURIComponent(GEMINI_MODEL)}:generateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0,
        responseMimeType: "application/json",
      },
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw apiError(502, `Gemini parse failed: ${response.status} ${text.slice(0, 300)}`);
  }
  return text;
}

function appointmentPrompt(rawText: string, receivedAt: number): string {
  const received = new Date(receivedAt).toISOString();
  return `
You extract one calendar appointment from Korean shared text.
Current received time is ${received}. Treat it as Asia/Seoul local context when resolving Korean relative dates.
Return only JSON with this shape:
{"title":"string|null","start_at_epoch_millis":number|null,"end_at_epoch_millis":number|null,"location":"string|null","confidence":0.0}
Rules:
- Extract exactly one appointment. If multiple appointments appear, choose the most concrete one.
- If month/day has no year, choose the next upcoming date from the received time.
- Interpret Korean afternoon, evening, dinner, and night wording as PM when appropriate.
- If a start time exists and an end time is not explicit, set end_at_epoch_millis to exactly one hour after start_at_epoch_millis.
- Do not invent location if absent.
- Title must summarize parsed date, time range, and location only. Format: M/d HHmm-HHmm location. Omit the trailing location when absent.
- If start_at_epoch_millis is null, title must be null.
- Use confidence 0.0 to 1.0 based on how explicit the appointment details are.

Text:
${rawText}
`.trim();
}

function parseGeminiAppointmentResponse(responseText: string): JsonObject {
  const root = JSON.parse(responseText) as Record<string, unknown>;
  const candidates = Array.isArray(root.candidates) ? root.candidates : [];
  const first = candidates[0] as Record<string, unknown> | undefined;
  const content = first?.content as Record<string, unknown> | undefined;
  const parts = Array.isArray(content?.parts) ? content?.parts : [];
  const firstPart = parts[0] as Record<string, unknown> | undefined;
  const raw = typeof firstPart?.text === "string" ? firstPart.text.trim() : "";
  if (!raw) throw apiError(502, "Gemini response did not contain parse JSON.");

  const cleaned = raw
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();

  const parsed = JSON.parse(cleaned) as Record<string, unknown>;
  const start = nullableNumber(parsed.start_at_epoch_millis);
  const end = nullableNumber(parsed.end_at_epoch_millis);
  const location = nullableText(parsed.location);
  const title = nullableText(parsed.title);
  const confidence = typeof parsed.confidence === "number"
    ? Math.max(0, Math.min(1, parsed.confidence))
    : start == null ? 0.5 : 0.85;

  return {
    title,
    start_at_epoch_millis: start,
    end_at_epoch_millis: end,
    location,
    confidence,
  };
}

function nullableNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return null;
}

function nullableText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== "null" ? trimmed : null;
}

'''
        text = replace_once(text, marker, parser_funcs + marker, "parser appointment functions")

    write(INDEX, text)
    print(f"Patched: {INDEX}")

def patch_env_example() -> None:
    if not ENV_EXAMPLE.exists():
        print("No .env.example found; skipped.")
        return
    text = read(ENV_EXAMPLE)
    if "GEMINI_API_KEY=" in text:
        text = text.replace("# Gemini appointment parser configuration.", "# Server-side Gemini appointment parser configuration.")
        if "Android app must not receive GEMINI_API_KEY" not in text:
            text = text.replace("GEMINI_API_KEY=", "# Android app must not receive GEMINI_API_KEY; set this as a Supabase Edge Function secret.\nGEMINI_API_KEY=", 1)
        write(ENV_EXAMPLE, text)
        print(f"Patched: {ENV_EXAMPLE}")

def patch_readme() -> None:
    if not README.exists():
        return
    text = read(README)
    if "Android app calls the planner-api parser endpoint instead of Gemini directly." in text:
        print("Already patched: README Gemini proxy note exists.")
        return
    marker = "## Supabase Edge Function planner API\n"
    note = '''## Gemini parser proxy

Android app calls the planner-api parser endpoint instead of Gemini directly. Keep `GEMINI_API_KEY`, `GEMINI_MODEL`, and `GEMINI_API_BASE_URL` as Supabase Edge Function secrets/env only; do not ship them in Android `BuildConfig`.

'''
    if marker in text:
        text = text.replace(marker, note + marker, 1)
        write(README, text)
        print(f"Patched: {README}")
    else:
        print("README marker not found; skipped README note.")

def main() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise RuntimeError("Run this script from the repository root.")

    patch_build_gradle()
    patch_on_my_plate_app()
    patch_parser()
    patch_index_ts()
    patch_env_example()
    patch_readme()

    print()
    print("Patch B applied.")
    print()
    print("Required next steps:")
    print("1. Set Supabase Edge Function secrets/env:")
    print("   supabase secrets set GEMINI_API_KEY=<your-key> GEMINI_MODEL=gemini-1.5-flash GEMINI_API_BASE_URL=https://generativelanguage.googleapis.com/v1beta --project-ref gznuqhjenzeucpmonesl")
    print("2. Deploy Edge Function:")
    print("   supabase functions deploy planner-api --use-api --no-verify-jwt --project-ref gznuqhjenzeucpmonesl")
    print("3. Rebuild Android:")
    print("   .\\gradlew.bat :app:assembleDebug")
    print("4. Verify app/build.gradle.kts no longer contains GEMINI_API_KEY BuildConfig.")
    print("5. Test shared text parsing after login.")

if __name__ == "__main__":
    main()
