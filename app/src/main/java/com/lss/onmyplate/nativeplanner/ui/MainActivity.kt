package com.lss.onmyplate.nativeplanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.R
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.widget.PlannerWidgetSync
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val appUpdateManager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val appUpdateLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // The user canceled or Play could not start the update. Check again on the next foreground entry.
        }
    }
    private var routeState = mutableStateOf<Route>(Route.Schedule)
    private var pendingAfterLogin: Route = Route.Schedule
    private var pendingExternalShare: PendingExternalShare? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExternalShare = externalShareFrom(intent)
        pendingAfterLogin = startRoute(intent)
        routeState.value = if (hasAppAccess()) pendingAfterLogin else Route.Login
        setContent {
            MaterialTheme(colorScheme = FeedLoopColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val route by routeState
                    AppRoot(
                        route = route,
                        onRoute = { routeState.value = it },
                        onAuthenticated = { onAuthenticated() },
                    )
                }
            }
        }
        if (hasAppAccess()) processPendingExternalShare()
        maybeRequestNotifications()
        checkForAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        resumeAppUpdateIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingExternalShare = externalShareFrom(intent)
        pendingAfterLogin = startRoute(intent)
        routeState.value = if (hasAppAccess()) pendingAfterLogin else Route.Login
        if (hasAppAccess()) processPendingExternalShare()
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun checkForAppUpdate() {
        if (BuildConfig.DEBUG) return
        runCatching {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                val isUpdateAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val canStartImmediateUpdate = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (isUpdateAvailable && canStartImmediateUpdate) {
                    runCatching {
                        appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            appUpdateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                        )
                    }
                }
            }
        }
    }

    private fun resumeAppUpdateIfNeeded() {
        if (BuildConfig.DEBUG) return
        runCatching {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    runCatching {
                        appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            appUpdateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                        )
                    }
                }
            }
        }
    }

    private fun hasAppAccess(): Boolean = (applicationContext as OnMyPlateApp).authRepository.hasAppAccess()

    private fun onAuthenticated() {
        routeState.value = pendingAfterLogin
        processPendingExternalShare()
    }

    private fun processPendingExternalShare() {
        val share = pendingExternalShare ?: return
        if (!hasAppAccess()) return
        pendingExternalShare = null
        routeState.value = Route.Basket
        lifecycleScope.launch {
            val app = applicationContext as OnMyPlateApp
            try {
                val candidate = app.repository.createCandidate(share.text, share.sourceApp, share.receivedAt)
                val notificationShown = app.notifications.showCandidate(candidate)
                Toast.makeText(
                    this@MainActivity,
                    if (notificationShown) "\uc54c\ub9bc\uc5d0\uc11c \uc81c\ubaa9\uc744 \uc785\ub825\ud558\uace0 \ud655\uc815\ud574 \uc8fc\uc138\uc694." else "\uc54c\ub9bc\uc744 \ud45c\uc2dc\ud560 \uc218 \uc5c6\uc5b4 \uc571\uc5d0\uc11c \ub514\ud14c\uc77c\uc744 \uc124\uc815\ud574 \uc8fc\uc138\uc694.",
                    Toast.LENGTH_LONG,
                ).show()
                if (!notificationShown) routeState.value = Route.Candidate(candidate.id)
            } catch (error: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "\uc77c\uc815 \ub514\ud14c\uc77c \uc124\uc815\uc744 \ub9cc\ub4e4\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \ub85c\uadf8\uc778 \ub610\ub294 \ub124\ud2b8\uc6cc\ud06c\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.",
                    Toast.LENGTH_LONG,
                ).show()
                routeState.value = if (hasAppAccess()) Route.Basket else Route.Login
            }
        }
    }

    private fun startRoute(intent: Intent): Route {
        val candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID)
        return when {
            candidateId != null && intent.getBooleanExtra(EXTRA_CONFLICT, false) -> Route.Conflict(candidateId)
            candidateId != null -> Route.Candidate(candidateId)
            intent.getStringExtra(EXTRA_ROUTE) == ROUTE_BASKET -> Route.Basket
            else -> Route.Schedule
        }
    }

    private fun externalShareFrom(intent: Intent): PendingExternalShare? {
        val text = intent.getStringExtra(EXTRA_SHARED_TEXT)
            ?: if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val cleanText = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return PendingExternalShare(
            text = cleanText,
            sourceApp = intent.getStringExtra(EXTRA_SHARED_SOURCE_APP),
            receivedAt = intent.getLongExtra(EXTRA_SHARED_RECEIVED_AT, System.currentTimeMillis()),
        )
    }

    companion object {
        private const val EXTRA_CANDIDATE_ID = "candidate_id"
        private const val EXTRA_CONFLICT = "conflict"
        private const val EXTRA_ROUTE = "route"
        private const val ROUTE_BASKET = "basket"
        private const val EXTRA_SHARED_TEXT = "shared_text"
        private const val EXTRA_SHARED_SOURCE_APP = "shared_source_app"
        private const val EXTRA_SHARED_RECEIVED_AT = "shared_received_at"

        fun candidateIntent(context: Context, candidateId: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CANDIDATE_ID, candidateId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        fun basketIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ROUTE_BASKET)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        fun sharedTextIntent(context: Context, text: String, sourceApp: String?, receivedAt: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ROUTE_BASKET)
                putExtra(EXTRA_SHARED_TEXT, text)
                putExtra(EXTRA_SHARED_SOURCE_APP, sourceApp)
                putExtra(EXTRA_SHARED_RECEIVED_AT, receivedAt)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        fun conflictIntent(context: Context, candidateId: String): Intent =
            candidateIntent(context, candidateId).apply { putExtra(EXTRA_CONFLICT, true) }
    }
}

