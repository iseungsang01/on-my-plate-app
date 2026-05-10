package com.lss.onmyplate.nativeplanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.R
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAfterLogin = startRoute(intent)
        routeState.value = if (hasAppAccess()) pendingAfterLogin else Route.Login
        setContent {
            MaterialTheme(colorScheme = FeedLoopColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val route by routeState
                    AppRoot(
                        route = route,
                        onRoute = { routeState.value = it },
                    )
                }
            }
        }
        maybeRequestNotifications()
        checkForAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        resumeAppUpdateIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingAfterLogin = startRoute(intent)
        routeState.value = if (hasAppAccess()) pendingAfterLogin else Route.Login
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

    private fun startRoute(intent: Intent): Route {
        val candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID)
        return when {
            candidateId != null && intent.getBooleanExtra(EXTRA_CONFLICT, false) -> Route.Conflict(candidateId)
            candidateId != null -> Route.Candidate(candidateId)
            else -> Route.Schedule
        }
    }

    companion object {
        private const val EXTRA_CANDIDATE_ID = "candidate_id"
        private const val EXTRA_CONFLICT = "conflict"

        fun candidateIntent(context: Context, candidateId: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CANDIDATE_ID, candidateId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        fun conflictIntent(context: Context, candidateId: String): Intent =
            candidateIntent(context, candidateId).apply { putExtra(EXTRA_CONFLICT, true) }
    }
}

sealed interface Route {
    data object Schedule : Route
    data object Basket : Route
    data object Sharing : Route
    data object Settings : Route
    data object Login : Route
    data class ScheduleEdit(val scheduleId: String) : Route
    data class Candidate(val candidateId: String) : Route
    data class Conflict(val candidateId: String) : Route
    data class Complete(val candidateId: String) : Route
}

private enum class MainTab(val label: String, val route: Route, val imageRes: Int) {
    Schedule("일정", Route.Schedule, R.drawable.mascot_note),
    Basket("후보", Route.Basket, R.drawable.mascot_basket),
    Sharing("공유", Route.Sharing, R.drawable.mascot_plane),
    Settings("설정", Route.Settings, R.drawable.mascot_settings),
}

@Composable
private fun AppRoot(route: Route, onRoute: (Route) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OnMyPlateApp
    when (route) {
        Route.Login -> LoginScreen(authRepository = app.authRepository, onAuthenticated = { onRoute(Route.Schedule) })
        Route.Schedule -> MascotScaffold(selected = MainTab.Schedule, onRoute = onRoute) {
            WeeklyScheduleScreen(repository = app.repository, onOpenSchedule = { onRoute(Route.ScheduleEdit(it)) })
        }
        Route.Basket -> MascotScaffold(selected = MainTab.Basket, onRoute = onRoute) {
            BasketScreen(repository = app.repository, onOpenCandidate = { onRoute(Route.Candidate(it)) })
        }
        Route.Sharing -> MascotScaffold(selected = MainTab.Sharing, onRoute = onRoute) {
            SharingScreen(plannerRepository = app.repository, sharingRepository = app.sharingRepository, onBack = { onRoute(Route.Schedule) })
        }
        Route.Settings -> MascotScaffold(selected = MainTab.Settings, onRoute = onRoute) {
            SettingsScreen(
                authRepository = app.authRepository,
                sharingRepository = app.sharingRepository,
                onLoggedOut = {
                    app.authRepository.clearAccess()
                    app.sharingRepository.clearAccountCache()
                    onRoute(Route.Login)
                },
            )
        }
        is Route.ScheduleEdit -> ScheduleEditScreen(
            repository = app.repository,
            scheduleId = route.scheduleId,
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
private fun MascotScaffold(selected: MainTab, onRoute: (Route) -> Unit, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f), content = content)
        Row(
            Modifier
                .fillMaxWidth()
                .background(FeedLoopColors.Surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onRoute(tab.route) }
                        .background(if (isSelected) FeedLoopColors.PrimaryLight else Color.Transparent)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(tab.imageRes),
                        contentDescription = tab.label,
                        modifier = Modifier.size(if (isSelected) 58.dp else 48.dp),
                    )
                    Text(
                        tab.label,
                        color = if (isSelected) FeedLoopColors.PrimaryDark else FeedLoopColors.Secondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
