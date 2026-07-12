package com.example.tiktokdl

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.example.tiktokdl.extractors.UrlDetector
import com.example.tiktokdl.service.DownloadService

/**
 * This Activity has NO visible UI. It exists only to catch the "Share" intent
 * coming from the TikTok app (ACTION_SEND, text/plain).
 *
 * When a link is shared here, we pull the URL out of the shared text, hand it
 * to the background download service, and finish() immediately — the user
 * never sees any screen, just a toast + a notification a moment later.
 *
 * The app's visible launcher icon opens MainActivity instead.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = sharedText?.let { UrlDetector.extractUrlFromText(it) }

            if (url != null) {
                Toast.makeText(this, "Downloading… check notification", Toast.LENGTH_SHORT).show()

                val serviceIntent = Intent(this, DownloadService::class.java).apply {
                    putExtra(DownloadService.EXTRA_URL, url)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "No TikTok link found in shared text", Toast.LENGTH_SHORT).show()
            }
        }

        // No UI at all — close instantly.
        finish()
    }
}
