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

internal const val CANDIDATE_UNCERTAIN_ACTION_LABEL = "\uBBF8\uC815"
internal const val CANDIDATE_CONFIRMED_ACTION_LABEL = "\uD655\uC815"
internal const val CANDIDATE_MEMO_REMOTE_INPUT_LABEL = "\uC77C\uC815 \uBA54\uBAA8 \uC791\uC131"
internal const val CANDIDATE_TITLE_REMOTE_INPUT_LABEL = "\uC77C\uC815 \uC81C\uBAA9 \uC791\uC131"

class AppointmentNotificationManager(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val zoneId = ZoneId.of("Asia/Seoul")
    private val formatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zoneId)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_APPOINTMENTS, "일정 디테일 설정", NotificationManager.IMPORTANCE_HIGH),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFLICTS, "일정 충돌", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    fun showCandidate(candidate: AppointmentCandidateEntity): Boolean {
        if (!canNotify(CHANNEL_APPOINTMENTS)) return false
        val contentIntent = PendingIntent.getActivity(
            context,
            candidate.id.hashCode(),
            MainActivity.candidateIntent(context, candidate.id),
            immutablePendingFlags(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_APPOINTMENTS)
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentTitle("일정 디테일 설정")
            .setContentText(candidateSummary(candidate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(candidateDetails(candidate)))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(saveAction(candidate.id, ScheduleStatus.Uncertain, CANDIDATE_UNCERTAIN_ACTION_LABEL, CANDIDATE_MEMO_REMOTE_INPUT_LABEL))
            .addAction(saveAction(candidate.id, ScheduleStatus.Confirmed, CANDIDATE_CONFIRMED_ACTION_LABEL, CANDIDATE_TITLE_REMOTE_INPUT_LABEL))
            .build()

        notificationManager.notify(candidate.id.hashCode(), notification)
        return true
    }

    fun showConflict(candidate: AppointmentCandidateEntity, existing: ScheduleEntity) {
        if (!canNotify(CHANNEL_CONFLICTS)) return
        val editIntent = MainActivity.conflictIntent(context, candidate.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentTitle("일정이 겹칩니다")
            .setContentText("${existing.title} ${formatRange(existing.startAt, existing.endAt)}")
            .setContentIntent(PendingIntent.getActivity(context, candidate.id.hashCode(), editIntent, immutablePendingFlags()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
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

    private fun saveAction(
        candidateId: String,
        status: ScheduleStatus,
        label: String,
        inputLabel: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SAVE
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
            putExtra(EXTRA_STATUS, status.dbValue)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, (candidateId + status.dbValue).hashCode(), intent, mutablePendingFlags())
        val builder = NotificationCompat.Action.Builder(R.drawable.ic_stat_calendar, label, pendingIntent)
        val remoteInput = RemoteInput.Builder(KEY_REMOTE_TITLE)
            .setLabel(inputLabel)
            .build()
        builder.addRemoteInput(remoteInput)
        return builder.build()
    }

    private fun conflictAction(candidateId: String, actionName: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = actionName
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
        }
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_calendar,
            label,
            PendingIntent.getBroadcast(context, (candidateId + actionName).hashCode(), intent, immutablePendingFlags()),
        ).build()
    }

    private fun conflictActivityAction(candidateId: String, label: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_stat_calendar,
            label,
            PendingIntent.getActivity(context, (candidateId + label).hashCode(), MainActivity.conflictIntent(context, candidateId), immutablePendingFlags()),
        ).build()

    private fun candidateSummary(candidate: AppointmentCandidateEntity): String =
        CandidateNotificationText.summary(candidate, formatter)

    private fun candidateDetails(candidate: AppointmentCandidateEntity): String =
        CandidateNotificationText.details(candidate, formatter)

    private fun formatRange(startAt: Long, endAt: Long?): String {
        val effectiveEnd = ConflictDetector.newEnd(startAt, endAt)
        return "${formatter.format(Instant.ofEpochMilli(startAt))}-${formatter.format(Instant.ofEpochMilli(effectiveEnd))}"
    }

    fun canNotify(channelId: String = CHANNEL_APPOINTMENTS): Boolean {
        val hasRuntimePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasRuntimePermission || !notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        return true
    }

    private fun immutablePendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun mutablePendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

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

internal object CandidateNotificationText {
    private const val UnknownTime = "미정"

    fun summary(candidate: AppointmentCandidateEntity, formatter: DateTimeFormatter): String {
        val start = formatTime(candidate.extractedStartAt, formatter)
        val location = candidate.extractedLocation?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "시작 시간 : $start$location"
    }

    fun details(candidate: AppointmentCandidateEntity, formatter: DateTimeFormatter): String {
        val location = candidate.extractedLocation.orEmpty()
        val source = candidate.sourceApp?.takeIf { it.isNotBlank() }?.let { "\n공유 앱: $it" }.orEmpty()
        return listOf(
            "시작 시간 : ${formatTime(candidate.extractedStartAt, formatter)}",
            "종료 시간 : ${formatTime(candidate.extractedEndAt, formatter)}",
            "장소 : $location",
            "미정은 메모를, 확정은 제목을 작성해 시간표에 저장합니다",
        ).joinToString("\n") + source
    }

    private fun formatTime(value: Long?, formatter: DateTimeFormatter): String =
        value?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: UnknownTime
}
