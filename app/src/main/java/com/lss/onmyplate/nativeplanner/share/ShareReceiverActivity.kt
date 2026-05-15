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
import com.lss.onmyplate.nativeplanner.ui.MainActivity
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
            Log.i(TAG, "Notification permission is missing. Requesting POST_NOTIFICATIONS before creating shared text detail setup.")
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
        Log.i(TAG, "Share detail setup preflight. $sessionState")
        if (!app.authRepository.hasAppAccess()) {
            Log.w(TAG, "Cannot create shared text detail setup because no app session is cached. $sessionState")
            Toast.makeText(this, "로그인이 필요합니다. 앱에서 로그인한 뒤 다시 공유해 주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        app.appScope.launch {
            try {
                Log.i(TAG, "Creating appointment detail setup from shared text. textLength=${sharedText.length}, sourceApp=$sourceApp")
                val candidate = app.repository.createCandidate(sharedText, sourceApp, receivedAt)
                val notificationShown = app.notifications.showCandidate(candidate)
                Log.i(TAG, "Appointment detail setup created. candidateId=${candidate.id}, notificationShown=$notificationShown")
                runOnUiThread {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        if (notificationShown) "알림에서 제목을 입력하고 확정해 주세요." else "알림을 표시할 수 없어 앱에서 디테일을 설정해 주세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (!notificationShown) startActivity(MainActivity.candidateIntent(this@ShareReceiverActivity, candidate.id))
                    finish()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to create appointment detail setup from shared text. textLength=${sharedText.length}, sourceApp=$sourceApp, ${app.authRepository.debugSessionState()}", error)
                runOnUiThread {
                    Toast.makeText(this@ShareReceiverActivity, "일정 디테일 설정을 만들지 못했습니다. 로그인 또는 네트워크를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        Toast.makeText(this, "일정 디테일 설정을 만드는 중입니다.", Toast.LENGTH_SHORT).show()
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ShareReceiverActivity"
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
