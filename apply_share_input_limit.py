from pathlib import Path

TARGET = Path("app/src/main/java/com/lss/onmyplate/nativeplanner/share/ShareReceiverActivity.kt")

OLD_BLOCK = '''        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" || sharedText.isNullOrBlank()) {
            Log.w(TAG, "Ignoring unsupported share intent. action=${intent.action}, type=${intent.type}, hasText=${!sharedText.isNullOrBlank()}")
            finish()
            return
        }

        pendingSharedText = sharedText
'''

NEW_BLOCK = '''        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" || sharedText.isNullOrBlank()) {
            Log.w(TAG, "Ignoring unsupported share intent. action=${intent.action}, type=${intent.type}, hasText=${!sharedText.isNullOrBlank()}")
            finish()
            return
        }

        if (sharedText.length > MAX_SHARED_TEXT_LENGTH) {
            Log.w(TAG, "Ignoring oversized shared text. textLength=${sharedText.length}")
            Toast.makeText(this, "공유 텍스트가 너무 깁니다. 약속이 포함된 부분만 다시 공유해 주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        pendingSharedText = sharedText
'''

OLD_COMPANION = '''    companion object {
        private const val TAG = "ShareReceiverActivity"
        private const val REQUEST_NOTIFICATIONS = 1001
    }
'''

NEW_COMPANION = '''    companion object {
        private const val TAG = "ShareReceiverActivity"
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val MAX_SHARED_TEXT_LENGTH = 5_000
    }
'''

def main() -> None:
    if not TARGET.exists():
        raise FileNotFoundError(f"Target file not found: {TARGET}")

    text = TARGET.read_text(encoding="utf-8-sig")

    if "MAX_SHARED_TEXT_LENGTH" in text:
        print("Already patched: MAX_SHARED_TEXT_LENGTH exists.")
        return

    if OLD_BLOCK not in text:
        raise RuntimeError("Could not find the expected sharedText block. File may have changed.")

    if OLD_COMPANION not in text:
        raise RuntimeError("Could not find the expected companion object block. File may have changed.")

    text = text.replace(OLD_BLOCK, NEW_BLOCK)
    text = text.replace(OLD_COMPANION, NEW_COMPANION)

    TARGET.write_text(text, encoding="utf-8")
    print(f"Patched successfully: {TARGET}")
    print("Next: run ./gradlew :app:assembleDebug or .\\gradlew.bat :app:assembleDebug")

if __name__ == "__main__":
    main()
