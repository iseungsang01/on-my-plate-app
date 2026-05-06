package com.lss.onmyplate.nativeplanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.lss.onmyplate.nativeplanner.OnMyPlateApp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var routeState = mutableStateOf<Route>(Route.Planner)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        routeState.value = startRoute(intent)
        setContent {
            MaterialTheme {
                Surface {
                    val route by routeState
                    AppRoot(
                        route = route,
                        onRoute = { routeState.value = it },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeState.value = startRoute(intent)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startRoute(intent: Intent): Route {
        val candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID)
        return when {
            candidateId != null && intent.getBooleanExtra(EXTRA_CONFLICT, false) -> Route.Conflict(candidateId)
            candidateId != null -> Route.Candidate(candidateId)
            else -> Route.Planner
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
    data object Planner : Route
    data class Candidate(val candidateId: String) : Route
    data class Conflict(val candidateId: String) : Route
}

@Composable
private fun AppRoot(route: Route, onRoute: (Route) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OnMyPlateApp
    when (route) {
        Route.Planner -> PlannerScreen(repository = app.repository, onOpenCandidate = { onRoute(Route.Candidate(it)) })
        is Route.Candidate -> CandidateEditScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onDone = { onRoute(Route.Planner) },
            onConflict = { onRoute(Route.Conflict(route.candidateId)) },
        )
        is Route.Conflict -> ConflictScreen(
            repository = app.repository,
            candidateId = route.candidateId,
            onEdit = { onRoute(Route.Candidate(route.candidateId)) },
            onDone = { onRoute(Route.Planner) },
        )
    }
}
