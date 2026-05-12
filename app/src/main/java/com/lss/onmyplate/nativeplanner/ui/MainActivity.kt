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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.R
import com.lss.onmyplate.nativeplanner.widget.PlannerWidgetSync
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.launch

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
                if (!notificationShown) {
                    routeState.value = Route.Candidate(candidate.id)
                }
            } catch (error: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "약속 정보를 저장하지 못했습니다. 로그인 또는 네트워크를 확인해 주세요.",
                    Toast.LENGTH_LONG,
                ).show()
                routeState.value = Route.Basket
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
    data object Settings : Route
    data object Login : Route
    data class ScheduleEdit(val scheduleId: String, val occurrenceStartAt: Long? = null) : Route
    data class Candidate(val candidateId: String) : Route
    data class Conflict(val candidateId: String) : Route
    data class Complete(val candidateId: String) : Route
}

private enum class MainTab(val label: String, val route: Route, val imageRes: Int, val badgeText: String? = null) {
    Schedule("일정", Route.Schedule, R.drawable.mascot_note),
    Basket("바구니", Route.Basket, R.drawable.mascot_basket),
    Settings("설정", Route.Settings, R.drawable.mascot_settings),
}

@Composable
private fun AppRoot(route: Route, onRoute: (Route) -> Unit, onAuthenticated: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OnMyPlateApp
    when (route) {
        Route.Login -> LoginScreen(authRepository = app.authRepository, onAuthenticated = onAuthenticated)
        Route.Schedule -> MascotScaffold(selected = MainTab.Schedule, onRoute = onRoute) {
            WeeklyScheduleScreen(repository = app.repository, onOpenSchedule = { scheduleId, occurrenceStartAt ->
                onRoute(Route.ScheduleEdit(scheduleId, occurrenceStartAt))
            })
        }
        Route.Basket -> MascotScaffold(selected = MainTab.Basket, onRoute = onRoute) {
            BasketScreen(repository = app.repository, onOpenCandidate = { onRoute(Route.Candidate(it)) })
        }
        Route.Sharing -> SharingScreen(
            plannerRepository = app.repository,
            sharingRepository = app.sharingRepository,
            onBack = { onRoute(Route.Schedule) },
        )
        Route.Settings -> MascotScaffold(selected = MainTab.Settings, onRoute = onRoute) {
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
            onBack = { onRoute(Route.Schedule) },
        )
        is Route.Candidate -> CandidateEditScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onDone = { onRoute(Route.Complete(route.candidateId)) },
            onConflict = { onRoute(Route.Conflict(route.candidateId)) },
            onBack = { onRoute(Route.Basket) },
        )
        is Route.Conflict -> ConflictScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onEdit = { onRoute(Route.Candidate(route.candidateId)) },
            onDone = { onRoute(Route.Complete(route.candidateId)) },
        )
        is Route.Complete -> AppointmentAddedScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onOpenPlanner = { onRoute(Route.Schedule) },
        )
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
private fun MascotScaffold(selected: MainTab, onRoute: (Route) -> Unit, content: @Composable BoxScope.() -> Unit) {
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
            MainTab.entries.forEach { tab ->
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
        }
    }
}
