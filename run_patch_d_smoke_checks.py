import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(".")
PROJECT_REF = "gznuqhjenzeucpmonesl"
DEFAULT_API_BASE_URL = f"https://{PROJECT_REF}.supabase.co/functions/v1/planner-api"

SECRET_PATTERNS = [
    r"GEMINI_API_KEY\s*=\s*[^ \n\r\"']{8,}",
    r"SUPABASE_SERVICE_ROLE_KEY\s*=\s*[^ \n\r\"']{8,}",
    r"PLANNER_SESSION_SECRET\s*=\s*[^ \n\r\"']{8,}",
    r"ANDROID_KEYSTORE_PASSWORD\s*=\s*[^ \n\r\"']{4,}",
    r"ANDROID_KEY_PASSWORD\s*=\s*[^ \n\r\"']{4,}",
    r"PLAY_SERVICE_ACCOUNT_JSON_PATH\s*=\s*[^ \n\r\"']{4,}",
    r"-----BEGIN PRIVATE KEY-----",
    r"private_key\"?\s*[:=]\s*\"?-----BEGIN",
    r"AIza[0-9A-Za-z\-_]{20,}",
    r"eyJ[a-zA-Z0-9_\-]{20,}\.[a-zA-Z0-9_\-]{20,}",
]

EMPTY_ASSIGNMENTS = {
    "GEMINI_API_KEY=",
    "SUPABASE_SERVICE_ROLE_KEY=",
    "PLANNER_SESSION_SECRET=",
    "ANDROID_KEYSTORE_PASSWORD=",
    "ANDROID_KEY_PASSWORD=",
    "PLAY_SERVICE_ACCOUNT_JSON_PATH=",
}

def run(cmd: list[str], *, check: bool = False, capture: bool = True) -> subprocess.CompletedProcess:
    print(f"\n$ {' '.join(cmd)}")
    return subprocess.run(
        cmd,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )

def pass_msg(message: str) -> None:
    print(f"[PASS] {message}")

def fail_msg(message: str) -> None:
    print(f"[FAIL] {message}")

def warn_msg(message: str) -> None:
    print(f"[WARN] {message}")

def ensure_repo_root() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise SystemExit("Run this script from the repository root.")

def check_android_gemini_buildconfig() -> bool:
    path = ROOT / "app/build.gradle.kts"
    if not path.exists():
        fail_msg("app/build.gradle.kts not found.")
        return False
    text = path.read_text(encoding="utf-8-sig")
    forbidden = ["GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_API_BASE_URL"]
    found = [item for item in forbidden if item in text]
    if found:
        fail_msg(f"Android build.gradle.kts still contains Gemini BuildConfig/env references: {', '.join(found)}")
        return False
    pass_msg("Android BuildConfig no longer contains Gemini key/model/base URL fields.")
    return True

def check_index_ts_auth_and_parser() -> bool:
    path = ROOT / "supabase/functions/planner-api/index.ts"
    if not path.exists():
        fail_msg("supabase/functions/planner-api/index.ts not found.")
        return False

    text = path.read_text(encoding="utf-8-sig")
    ok = True

    required = [
        'const SESSION_TOKEN_PREFIX = "omp_session_v1_";',
        'planner_sessions',
        'async function userIdFromSession',
        'async function parseAppointment(request: Request)',
        'GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemma-4-26b-it"',
        'path === "/api/parser/appointment"',
    ]
    for token in required:
        if token not in text:
            fail_msg(f"index.ts is missing expected hardening marker: {token}")
            ok = False

    forbidden = [
        "return jsonResponse({ sessionToken: id, userId: id });",
        "return await createUserFromLogin(credentials.id, credentials.password);",
        "async function parseAppointment(request: Request, userId: string)",
    ]
    for token in forbidden:
        if token in text:
            fail_msg(f"index.ts still contains forbidden legacy marker: {token}")
            ok = False

    if ok:
        pass_msg("Edge Function auth/session/parser hardening markers look correct.")
    return ok

def run_gradle_build() -> bool:
    cmd = ["gradlew.bat", ":app:assembleDebug"] if os.name == "nt" else ["./gradlew", ":app:assembleDebug"]
    result = run(cmd, capture=True)
    if result.returncode == 0:
        pass_msg("Android debug build succeeded.")
        return True
    fail_msg("Android debug build failed.")
    print(result.stdout[-4000:] if result.stdout else "")
    return False

def run_deno_check() -> bool | None:
    if shutil.which("deno") is None:
        warn_msg("Deno is not installed locally; skipped deno check.")
        return None
    result = run(["deno", "check", "supabase/functions/planner-api/index.ts"], capture=True)
    if result.returncode == 0:
        pass_msg("deno check passed for planner-api/index.ts.")
        return True
    fail_msg("deno check failed.")
    print(result.stdout[-4000:] if result.stdout else "")
    return False

