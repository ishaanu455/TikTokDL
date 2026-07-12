package com.example.tiktokdl.extractors

/** Single entry point the rest of the app calls. TikTok links only. */
object MediaResolver {
    fun resolve(url: String): MediaResult {
        return when (UrlDetector.detect(url)) {
            Platform.TIKTOK -> TikTokExtractor.resolve(url)
            Platform.UNKNOWN ->
                throw MediaExtractionException("That doesn't look like a TikTok link.")
        }
    }
}
