from pathlib import Path
import re

ROOT = Path(".")
INDEX = ROOT / "supabase/functions/planner-api/index.ts"
DENO_JSON = ROOT / "supabase/functions/planner-api/deno.json"

def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing file: {path}")
    return path.read_text(encoding="utf-8-sig")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def patch_index_header(text: str) -> str:
    # Make Deno global types visible to Deno-aware editors/checkers.
    # This is safe for Supabase Edge Functions and does not change runtime behavior.
    ref = '/// <reference lib="deno.ns" />\n\n'
    if text.startswith('/// <reference lib="deno.ns" />'):
        return text
    return ref + text

def patch_parse_appointment_unused_param(text: str) -> str:
    # Previous Patch B authenticated the endpoint and passed userId into parseAppointment,
    # but parseAppointment does not use it. Remove the unused parameter so lint/type checks pass.
    old_route = '''    if (method === "POST" && path === "/api/parser/appointment") {
      const userId = await requireUserId(request);
      return await parseAppointment(request, userId);
    }

'''
    new_route = '''    if (method === "POST" && path === "/api/parser/appointment") {
      await requireUserId(request);
      return await parseAppointment(request);
    }

'''
    if old_route in text:
        text = text.replace(old_route, new_route, 1)

    text = text.replace(
        "async function parseAppointment(request: Request, userId: string): Promise<Response>",
        "async function parseAppointment(request: Request): Promise<Response>",
        1,
    )
    return text

def patch_optional_user_id_await(text: str) -> str:
    # Make sure optionalUserId is awaited everywhere after it became async.
    text = text.replace("user_id: optionalUserId(request),", "user_id: await optionalUserId(request),")
    return text

def patch_json_record_access(text: str) -> str:
    # Some TS checkers dislike optional property access on Record<string, unknown> without narrowing.
    old = '''  const content = first?.content as Record<string, unknown> | undefined;
  const parts = Array.isArray(content?.parts) ? content?.parts : [];
  const firstPart = parts[0] as Record<string, unknown> | undefined;
'''
    new = '''  const content = first?.content as Record<string, unknown> | undefined;
  const rawParts = content?.["parts"];
  const parts = Array.isArray(rawParts) ? rawParts : [];
  const firstPart = parts[0] as Record<string, unknown> | undefined;
'''
    if old in text:
        text = text.replace(old, new, 1)
    return text

def patch_btoa_helper(text: str) -> str:
    # Deno has btoa at runtime, but using a local base64 encoder avoids editor/runtime-lib mismatch.
    old = '''function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/g, "");
}
'''
    new = '''function base64Url(bytes: Uint8Array): string {
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
'''
    if old in text:
        text = text.replace(old, new, 1)
    return text

def ensure_deno_json() -> None:
    # Helps Deno-aware tooling/language server use the right runtime libraries.
    if DENO_JSON.exists():
        print(f"Existing deno.json found, not overwriting: {DENO_JSON}")
        return

    DENO_JSON.write_text(
        '''{
  "compilerOptions": {
    "lib": ["deno.ns", "dom", "dom.iterable", "esnext"],
    "strict": true
  },
  "lint": {
    "rules": {
      "exclude": ["no-explicit-any"]
    }
  }
}
''',
        encoding="utf-8",
    )
    print(f"Created: {DENO_JSON}")

def main() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise RuntimeError("Run this script from the repository root.")

    text = read(INDEX)
    text = patch_index_header(text)
    text = patch_parse_appointment_unused_param(text)
    text = patch_optional_user_id_await(text)
    text = patch_json_record_access(text)
    text = patch_btoa_helper(text)
    write(INDEX, text)
    print(f"Patched: {INDEX}")

    ensure_deno_json()

    print()
    print("Next commands:")
    print("  deno check supabase/functions/planner-api/index.ts")
    print("  supabase functions deploy planner-api --use-api --no-verify-jwt --project-ref gznuqhjenzeucpmonesl")
    print("  .\\gradlew.bat :app:assembleDebug")
    print()
    print("If deno is not installed locally, skip deno check and deploy with Supabase CLI.")

if __name__ == "__main__":
    main()
