package com.example.tiktokdl.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.example.tiktokdl.MainActivity
import com.example.tiktokdl.R
import com.example.tiktokdl.data.DownloadEvents
import com.example.tiktokdl.data.DownloadProgressUpdate
import com.example.tiktokdl.data.DownloadHistoryStore
import com.example.tiktokdl.data.HistoryEntry
import com.example.tiktokdl.extractors.MediaExtractionException
import com.example.tiktokdl.extractors.MediaItem
import com.example.tiktokdl.extractors.MediaKind
import com.example.tiktokdl.extractors.MediaResolver
import com.example.tiktokdl.extractors.MediaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_AUDIO_ONLY = "extra_audio_only"
        private const val CHANNEL_ID = "media_saver_downloads"
        private const val NOTIF_ID_BASE = 2000
        private const val NOTIF_ID_TERMINAL_OFFSET = 100000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var notificationManager: NotificationManager
    private var notifId = NOTIF_ID_BASE

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val audioOnly = intent?.getBooleanExtra(EXTRA_AUDIO_ONLY, false) ?: false
        notifId = NOTIF_ID_BASE + startId

        startForeground(notifId, buildProgressNotification("Resolving link...", 0, indeterminate = true))
        DownloadEvents.updateProgress(DownloadProgressUpdate("Resolving link...", 0, indeterminate = true))

        if (url == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        CoroutineScope(Dispatchers.IO).launch {
            var terminalTitle: String = "Download failed"
            var terminalText: String = "Something went wrong"
            var isError = false
            try {
                val resolved = MediaResolver.resolve(url)
                val result = if (audioOnly) {
                    val audioUrl = resolved.audioUrl
                        ?: throw MediaExtractionException("No audio track found for this link")
                    resolved.copy(items = listOf(MediaItem(audioUrl, MediaKind.AUDIO)))
                } else {
                    resolved
                }
                val outcome = downloadAll(result)
                terminalTitle = outcome.first
                terminalText = outcome.second
            } catch (e: MediaExtractionException) {
                isError = true
                terminalTitle = "Download failed"
                terminalText = e.message ?: "Could not resolve this link"
            } catch (e: Exception) {
                isError = true
                terminalTitle = "Download failed"
                terminalText = e.message ?: "Download failed"
            } finally {
                // Release the foreground notification slot FIRST (removes it outright)
                // before posting the terminal notification under a fresh id. Cancelling
                // the same id manually while still foreground is what left the old
                // progress bar stuck at 100% in the tray on some devices.
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (isError) {
                    postErrorNotification(terminalText)
                } else {
                    postCompleteNotification(terminalTitle, terminalText)
                }
                stopSelf(startId)
                DownloadEvents.updateProgress(null)
                DownloadEvents.notifyChanged()
            }
        }

        return START_NOT_STICKY
    }

    // ---------- Orchestration ----------

    /** Downloads every item and returns the (title, text) pair for the terminal notification. */
    private fun downloadAll(result: MediaResult): Pair<String, String> {
        val total = result.items.size
        result.items.forEachIndexed { index, item ->
            val label = if (total > 1) "Saving ${index + 1}/$total" else "Saving"
            downloadOne(result, item, label)
        }

        val kindLabel = result.items.firstOrNull()?.kind?.folderName ?: "media"
        return "Saved to ${result.platform.displayName}/$kindLabel" to
            (if (total > 1) "$total files saved" else "1 file saved")
    }

    private fun downloadOne(result: MediaResult, item: MediaItem, progressLabel: String) {
        val request = Request.Builder().url(item.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MediaExtractionException("Download failed (${response.code})")
            val body = response.body ?: throw MediaExtractionException("Empty response body")
            val totalBytes = body.contentLength()

            val extension = when (item.kind) {
                MediaKind.IMAGE -> "jpg"
                MediaKind.AUDIO -> "mp3"
                MediaKind.VIDEO -> "mp4"
            }
            val mimeType = when (item.kind) {
                MediaKind.IMAGE -> "image/jpeg"
                MediaKind.AUDIO -> "audio/mpeg"
                MediaKind.VIDEO -> "video/mp4"
            }
            val fileName = "${result.platform.name.lowercase()}_${System.currentTimeMillis()}.$extension"
            val relativePath = "Download/TikTokSaver/${item.kind.folderName}"

            val (uri, out) = createMediaStoreOutput(fileName, mimeType, relativePath)

            out.use { outputStream ->
                body.byteStream().use { input ->
                    copyWithProgress(input, outputStream, totalBytes) { readBytes, totalKnown, percent ->
                        updateNotification(progressLabel, percent, readBytes, totalKnown)
                    }
                }
            }
            finalizeMediaStoreEntry(uri)

            DownloadHistoryStore.add(
                this,
                HistoryEntry(
                    title = result.title,
                    platform = result.platform.displayName,
                    kind = item.kind.folderName,
                    fileName = fileName,
                    timestamp = System.currentTimeMillis(),
                    fileUri = uri.toString(),
                    status = "done"
                )
            )
            DownloadEvents.notifyChanged()
        }
    }

    // ---------- Storage (scoped storage safe, Android 10+) ----------

    private fun createMediaStoreOutput(
        fileName: String,
        mimeType: String,
        relativePath: String
    ): Pair<Uri, OutputStream> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw MediaExtractionException("Could not create output file")
        val out = contentResolver.openOutputStream(uri)
            ?: throw MediaExtractionException("Could not open output stream")
        return uri to out
    }

    private fun finalizeMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (readBytes: Long, totalBytes: Long, percent: Int) -> Unit
    ) {
        val buffer = ByteArray(16 * 1024)
        var readTotal = 0L
        var lastReportedPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            readTotal += read
            if (totalBytes > 0) {
                val percent = ((readTotal * 100) / totalBytes).toInt()
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    onProgress(readTotal, totalBytes, percent)
                }
            } else {
                onProgress(readTotal, -1, -1)
            }
        }
    }

    // ---------- Notifications ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "TikTok Saver download progress"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): android.app.PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(text: String, percent: Int, indeterminate: Boolean): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TikTok Saver")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun updateNotification(label: String, percent: Int, readBytes: Long, totalBytes: Long) {
        val sizeText = if (totalBytes > 0) {
            "${formatBytes(readBytes)} / ${formatBytes(totalBytes)} · $percent%"
        } else {
            formatBytes(readBytes)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(sizeText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, percent.coerceIn(0, 100), totalBytes <= 0)
            .build()
        notificationManager.notify(notifId, notification)

        DownloadEvents.updateProgress(
            DownloadProgressUpdate(label, percent.coerceIn(0, 100), totalBytes <= 0)
        )
    }

    /** Called only AFTER stopForeground() has already released notifId — nothing to cancel here. */
    private fun postCompleteNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(notifId + NOTIF_ID_TERMINAL_OFFSET, notification)
    }

    /** Called only AFTER stopForeground() has already released notifId — nothing to cancel here. */
    private fun postErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Download failed")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(notifId + NOTIF_ID_TERMINAL_OFFSET, notification)

        DownloadHistoryStore.add(
            this,
            HistoryEntry(
                title = message,
                platform = "TikTok",
                kind = "",
                fileName = "",
                timestamp = System.currentTimeMillis(),
                status = "failed"
            )
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "--"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
