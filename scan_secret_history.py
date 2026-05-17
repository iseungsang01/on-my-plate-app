import re
import subprocess
from pathlib import Path

PATTERNS = [
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

def run_git_log() -> str:
    result = subprocess.run(
        ["git", "log", "-p", "--all", "--", ".env", ".env.example", "*.json", "*.jks", "*.keystore"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git log failed")
    return result.stdout

def compact_line(line: str, max_len: int = 220) -> str:
    line = line.rstrip()
    if len(line) <= max_len:
        return line
    return line[:max_len] + " ...[truncated]"

def is_empty_assignment(line: str) -> bool:
    stripped = line.strip().lstrip("+-").strip()
    return stripped in EMPTY_ASSIGNMENTS

def main() -> None:
    if not Path(".git").exists():
        raise RuntimeError("Run this script from the repository root.")

    print("Scanning git history for secret-like patterns...")
    log_text = run_git_log()

    findings = []
    lines = log_text.splitlines()
    combined = re.compile("|".join(f"({p})" for p in PATTERNS), re.IGNORECASE)

    current_commit = None
    for line in lines:
        if line.startswith("commit "):
            current_commit = line.split(" ", 1)[1].strip()
            continue

        if is_empty_assignment(line):
            continue

        if combined.search(line):
            findings.append((current_commit, compact_line(line)))

    if not findings:
        print("No obvious secret values found in targeted git history scan.")
        print("This does not replace GitHub secret scanning, but it is good enough for MVP preflight.")
        return

    print("\nPotential secret exposure candidates found:\n")
    for i, (commit, line) in enumerate(findings, start=1):
        print(f"[{i}] commit: {commit}")
        print(f"    {line}")
        print()

    print("Action:")
    print("- If these are only variable names/placeholders, no key rotation is needed.")
    print("- If actual keys/passwords/private_key values appear, rotate that credential immediately.")

if __name__ == "__main__":
    main()