def scan_secret_history() -> bool:
    if not (ROOT / ".git").exists():
        warn_msg(".git not found; skipped git history secret scan.")
        return True

    result = run(
        ["git", "log", "-p", "--all", "--", ".env", ".env.example", "*.json", "*.jks", "*.keystore"],
        capture=True,
    )
    if result.returncode != 0:
        warn_msg("git log scan failed; skipped secret history scan.")
        print(result.stdout or "")
        return True

    combined = re.compile("|".join(f"({p})" for p in SECRET_PATTERNS), re.IGNORECASE)
    findings = []
    current_commit = None
    for line in (result.stdout or "").splitlines():
        if line.startswith("commit "):
            current_commit = line.split(" ", 1)[1].strip()
            continue
        stripped = line.strip().lstrip("+-").strip()
        if stripped in EMPTY_ASSIGNMENTS:
            continue
        if combined.search(line):
            findings.append((current_commit, line.rstrip()[:240]))

    if findings:
        fail_msg("Potential secret exposure candidates found in targeted git history scan.")
        for i, (commit, line) in enumerate(findings[:20], start=1):
            print(f"[{i}] commit={commit} line={line}")
        if len(findings) > 20:
            print(f"... {len(findings) - 20} more findings omitted.")
        return False

    pass_msg("No obvious secret values found in targeted git history scan.")
    return True

def http_json(method: str, url: str, body: dict | None = None, token: str | None = None, timeout: int = 30) -> tuple[int, dict | str]:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            text = response.read().decode("utf-8", errors="replace")
            return response.status, json.loads(text) if text.strip().startswith(("{", "[")) else text
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(text)
        except Exception:
            parsed = text
        return error.code, parsed

def smoke_auth(api_base_url: str, username: str, password: str) -> tuple[bool, str | None]:
    token = None
    login_url = f"{api_base_url.rstrip('/')}/api/auth/login"
    signup_url = f"{api_base_url.rstrip('/')}/api/auth/signup"
    schedules_url = f"{api_base_url.rstrip('/')}/api/planner/schedules"

    missing_user = f"{username}-missing-smoke"
    status, payload = http_json("POST", login_url, {"id": missing_user, "password": password})
    if status != 401:
        fail_msg(f"Missing-user login should return 401, got {status}: {payload}")
        return False, None
    pass_msg("Missing-user login no longer auto-creates an account.")

    status, payload = http_json("POST", signup_url, {"id": username, "password": password})
    if status not in (200, 409):
        fail_msg(f"Signup returned unexpected status {status}: {payload}")
        return False, None

    if status == 409:
        warn_msg("Smoke user already exists; using login instead.")
        status, payload = http_json("POST", login_url, {"id": username, "password": password})

    if status != 200 or not isinstance(payload, dict):
        fail_msg(f"Login/signup failed: status={status}, payload={payload}")
        return False, None

    token = str(payload.get("sessionToken", ""))
    if not token.startswith("omp_session_v1_"):
        fail_msg(f"sessionToken is not opaque session token: {token[:32]}")
        return False, None
    pass_msg("Auth returns opaque omp_session_v1_ session token.")

    status, payload = http_json("GET", schedules_url, token=token)
    if status != 200:
        fail_msg(f"Schedule list with opaque token failed: status={status}, payload={payload}")
        return False, token
    pass_msg("Schedule list works with opaque session token.")

    status, payload = http_json("GET", schedules_url, token=username)
    if status != 401:
        fail_msg(f"Legacy userId bearer token should be rejected, got {status}: {payload}")
        return False, token
    pass_msg("Legacy userId bearer token is rejected.")
    return True, token

def smoke_parser(api_base_url: str, token: str | None) -> bool:
    if not token:
        warn_msg("No token available; skipped parser smoke test.")
        return True

    url = f"{api_base_url.rstrip('/')}/api/parser/appointment"
    body = {
        "rawText": "내일 오후 3시에 강남역에서 회의",
        "receivedAt": 1779000000000,
    }
    status, payload = http_json("POST", url, body=body, token=token, timeout=45)
    if status != 200 or not isinstance(payload, dict):
        fail_msg(f"Parser endpoint failed: status={status}, payload={payload}")
        return False

    if "confidence" not in payload:
        fail_msg(f"Parser endpoint response missing confidence: {payload}")
        return False

    pass_msg("Parser endpoint returned a parse response.")
    print("Parser response:", json.dumps(payload, ensure_ascii=False))
    return True

def main() -> None:
    parser = argparse.ArgumentParser(description="Run On My Plate MVP hardening smoke checks.")
    parser.add_argument("--api-base-url", default=os.environ.get("PLANNER_API_BASE_URL", DEFAULT_API_BASE_URL))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-deno", action="store_true")
    parser.add_argument("--skip-secret-scan", action="store_true")
    parser.add_argument("--smoke-api", action="store_true", help="Run live API auth/parser smoke tests.")
    parser.add_argument("--smoke-user", default=os.environ.get("OMP_SMOKE_USER", "smoke-user"))
    parser.add_argument("--smoke-password", default=os.environ.get("OMP_SMOKE_PASSWORD", "smoke-password-123"))
    args = parser.parse_args()

    ensure_repo_root()

    checks: list[bool] = []
    checks.append(check_android_gemini_buildconfig())
    checks.append(check_index_ts_auth_and_parser())

    if not args.skip_secret_scan:
        checks.append(scan_secret_history())

    if not args.skip_deno:
        deno_result = run_deno_check()
        if deno_result is not None:
            checks.append(deno_result)

    if not args.skip_build:
        checks.append(run_gradle_build())

    if args.smoke_api:
        ok, token = smoke_auth(args.api_base_url, args.smoke_user, args.smoke_password)
        checks.append(ok)
        if ok:
            checks.append(smoke_parser(args.api_base_url, token))

    print("\nSummary")
    print("=======")
    if all(checks):
        pass_msg("All required checks passed.")
        sys.exit(0)

    fail_msg("One or more checks failed.")
    sys.exit(1)

if __name__ == "__main__":
    main()
