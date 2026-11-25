package dev.abhi.zmt.data.remote.youtube

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamExtensionEngine"

/**
 * Built-in Spoti-style multi-provider stream engine.
 * Races fast native Ktor providers in parallel (~0.3s) and falls back to yt-dlp only as a last resort.
 */
@Singleton
class StreamExtensionEngine @Inject constructor(
    private val httpClient: HttpClient,
    private val youtubeStreamResolver: YoutubeStreamResolver,
) {
    // Real Piped API endpoints (not frontend HTML pages)
    private val pipedApiInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.projectsegfau.lt",
    )

    // Fast public Invidious API endpoints
    private val invidiousInstances = listOf(
        "https://nadeko.net",
        "https://nerdvpn.de",
        "https://artemislena.eu",
    )

    /**
     * Resolve a playable audio stream in <0.4s by racing native extensions in parallel.
     * Pass [title] and [artist] when available to unlock Spoti-style metadata matching (JioSaavn).
     */
    suspend fun resolveFast(
        videoId: String,
        title: String? = null,
        artist: String? = null,
    ): ResolvedStream? {
        // 0. Instant in-memory cache hit (0ms)
        youtubeStreamResolver.getCachedStream(videoId)?.let { return it }

        val startMs = System.currentTimeMillis()

        // 1. Race fast native extensions in parallel (max 2.5s budget)
        val fastWin = withTimeoutOrNull(2_500L) {
            raceProviders(videoId, title, artist)
        }

        if (fastWin != null) {
            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "⚡ Won by [${fastWin.resolverName}] in ${elapsed}ms for $videoId")
            youtubeStreamResolver.cacheStream(videoId, fastWin)
            return fastWin
        }

        // 2. Last-resort Python yt-dlp fallback only if all fast extensions failed
        Log.w(TAG, "Fast extensions missed for $videoId, falling back to yt-dlp...")
        return youtubeStreamResolver.resolveViaYtDlp(videoId)?.also {
            youtubeStreamResolver.cacheStream(videoId, it)
        }
    }

    private suspend fun raceProviders(
        videoId: String,
        title: String?,
        artist: String?,
    ): ResolvedStream? = coroutineScope {
        val candidates = buildList {
            // Provider A: Direct YouTube Innertube (ANDROID_VR / IOS / TV)
            add(async { youtubeStreamResolver.resolveViaInnertubeFast(videoId) })

            // Provider B: Real Piped API JSON endpoints
            add(async { resolveViaPipedApi(videoId) })

            // Provider C: Invidious API JSON endpoints
            add(async { resolveViaInvidiousApi(videoId) })

            // Provider D: Spoti-style Metadata Extension (JioSaavn CDN) when title is known
            if (!title.isNullOrBlank()) {
                add(async { resolveViaJioSaavn(title, artist) })
            }
        }

        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty()) {
            val (winnerJob, stream) = select<Pair<kotlinx.coroutines.Deferred<ResolvedStream?>, ResolvedStream?>> {
                remaining.forEach { job ->
                    job.onAwait { result -> job to result }
                }
            }
            remaining.remove(winnerJob)
            if (stream != null && stream.url.startsWith("http")) {
                // Cancel slower racers immediately to save bandwidth & CPU
                remaining.forEach { it.cancel() }
                return@coroutineScope stream
            }
        }
        null
    }

    private suspend fun resolveViaPipedApi(videoId: String): ResolvedStream? {
        for (baseUrl in pipedApiInstances) {
            try {
                val resp = httpClient.get("$baseUrl/streams/$videoId")
                if (!resp.status.isSuccess()) continue
                val json = JSONObject(resp.bodyAsText())
                val audioStreams = json.optJSONArray("audioStreams") ?: continue

                var bestUrl: String? = null
                var bestBitrate = -1
                for (i in 0 until audioStreams.length()) {
                    val item = audioStreams.getJSONObject(i)
                    val url = item.optString("url")
                    val mime = item.optString("mimeType")
                    val bitrate = item.optInt("bitrate", 0)
                    // Prefer m4a/mp4 for fastest MediaExtractor hardware decoding
                    val score = bitrate + if (mime.contains("mp4") || mime.contains("m4a")) 50_000 else 0
                    if (url.startsWith("http") && score > bestBitrate) {
                        bestBitrate = score
                        bestUrl = url
                    }
                }
                if (bestUrl != null) {
                    return ResolvedStream(
                        url = bestUrl,
                        userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                        resolverName = "Piped($baseUrl)",
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun resolveViaInvidiousApi(videoId: String): ResolvedStream? {
        for (baseUrl in invidiousInstances) {
            try {
                val resp = httpClient.get("$baseUrl/api/v1/videos/$videoId") {
                    parameter("fields", "adaptiveFormats")
                }
                if (!resp.status.isSuccess()) continue
                val json = JSONObject(resp.bodyAsText())
                val formats = json.optJSONArray("adaptiveFormats") ?: continue

                var bestUrl: String? = null
                var bestScore = -1
                for (i in 0 until formats.length()) {
                    val item = formats.getJSONObject(i)
                    val type = item.optString("type")
                    if (!type.startsWith("audio/")) continue
                    val url = item.optString("url")
                    val bitrate = item.optString("bitrate").toIntOrNull() ?: 0
                    val score = bitrate + if (type.contains("mp4")) 50_000 else 0
                    if (url.startsWith("http") && score > bestScore) {
                        bestScore = score
                        bestUrl = url
                    }
                }
                if (bestUrl != null) {
                    return ResolvedStream(
                        url = bestUrl,
                        userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                        resolverName = "Invidious($baseUrl)",
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Spoti-style metadata extension: searches JioSaavn by song title + artist
     * and returns a direct unthrottled AAC/MP4 CDN stream URL.
     */
    private suspend fun resolveViaJioSaavn(title: String, artist: String?): ResolvedStream? {
        return try {
            val query = listOfNotNull(title, artist).joinToString(" ").trim()
            val resp = httpClient.get("https://saavn.dev") {
                parameter("query", query)
                parameter("limit", "3")
                header("Accept", "application/json")
            }
            if (!resp.status.isSuccess()) return null
            val root = JSONObject(resp.bodyAsText())
            val results = root.optJSONObject("data")?.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val firstMatch = results.getJSONObject(0)
            val downloadUrls = firstMatch.optJSONArray("downloadUrl") ?: return null
            // Pick highest quality available (usually 320kbps or 160kbps at the end of array)
            var streamUrl: String? = null
            for (i in downloadUrls.length() - 1 downTo 0) {
                val candidate = downloadUrls.getJSONObject(i).optString("url")
                if (candidate.startsWith("http")) {
                    streamUrl = candidate
                    break
                }
            }
            streamUrl?.let {
                ResolvedStream(
                    url = it,
                    userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                    resolverName = "JioSaavnExtension",
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
