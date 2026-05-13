package com.lss.onmyplate.nativeplanner.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import kotlinx.coroutines.launch

class ShareReceiverActivity : Activity() {
    private var pendingSharedText: String? = null
    private var pendingSourceApp: String? = null
    private var pendingReceivedAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" || sharedText.isNullOrBlank()) {
            Log.w(TAG, "Ignoring unsupported share intent. action=${intent.action}, type=${intent.type}, hasText=${!sharedText.isNullOrBlank()}")
            finish()
            return
        }

        pendingSharedText = sharedText
        pendingSourceApp = callingPackage
        pendingReceivedAt = System.currentTimeMillis()
        Log.i(TAG, "Received shared text. textLength=${sharedText.length}, sourceApp=$pendingSourceApp")

        if (needsNotificationPermission()) {
            Log.i(TAG, "Notification permission is missing. Requesting POST_NOTIFICATIONS before saving shared text.")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        saveAndNotify()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Notification permission result received. granted=$granted")
            saveAndNotify()
        }
    }

    private fun saveAndNotify() {
        val sharedText = pendingSharedText
        if (sharedText.isNullOrBlank()) {
            Log.w(TAG, "No pending shared text found when saveAndNotify was called.")
            finish()
            return
        }

        val app = application as OnMyPlateApp
        val sourceApp = pendingSourceApp
        val receivedAt = pendingReceivedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val sessionState = app.authRepository.debugSessionState()
        Log.i(TAG, "Share save preflight. $sessionState")
        if (!app.authRepository.hasAppAccess()) {
            Log.w(TAG, "Cannot save shared text because no app session is cached. $sessionState")
            Toast.makeText(
                this,
                "로그인 세션이 없어 약속 정보를 저장하지 못했습니다.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        app.appScope.launch {
            try {
                Log.i(TAG, "Creating appointment candidate from shared text. textLength=${sharedText.length}, sourceApp=$sourceApp")
                val candidate = app.repository.createCandidate(sharedText, sourceApp, receivedAt)
                Log.i(TAG, "Appointment candidate saved. candidateId=${candidate.id}, hasStart=${candidate.extractedStartAt != null}, hasLocation=${candidate.extractedLocation != null}")
                val notificationShown = app.notifications.showCandidate(candidate)
                Log.i(TAG, "Candidate notification requested. candidateId=${candidate.id}, shown=$notificationShown")
                runOnUiThread {
                    if (!notificationShown) {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            "알림이 꺼져 있어 약속 정보는 바구니에만 저장되었습니다.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    finish()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to save shared appointment text. textLength=${sharedText.length}, sourceApp=$sourceApp, ${app.authRepository.debugSessionState()}", error)
                runOnUiThread {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "약속 정보를 저장하지 못했습니다. 로그인 또는 네트워크를 확인해 주세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            }
        }
        Toast.makeText(this, "약속 정보를 확인 중입니다", Toast.LENGTH_SHORT).show()
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ShareReceiverActivity"
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
