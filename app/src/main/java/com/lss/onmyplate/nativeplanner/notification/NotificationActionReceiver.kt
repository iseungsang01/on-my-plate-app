package com.lss.onmyplate.nativeplanner.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.ACTION_CANCEL
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.ACTION_FORCE_ADD
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.ACTION_SAVE
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.EXTRA_CANDIDATE_ID
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.EXTRA_STATUS
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager.Companion.KEY_REMOTE_TITLE
import com.lss.onmyplate.nativeplanner.data.repository.SaveResult
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as OnMyPlateApp
        app.appScope.launch {
            var candidateId: String? = null
            try {
                candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID) ?: return@launch
                var keepConflictNotification = false
                when (intent.action) {
                    ACTION_SAVE -> keepConflictNotification = handleSave(app, intent, candidateId)
                    ACTION_FORCE_ADD -> app.repository.saveFromCandidate(candidateId, ScheduleStatus.Confirmed, null, force = true)
                    ACTION_CANCEL -> app.repository.discardCandidate(candidateId)
                }
                if (keepConflictNotification) {
                    app.notifications.cancelCandidatePrompt(candidateId)
                } else {
                    app.notifications.cancelCandidate(candidateId)
                }
            } catch (error: Throwable) {
                candidateId?.let {
                    app.notifications.showActionFailed(
                        candidateId = it,
                        message = error.message?.takeIf { message -> message.isNotBlank() }
                            ?: "일정 저장에 실패했습니다. 네트워크와 로그인 상태를 확인해 주세요.",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleSave(app: OnMyPlateApp, intent: Intent, candidateId: String): Boolean {
        // Native RemoteInput handling: the same input key is used by the two notification actions.
        // Confirmed treats the text as a title; uncertain treats it as a memo.
        val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REMOTE_TITLE)?.toString()
        val status = when (intent.getStringExtra(EXTRA_STATUS)) {
            ScheduleStatus.Confirmed.dbValue -> ScheduleStatus.Confirmed
            ScheduleStatus.Planned.dbValue -> ScheduleStatus.Planned
            else -> ScheduleStatus.Uncertain
        }
        val title = input.takeIf { status != ScheduleStatus.Uncertain }
        val memo = input.takeIf { status == ScheduleStatus.Uncertain }
        return when (val result = app.repository.saveFromCandidate(candidateId, status, title, memoOverride = memo)) {
            SaveResult.TitleRequired -> {
                app.repository.getCandidate(candidateId)?.let { app.notifications.showCandidate(it) }
                true
            }
            is SaveResult.Conflict -> {
                app.notifications.showConflict(result.candidate, result.conflicts.first())
                true
            }
            else -> {
                false
            }
        }
    }
}