private data class PendingExternalShare(
    val text: String,
    val sourceApp: String?,
    val receivedAt: Long,
)

sealed interface Route {
    data object Schedule : Route
    data object Basket : Route
    data object Sharing : Route
    data object AvailabilityGroups : Route
    data object AvailabilityGroupCreate : Route
    data object AvailabilityGroupJoin : Route
    data class AvailabilityGroupDetail(val groupId: String) : Route
    data class AvailabilityProposalDetail(val groupId: String, val proposalId: String) : Route
    data object Settings : Route
    data object Login : Route
    data class ScheduleEdit(
        val scheduleId: String,
        val occurrenceStartAt: Long? = null,
        val returnRoute: Route = Schedule,
    ) : Route
    data class Candidate(val candidateId: String) : Route
    data class Conflict(val candidateId: String) : Route
    data class Complete(val candidateId: String) : Route
}

private enum class MainTab(val label: String, val route: Route, val imageRes: Int, val badgeText: String? = null) {
    Schedule("일정", Route.Schedule, R.drawable.mascot_note),
    Basket("바구니", Route.Basket, R.drawable.mascot_basket),
    Sharing("공유", Route.Sharing, R.drawable.mascot_plane),
    Settings("설정", Route.Settings, R.drawable.mascot_settings),
}

