from pathlib import Path
import re

ROOT = Path(".")
REPO = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt"
WEEKLY = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/ui/WeeklyScheduleScreen.kt"
CANDIDATE = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/ui/CandidateEditScreen.kt"
SCHEDULE_EDIT = ROOT / "app/src/main/java/com/lss/onmyplate/nativeplanner/ui/ScheduleEditScreen.kt"

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

def user_error_expr(var_name: str = "error") -> str:
    return f'userFacingError({var_name})'

def patch_repository() -> None:
    text = read(REPO)

    if "import kotlinx.coroutines.flow.StateFlow" not in text:
        text = replace_once(
            text,
            "import kotlinx.coroutines.flow.MutableStateFlow\n",
            "import kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow\n",
            "PlannerRepository StateFlow import",
        )

    if "data class PlannerRuntimeState" not in text:
        anchor = '''private data class RecentCandidateCreate(
    val rawText: String,
    val sourceApp: String?,
    val savedAt: Long,
    val candidate: AppointmentCandidateEntity,
)

'''
        insertion = anchor + '''data class PlannerRuntimeState(
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

'''
        text = replace_once(text, anchor, insertion, "PlannerRuntimeState data class")

    if "val runtimeState: StateFlow<PlannerRuntimeState>" not in text:
        anchor = '''    private val createCandidateMutex = Mutex()
    private val saveCandidateMutex = Mutex()
    private var recentCandidateCreate: RecentCandidateCreate? = null
'''
        insertion = '''    private val createCandidateMutex = Mutex()
    private val saveCandidateMutex = Mutex()
    private val _runtimeState = MutableStateFlow(PlannerRuntimeState())
    val runtimeState: StateFlow<PlannerRuntimeState> = _runtimeState
    private var recentCandidateCreate: RecentCandidateCreate? = null
'''
        text = replace_once(text, anchor, insertion, "PlannerRepository runtimeState field")

    if "fun clearRuntimeError()" not in text:
        anchor = '''    suspend fun getCandidate(id: String): AppointmentCandidateEntity? =
        refreshCandidate(id) ?: candidateRecords.value[id] ?: pendingCandidates.value.firstOrNull { it.id == id }

'''
        insertion = anchor + '''    fun clearRuntimeError() {
        _runtimeState.value = _runtimeState.value.copy(errorMessage = null)
    }

'''
        text = replace_once(text, anchor, insertion, "PlannerRepository clearRuntimeError")

    # Record createCandidate API failure.
    old = '''        } catch (error: Throwable) {
            Log.e(TAG, "createCandidate API save failed. ${localCandidate.diagnosticSummary()}", error)
            throw error
        }
'''
    new = '''        } catch (error: Throwable) {
            Log.e(TAG, "createCandidate API save failed. ${localCandidate.diagnosticSummary()}", error)
            recordRuntimeError(error)
            throw error
        }
'''
    if old in text:
        text = text.replace(old, new, 1)

    # Patch refreshSchedules.
    old = '''    suspend fun refreshSchedules(rangeStart: Long? = null, rangeEnd: Long? = null): List<ScheduleRecord> {
        val records = withContext(Dispatchers.IO) { client.listSchedules(sessionToken(), rangeStart, rangeEnd) }
        scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, records)
        return records
    }
'''
    new = '''    suspend fun refreshSchedules(rangeStart: Long? = null, rangeEnd: Long? = null): List<ScheduleRecord> {
        beginRuntimeLoading()
        return try {
            val records = withContext(Dispatchers.IO) { client.listSchedules(sessionToken(), rangeStart, rangeEnd) }
            scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, records)
            clearRuntimeLoading()
            records
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }
'''
    if old in text:
        text = text.replace(old, new, 1)

    # Patch refreshSchedule.
    old = '''    private suspend fun refreshSchedule(id: String): ScheduleRecord? {
        val record = withContext(Dispatchers.IO) { client.getSchedule(sessionToken(), id) }
        if (record != null) scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, listOf(record))
        return record
    }
'''
    new = '''    private suspend fun refreshSchedule(id: String): ScheduleRecord? {
        beginRuntimeLoading()
        return try {
            val record = withContext(Dispatchers.IO) { client.getSchedule(sessionToken(), id) }
            if (record != null) scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, listOf(record))
            clearRuntimeLoading()
            record
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }
'''
    if old in text:
        text = text.replace(old, new, 1)

    # Patch refreshPendingCandidates.
    old = '''    private suspend fun refreshPendingCandidates(): List<AppointmentCandidateEntity> {
        val candidates = withContext(Dispatchers.IO) { client.listPendingCandidates(sessionToken()) }
        pendingCandidates.value = candidates
        candidateRecords.value = candidateRecords.value + candidates.associateBy { it.id }
        return candidates
    }
'''
    new = '''    private suspend fun refreshPendingCandidates(): List<AppointmentCandidateEntity> {
        beginRuntimeLoading()
        return try {
            val candidates = withContext(Dispatchers.IO) { client.listPendingCandidates(sessionToken()) }
            pendingCandidates.value = candidates
            candidateRecords.value = candidateRecords.value + candidates.associateBy { it.id }
            clearRuntimeLoading()
            candidates
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }
'''
    if old in text:
        text = text.replace(old, new, 1)

    # Patch refreshCandidate.
    old = '''    private suspend fun refreshCandidate(id: String): AppointmentCandidateEntity? {
        val candidate = withContext(Dispatchers.IO) { client.getCandidate(sessionToken(), id) }
        if (candidate != null) rememberCandidate(candidate)
        return candidate
    }
'''
    new = '''    private suspend fun refreshCandidate(id: String): AppointmentCandidateEntity? {
        beginRuntimeLoading()
        return try {
            val candidate = withContext(Dispatchers.IO) { client.getCandidate(sessionToken(), id) }
            if (candidate != null) rememberCandidate(candidate)
            clearRuntimeLoading()
            candidate
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }
'''
    if old in text:
        text = text.replace(old, new, 1)

    if "private fun beginRuntimeLoading()" not in text:
        anchor = '''    private fun rememberCandidate(candidate: AppointmentCandidateEntity) {
        candidateRecords.value = candidateRecords.value + (candidate.id to candidate)
    }

'''
        insertion = anchor + '''    private fun beginRuntimeLoading() {
        _runtimeState.value = _runtimeState.value.copy(loading = true)
    }

    private fun clearRuntimeLoading() {
        _runtimeState.value = PlannerRuntimeState()
    }

    private fun recordRuntimeError(error: Throwable) {
        _runtimeState.value = PlannerRuntimeState(
            loading = false,
            errorMessage = userFacingError(error),
        )
    }

    private fun userFacingError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Login is required", ignoreCase = true) ||
                raw.contains("로그인", ignoreCase = true) ||
                raw.contains("401") -> "로그인이 만료되었습니다. 다시 로그인해 주세요."
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) -> "요청 시간이 초과되었습니다. 네트워크 상태를 확인해 주세요."
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("Connection", ignoreCase = true) -> "서버에 연결할 수 없습니다. 네트워크를 확인해 주세요."
            raw.isNotBlank() -> raw.take(180)
            else -> "요청 처리 중 오류가 발생했습니다."
        }
    }

'''
        text = replace_once(text, anchor, insertion, "PlannerRepository runtime helpers")

    write(REPO, text)
    print(f"Patched: {REPO}")

