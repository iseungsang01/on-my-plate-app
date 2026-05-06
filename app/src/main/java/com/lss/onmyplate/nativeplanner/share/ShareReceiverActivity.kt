package com.lss.onmyplate.nativeplanner.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
            finish()
            return
        }

        pendingSharedText = sharedText
        pendingSourceApp = callingPackage
        pendingReceivedAt = System.currentTimeMillis()

        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        saveAndNotify()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            saveAndNotify()
        }
    }

    private fun saveAndNotify() {
        val sharedText = pendingSharedText
        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as OnMyPlateApp
        val sourceApp = pendingSourceApp
        val receivedAt = pendingReceivedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        app.appScope.launch {
            val candidate = app.repository.createCandidate(sharedText, sourceApp, receivedAt)
            app.notifications.showCandidate(candidate)
            runOnUiThread { finish() }
        }
        Toast.makeText(this, "약속 정보를 확인 중입니다", Toast.LENGTH_SHORT).show()
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
