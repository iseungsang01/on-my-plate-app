from pathlib import Path

ROOT = Path(".")
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SMOKE = ROOT / "run_patch_d_smoke_checks.py"

def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing file: {path}")
    return path.read_text(encoding="utf-8-sig")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def patch_manifest() -> None:
    text = read(MANIFEST)

    if 'android:fullBackupContent="false"' in text:
        print("Already patched: AndroidManifest.xml has fullBackupContent=false.")
        return

    old = '''    <application
        android:name=".OnMyPlateApp"
        android:allowBackup="false"
        android:label="@string/app_name"
'''
    new = '''    <application
        android:name=".OnMyPlateApp"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:label="@string/app_name"
'''

    if old not in text:
        raise RuntimeError("Could not find expected <application> block in AndroidManifest.xml.")

    text = text.replace(old, new, 1)
    write(MANIFEST, text)
    print(f"Patched: {MANIFEST}")

def patch_smoke_script() -> None:
    text = read(SMOKE)

    old = '''ROOT = Path(".")
PROJECT_REF = "gznuqhjenzeucpmonesl"
DEFAULT_API_BASE_URL = f"https://{PROJECT_REF}.supabase.co/functions/v1/planner-api"
'''

    new = '''ROOT = Path(".")
DEFAULT_PROJECT_REF = "gznuqhjenzeucpmonesl"
PROJECT_REF = os.environ.get("SUPABASE_PROJECT_REF", DEFAULT_PROJECT_REF)
DEFAULT_API_BASE_URL = os.environ.get(
    "PLANNER_API_BASE_URL",
    f"https://{PROJECT_REF}.supabase.co/functions/v1/planner-api",
)
'''

    if old in text:
        text = text.replace(old, new, 1)
        write(SMOKE, text)
        print(f"Patched: {SMOKE}")
        return

    if 'SUPABASE_PROJECT_REF' in text and 'DEFAULT_PROJECT_REF' in text:
        print("Already patched: smoke script supports env project ref.")
        return

    raise RuntimeError("Could not find expected PROJECT_REF block in run_patch_d_smoke_checks.py.")

def main() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise RuntimeError("Run this script from the repository root.")

    patch_manifest()
    patch_smoke_script()

    print()
    print("Done.")
    print("Next:")
    print("  .\\gradlew.bat :app:assembleDebug")
    print("  python run_patch_d_smoke_checks.py --skip-deno --skip-build --smoke-api")
    print()
    print("Optional env override examples:")
    print("  set SUPABASE_PROJECT_REF=gznuqhjenzeucpmonesl")
    print("  set PLANNER_API_BASE_URL=https://gznuqhjenzeucpmonesl.supabase.co/functions/v1/planner-api")

if __name__ == "__main__":
    main()