def patch_weekly_screen() -> None:
    text = read(WEEKLY)

    if "val runtimeState by repository.runtimeState.collectAsState()" not in text:
        old = '''    val schedules by repository.observeExpandedSchedules(rangeStart, rangeEnd).collectAsState(initial = emptyList())
    val schedulesByDay = remember(schedules, days) {
'''
        new = '''    val schedules by repository.observeExpandedSchedules(rangeStart, rangeEnd).collectAsState(initial = emptyList())
    val runtimeState by repository.runtimeState.collectAsState()
    val schedulesByDay = remember(schedules, days) {
'''
        text = replace_once(text, old, new, "WeeklyScheduleScreen runtimeState collection")

    if "runtimeState.errorMessage?.let" not in text:
        old = '''        Column(Modifier.fillMaxSize().padding(12.dp)) {
            WeeklyTimetableWidget(
'''
        new = '''        Column(Modifier.fillMaxSize().padding(12.dp)) {
            runtimeState.errorMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = FeedLoopColors.ErrorBg),
                    border = BorderStroke(1.dp, FeedLoopColors.Error.copy(alpha = 0.35f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "일정 동기화 실패: $message",
                            color = FeedLoopColors.Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { repository.clearRuntimeError() }) { Text("닫기") }
                    }
                }
            }
            if (runtimeState.loading && schedules.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            WeeklyTimetableWidget(
'''
        text = replace_once(text, old, new, "WeeklyScheduleScreen runtimeState banner")

    write(WEEKLY, text)
    print(f"Patched: {WEEKLY}")

