package com.lss.onmyplate.nativeplanner.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import kotlinx.coroutines.launch

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" || sharedText.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as OnMyPlateApp
        val sourceApp = callingPackage
        val receivedAt = System.currentTimeMillis()
        app.appScope.launch {
            val candidate = app.repository.createCandidate(sharedText, sourceApp, receivedAt)
            app.notifications.showCandidate(candidate)
        }
        Toast.makeText(this, "약속 후보를 확인 중입니다", Toast.LENGTH_SHORT).show()
        finish()
    }
}
