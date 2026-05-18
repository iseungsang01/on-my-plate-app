type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];
type JsonObject = { [key: string]: JsonValue };

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemma-4-26b-it";
const GEMINI_API_BASE_URL = Deno.env.get("GEMINI_API_BASE_URL") ?? "https://generativelanguage.googleapis.com/v1beta";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
};

export async function parseAppointment(request: Request): Promise<Response> {
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
  const rawParts = content?.["parts"];
  const parts = Array.isArray(rawParts) ? rawParts : [];
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

function requiredString(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw apiError(400, message);
  return value;
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

function jsonResponse(body: JsonObject, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function apiError(status: number, message: string): Error & { status: number } {
  const error = new Error(message) as Error & { status: number };
  error.status = status;
  return error;
}