@Composable
private fun AppRoot(route: Route, onRoute: (Route) -> Unit, onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OnMyPlateApp
    var showQuickAdd by remember { mutableStateOf(false) }
    val openQuickAdd = { showQuickAdd = true }
    Box(Modifier.fillMaxSize()) {
        when (route) {
        Route.Login -> LoginScreen(authRepository = app.authRepository, onAuthenticated = onAuthenticated)
        Route.Schedule -> MascotScaffold(selected = MainTab.Schedule, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            WeeklyScheduleScreen(
                repository = app.repository,
                onOpenSchedule = { scheduleId, occurrenceStartAt ->
                    onRoute(Route.ScheduleEdit(scheduleId, occurrenceStartAt, returnRoute = Route.Schedule))
                },
            )
        }
        Route.Basket -> MascotScaffold(selected = MainTab.Basket, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            BasketScreen(
                repository = app.repository,
                onOpenCandidate = { onRoute(Route.Candidate(it)) },
            )
        }
        Route.Sharing -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            SharingScreen(
                plannerRepository = app.repository,
                sharingRepository = app.sharingRepository,
                onOpenAvailabilityGroups = { onRoute(Route.AvailabilityGroups) },
            )
        }
        Route.AvailabilityGroups -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            AvailabilityGroupListScreen(
                repository = app.availabilityGroupRepository,
                onOpenGroup = { onRoute(Route.AvailabilityGroupDetail(it)) },
                onCreate = { onRoute(Route.AvailabilityGroupCreate) },
                onJoin = { onRoute(Route.AvailabilityGroupJoin) },
                onBack = { onRoute(Route.Sharing) },
            )
        }
        Route.AvailabilityGroupCreate -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            AvailabilityGroupCreateScreen(
                repository = app.availabilityGroupRepository,
                onCreated = { onRoute(Route.AvailabilityGroupDetail(it)) },
                onBack = { onRoute(Route.AvailabilityGroups) },
            )
        }
        Route.AvailabilityGroupJoin -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            AvailabilityGroupJoinScreen(
                repository = app.availabilityGroupRepository,
                onJoined = { onRoute(Route.AvailabilityGroupDetail(it)) },
                onBack = { onRoute(Route.AvailabilityGroups) },
            )
        }
        is Route.AvailabilityGroupDetail -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            AvailabilityGroupDetailScreen(
                repository = app.availabilityGroupRepository,
                groupId = route.groupId,
                onOpenProposal = { proposalId -> onRoute(Route.AvailabilityProposalDetail(route.groupId, proposalId)) },
                onBack = { onRoute(Route.AvailabilityGroups) },
            )
        }
        is Route.AvailabilityProposalDetail -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            AvailabilityProposalDetailScreen(
                repository = app.availabilityGroupRepository,
                groupId = route.groupId,
                proposalId = route.proposalId,
                onBack = { onRoute(Route.AvailabilityGroupDetail(route.groupId)) },
            )
        }
        Route.Settings -> MascotScaffold(selected = MainTab.Settings, onRoute = onRoute, onQuickAdd = openQuickAdd) {
            SettingsScreen(
                authRepository = app.authRepository,
                feedbackRepository = app.feedbackRepository,
                sharingRepository = app.sharingRepository,
                onLoggedOut = {
                    app.authRepository.clearAccess()
                    app.sharingRepository.clearAccountCache()
                    PlannerWidgetSync.clearSnapshot(app)
                    onRoute(Route.Login)
                },
            )
        }
        is Route.ScheduleEdit -> ScheduleEditScreen(
            repository = app.repository,
            scheduleId = route.scheduleId,
            occurrenceStartAt = route.occurrenceStartAt,
            onBack = { onRoute(route.returnRoute) },
        )
        is Route.Candidate -> CandidateEditScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onDone = { onRoute(Route.Schedule) },
            onConflict = { onRoute(Route.Conflict(route.candidateId)) },
            onBack = { onRoute(Route.Basket) },
        )
        is Route.Conflict -> ConflictScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onEdit = { onRoute(Route.Candidate(route.candidateId)) },
            onDone = { onRoute(Route.Schedule) },
        )
        is Route.Complete -> AppointmentAddedScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onOpenPlanner = { onRoute(Route.Schedule) },
        )
        }
        if (showQuickAdd) {
            QuickAddScheduleDialog(
                repository = app.repository,
                onDismiss = { showQuickAdd = false },
            )
        }
    }
}

@Composable
private fun ComingSoonScreen(title: String, message: String, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FeedLoopColors.Background,
                        FeedLoopColors.PrimaryLight.copy(alpha = 0.35f),
                    ),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                border = BorderStroke(1.dp, FeedLoopColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = FeedLoopCardElevation),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        message,
                        color = FeedLoopColors.Secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark)) {
                        Text("일정으로 돌아가기")
                    }
                }
            }
        }
    }
}

@Composable
private fun MascotScaffold(
    selected: MainTab,
    onRoute: (Route) -> Unit,
    onQuickAdd: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f), content = content)
        Row(
            Modifier
                .fillMaxWidth()
                .background(FeedLoopColors.Surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = MainTab.entries
            tabs.take(2).forEach { tab ->
                MainTabItem(tab = tab, selected = selected, onRoute = onRoute)
            }
            Button(
                onClick = onQuickAdd,
                modifier = Modifier
                    .weight(0.8f)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            tabs.drop(2).forEach { tab ->
                MainTabItem(tab = tab, selected = selected, onRoute = onRoute)
            }
        }
    }
}