def patch_candidate_error_message() -> None:
    if not CANDIDATE.exists():
        print(f"Skipped missing file: {CANDIDATE}")
        return
    text = read(CANDIDATE)
    old = '''                        }.onFailure {
                            actionError = "Unable to save. Check the network and try again."
                            actionInFlight = false
                        }
'''
    new = '''                        }.onFailure { error ->
                            actionError = error.message?.takeIf { it.isNotBlank() }
                                ?: "저장에 실패했습니다. 네트워크와 로그인 상태를 확인해 주세요."
                            actionInFlight = false
                        }
'''
    if old in text:
        text = text.replace(old, new, 1)
        # there may be two occurrences; patch the second too.
        text = text.replace(old, new, 1)
        write(CANDIDATE, text)
        print(f"Patched: {CANDIDATE}")
    else:
        print("CandidateEditScreen generic error block not found or already patched.")

def patch_schedule_edit_error_message() -> None:
    if not SCHEDULE_EDIT.exists():
        print(f"Skipped missing file: {SCHEDULE_EDIT}")
        return
    text = read(SCHEDULE_EDIT)
    changed = False

    patterns = [
        (
            '''}.onFailure {
                            actionError = "Unable to save. Check the network and try again."
                            actionInFlight = false
                        }''',
            '''}.onFailure { error ->
                            actionError = error.message?.takeIf { it.isNotBlank() }
                                ?: "저장에 실패했습니다. 네트워크와 로그인 상태를 확인해 주세요."
                            actionInFlight = false
                        }''',
        ),
        (
            '''}.onFailure {
                            actionError = "Unable to delete. Check the network and try again."
                            actionInFlight = false
                        }''',
            '''}.onFailure { error ->
                            actionError = error.message?.takeIf { it.isNotBlank() }
                                ?: "삭제에 실패했습니다. 네트워크와 로그인 상태를 확인해 주세요."
                            actionInFlight = false
                        }''',
        ),
    ]

    for old, new in patterns:
        if old in text:
            text = text.replace(old, new)
            changed = True

    if changed:
        write(SCHEDULE_EDIT, text)
        print(f"Patched: {SCHEDULE_EDIT}")
    else:
        print("ScheduleEditScreen known generic error blocks not found or already patched.")

def find_basket_screen() -> Path | None:
    src = ROOT / "app/src/main/java"
    if not src.exists():
        return None
    for path in src.rglob("*.kt"):
        try:
            text = read(path)
        except UnicodeDecodeError:
            continue
        if "fun BasketScreen(" in text:
            return path
    return None

def patch_basket_screen() -> None:
    path = find_basket_screen()
    if path is None:
        print("BasketScreen file not found; skipped.")
        return

    text = read(path)

    if "val runtimeState by repository.runtimeState.collectAsState()" not in text:
        # Try to insert after pending candidates collection.
        text = re.sub(
            r'(val\s+\w+\s+by\s+repository\.observePendingCandidates\(\)\.collectAsState\(initial\s*=\s*emptyList\(\)\)\s*)',
            r'\1\n    val runtimeState by repository.runtimeState.collectAsState()\n',
            text,
            count=1,
        )

    if "바구니 동기화 실패" not in text and "runtimeState.errorMessage" in text:
        # Insert after the first Column opening that likely contains screen content.
        text = text.replace(
            "    Column(",
            '''    Column(
''',
            1,
        )
        # This heuristic may be too risky; avoid deeper UI surgery if exact structure unknown.
        print(f"BasketScreen runtimeState collection patched if matching pattern existed: {path}")
    else:
        print(f"BasketScreen skipped UI banner; inspect manually if needed: {path}")

    write(path, text)

def main() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise RuntimeError("Run this script from the repository root.")

    patch_repository()
    patch_weekly_screen()
    patch_candidate_error_message()
    patch_schedule_edit_error_message()
    patch_basket_screen()

    print()
    print("Patch C applied.")
    print()
    print("Next:")
    print("  .\\gradlew.bat :app:assembleDebug")
    print()
    print("Expected behavior:")
    print("- Schedule screen shows a loading bar during first empty load.")
    print("- Schedule screen shows an explicit error banner instead of silently showing an empty timetable.")
    print("- Candidate/Schedule edit failures display the server/network error message when available.")
    print("- Repository exposes runtimeState for future Basket/Sharing screen banners.")

if __name__ == "__main__":
    main()
