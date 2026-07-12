package com.example.tiktokdl.extractors

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves TikTok share links (vt.tiktok.com, vm.tiktok.com or full tiktok.com/@user/video/id
 * links) to direct, no-watermark media URLs using the public tikwm resolver.
 */
object TikTokExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val API_BASE = "https://www.tikwm.com/api/"

    fun resolve(shareUrl: String): MediaResult {
        val apiUrl = "$API_BASE?url=${java.net.URLEncoder.encode(shareUrl, "UTF-8")}&hd=1"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MediaExtractionException("Server error: ${response.code}")
            }
            val body = response.body?.string()
                ?: throw MediaExtractionException("Empty response")

            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw MediaExtractionException(json.optString("msg", "Could not resolve link"))
            }

            val data = json.getJSONObject("data")
            val title = data.optString("title", "tiktok")

            // Slideshow (images) post
            val imagesArray = data.optJSONArray("images")
            if (imagesArray != null && imagesArray.length() > 0) {
                val items = mutableListOf<MediaItem>()
                for (i in 0 until imagesArray.length()) {
                    items.add(MediaItem(imagesArray.getString(i), MediaKind.IMAGE))
                }
                val musicUrl = data.optString("music").ifBlank { null }
                return MediaResult(Platform.TIKTOK, title, items, musicUrl)
            }

            // Regular video post
            val videoUrl = data.optString("hdplay").ifBlank { data.optString("play") }
            if (videoUrl.isBlank()) {
                throw MediaExtractionException("No downloadable media found")
            }
            val musicUrl = data.optString("music").ifBlank { null }
            return MediaResult(Platform.TIKTOK, title, listOf(MediaItem(videoUrl, MediaKind.VIDEO)), musicUrl)
        }
    }
}