@Composable
private fun RowScope.MainTabItem(tab: MainTab, selected: MainTab, onRoute: (Route) -> Unit) {
    val isSelected = tab == selected
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onRoute(tab.route) }
            .background(if (isSelected) FeedLoopColors.PrimaryLight else Color.Transparent)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(tab.imageRes),
                contentDescription = tab.label,
                modifier = Modifier.size(if (isSelected) 48.dp else 40.dp),
            )
            tab.badgeText?.let { badgeText ->
                Spacer(Modifier.width(4.dp))
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = FeedLoopColors.Pending,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(FeedLoopColors.PendingBg, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            tab.label,
            color = if (isSelected) FeedLoopColors.PrimaryDark else FeedLoopColors.Secondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun QuickAddScheduleDialog(
    repository: PlannerRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var rawInput by remember { mutableStateOf("") }
    var startAt by remember { mutableStateOf<Long?>(null) }
    var endAt by remember { mutableStateOf<Long?>(null) }
    var location by remember { mutableStateOf("") }
    var reviewMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun parseInput() {
        val text = rawInput.trim()
        if (text.isBlank() || busy) return
        busy = true
        message = null
        scope.launch {
            runCatching {
                repository.parseQuickAddInput(text, System.currentTimeMillis())
            }.onSuccess { outcome ->
                startAt = outcome.result.startAt
                endAt = outcome.result.endAt
                location = outcome.result.location.orEmpty()
                reviewMode = true
            }.onFailure { error ->
                message = quickAddErrorMessage(error)
            }
            busy = false
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
            border = BorderStroke(1.dp, FeedLoopColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (reviewMode) "일정 확인" else "빠른 일정 추가",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!reviewMode) {
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = {
                            rawInput = it
                            message = null
                        },
                        label = { Text("자연어로 일정 입력") },
                        placeholder = { Text("예: 내일 7시 강남에서 저녁") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { parseInput() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FeedLoopColors.PrimaryDark,
                            unfocusedBorderColor = FeedLoopColors.Border,
                            focusedLabelColor = FeedLoopColors.PrimaryDark,
                            cursorColor = FeedLoopColors.PrimaryDark,
                        ),
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                } else {
                    Text(
                        text = rawInput.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FeedLoopColors.Secondary,
                    )
                    DateAndTimeRangeFields(
                        startMillis = startAt,
                        onStartChange = { startAt = it },
                        endMillis = endAt,
                        onEndChange = { endAt = it },
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("장소 (선택)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FeedLoopColors.PrimaryDark,
                            unfocusedBorderColor = FeedLoopColors.Border,
                            focusedLabelColor = FeedLoopColors.PrimaryDark,
                            cursorColor = FeedLoopColors.PrimaryDark,
                        ),
                    )
                    if (startAt == null) {
                        Text("시작 시간을 입력하면 확정할 수 있습니다.", color = FeedLoopColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(0.7f),
                        border = BorderStroke(1.dp, FeedLoopColors.Border),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text("취소", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            if (!reviewMode) {
                                parseInput()
                            } else {
                                val confirmedStart = startAt ?: return@Button
                                busy = true
                                message = null
                                scope.launch {
                                    runCatching {
                                        repository.createConfirmedScheduleFromQuickAdd(
                                            rawText = rawInput.trim(),
                                            startAt = confirmedStart,
                                            endAt = endAt,
                                            location = location,
                                        )
                                    }.onSuccess {
                                        Toast.makeText(context, "일정을 추가했습니다.", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }.onFailure { error ->
                                        message = quickAddErrorMessage(error)
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy && rawInput.isNotBlank() && (!reviewMode || startAt != null),
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
                    ) {
                        Text(if (reviewMode) "확정" else "확인")
                    }
                }
            }
        }
    }
}

private fun quickAddErrorMessage(error: Throwable): String {
    val detail = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
    return when {
        detail == "Login is required." -> "로그인이 필요합니다."
        detail == "Planner API is not configured." -> "Planner API 주소가 설정되지 않았습니다."
        error is IOException || error.cause is IOException -> "네트워크 요청에 실패했습니다."
        detail != null -> detail
        else -> "일정 처리에 실패했습니다."
    }
}
