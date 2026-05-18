from __future__ import annotations

import os
import platform
import subprocess
from pathlib import Path


REQUIRED_SIGNING_VARS = [
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
]
REQUIRED_PLAY_VARS = [
    "PLAY_SERVICE_ACCOUNT_JSON_PATH",
]


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def load_dotenv(dotenv_path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not dotenv_path.is_file():
        return values

    for raw_line in dotenv_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and value:
            values[key] = value
    return values


def env_value(name: str, dotenv: dict[str, str]) -> str | None:
    value = os.environ.get(name)
    if value and value.strip():
        return value.strip()
    value = dotenv.get(name)
    return value.strip() if value and value.strip() else None


def main() -> int:
    root = repo_root()
    dotenv = load_dotenv(root / ".env")

    version_name = env_value("ANDROID_VERSION_NAME", dotenv)
    version_code = env_value("ANDROID_VERSION_CODE", dotenv)
    track = env_value("PLAY_TRACK", dotenv) or "internal"
    release_status = env_value("PLAY_RELEASE_STATUS", dotenv) or "DRAFT"

    missing_signing = [name for name in REQUIRED_SIGNING_VARS if not env_value(name, dotenv)]
    missing_play = [name for name in REQUIRED_PLAY_VARS if not env_value(name, dotenv)]
    missing = missing_signing + missing_play

    print(f"Version: {version_name} ({version_code})")
    print(f"Play track: {track}")
    print(f"Release status: {release_status}")

    if missing:
        print("Missing required release values:")
        for name in missing:
            print(f"  - {name}")
        return 2

    is_windows = platform.system().lower().startswith("win")
    gradlew = root / "gradlew.bat" if is_windows else root / "gradlew"
    gradle_cmd = [str(gradlew), ":app:publishAab", "--no-daemon"]
    run_cmd = ["cmd", "/c", *gradle_cmd] if is_windows else gradle_cmd

    print("Running:", " ".join(gradle_cmd))
    completed = subprocess.run(run_cmd, cwd=root)
    if completed.returncode == 0:
        aab_path = root / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab"
        print(f"AAB: {aab_path}")
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
