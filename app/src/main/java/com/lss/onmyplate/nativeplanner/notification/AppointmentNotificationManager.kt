package com.lss.onmyplate.nativeplanner.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.lss.onmyplate.nativeplanner.R
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.domain.conflict.ConflictDetector
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

class AppointmentNotificationManager(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val zoneId = ZoneId.of("Asia/Seoul")
    private val formatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zoneId)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_APPOINTMENTS, "Appointment candidates", NotificationManager.IMPORTANCE_HIGH),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFLICTS, "Schedule conflicts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    fun showCandidate(candidate: AppointmentCandidateEntity) {
        if (!canNotify()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            candidate.id.hashCode(),
            MainActivity.candidateIntent(context, candidate.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_APPOINTMENTS)
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentTitle("약속 후보를 찾았습니다")
            .setContentText(summary(candidate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary(candidate)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(action(candidate.id, ScheduleStatus.Confirmed, "확정"))
            .addAction(action(candidate.id, ScheduleStatus.Planned, "예정"))
            .addAction(action(candidate.id, ScheduleStatus.Uncertain, "미정"))
            .build()

        notificationManager.notify(candidate.id.hashCode(), notification)
    }

    fun showConflict(candidate: AppointmentCandidateEntity, existing: ScheduleEntity) {
        if (!canNotify()) return
        val editIntent = MainActivity.conflictIntent(context, candidate.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentTitle("일정이 겹칩니다")
            .setContentText("${existing.title} ${formatRange(existing.startAt, existing.endAt)}")
            .setContentIntent(PendingIntent.getActivity(context, candidate.id.hashCode(), editIntent, pendingFlags()))
            .setAutoCancel(true)
            .addAction(conflictAction(candidate.id, ACTION_FORCE_ADD, "그래도 추가"))
            .addAction(conflictActivityAction(candidate.id, "수정하기"))
            .addAction(conflictAction(candidate.id, ACTION_CANCEL, "취소"))
            .build()
        notificationManager.notify(conflictNotificationId(candidate.id), notification)
    }

    fun cancelCandidate(candidateId: String) {
        notificationManager.cancel(candidateId.hashCode())
        notificationManager.cancel(conflictNotificationId(candidateId))
    }

    fun cancelCandidatePrompt(candidateId: String) {
        notificationManager.cancel(candidateId.hashCode())
    }

    private fun action(candidateId: String, status: ScheduleStatus, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SAVE
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
            putExtra(EXTRA_STATUS, status.dbValue)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, (candidateId + status.dbValue).hashCode(), intent, pendingFlags())

        // RemoteInput is attached to each native notification action so the typed title arrives in NotificationActionReceiver.
        val remoteInput = RemoteInput.Builder(KEY_REMOTE_TITLE)
            .setLabel("제목")
            .build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_calendar, label, pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun conflictAction(candidateId: String, actionName: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = actionName
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
        }
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_calendar,
            label,
            PendingIntent.getBroadcast(context, (candidateId + actionName).hashCode(), intent, pendingFlags()),
        ).build()
    }

    private fun conflictActivityAction(candidateId: String, label: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_stat_calendar,
            label,
            PendingIntent.getActivity(context, (candidateId + label).hashCode(), MainActivity.conflictIntent(context, candidateId), pendingFlags()),
        ).build()

    private fun summary(candidate: AppointmentCandidateEntity): String {
        val time = candidate.extractedStartAt?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "시간 미정"
        val location = candidate.extractedLocation?.let { " · $it" }.orEmpty()
        return "$time$location · ${candidate.extractedTitle}"
    }

    private fun formatRange(startAt: Long, endAt: Long?): String {
        val effectiveEnd = ConflictDetector.newEnd(startAt, endAt)
        return "${formatter.format(Instant.ofEpochMilli(startAt))}-${formatter.format(Instant.ofEpochMilli(effectiveEnd))}"
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun conflictNotificationId(candidateId: String) = candidateId.hashCode().absoluteValue + 100_000

    companion object {
        const val CHANNEL_APPOINTMENTS = "appointments"
        const val CHANNEL_CONFLICTS = "conflicts"
        const val ACTION_SAVE = "com.lss.onmyplate.nativeplanner.SAVE"
        const val ACTION_FORCE_ADD = "com.lss.onmyplate.nativeplanner.FORCE_ADD"
        const val ACTION_CANCEL = "com.lss.onmyplate.nativeplanner.CANCEL"
        const val EXTRA_CANDIDATE_ID = "candidate_id"
        const val EXTRA_STATUS = "status"
        const val KEY_REMOTE_TITLE = "remote_title"
    }
}
