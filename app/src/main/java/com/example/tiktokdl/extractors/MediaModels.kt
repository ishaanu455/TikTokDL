package com.example.tiktokdl.extractors

/** This app only ever resolves TikTok links; anything else is UNKNOWN. */
enum class Platform(val displayName: String, val folderName: String) {
    TIKTOK("TikTok", "TikTok"),
    UNKNOWN("Unknown", "Other")
}

/** The kind of media, used to pick the correct sub-sub-folder (Videos / Images). */
enum class MediaKind(val folderName: String) {
    VIDEO("Videos"),
    IMAGE("Images"),
    AUDIO("Audio")
}

/** A single downloadable file resolved from a share link. */
data class MediaItem(
    val url: String,
    val kind: MediaKind
)

/** Full result of resolving a share URL: one or more downloadable items. */
data class MediaResult(
    val platform: Platform,
    val title: String,
    val items: List<MediaItem>,
    val audioUrl: String? = null
)

class MediaExtractionException(message: String) : Exception(message)

/** Detects which platform a shared URL belongs to. */
object UrlDetector {
    fun detect(url: String): Platform {
        val lower = url.lowercase()
        return if (lower.contains("tiktok.com")) Platform.TIKTOK else Platform.UNKNOWN
    }

    /** Pulls the first http(s) URL out of raw shared text or clipboard text. */
    fun extractUrlFromText(text: String): String? {
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        return if (matcher.find()) {
            val found = matcher.group()
            if (found.startsWith("http")) found else "https://$found"
        } else null
    }
}
