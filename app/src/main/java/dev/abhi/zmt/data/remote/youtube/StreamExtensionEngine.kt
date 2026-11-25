package dev.abhi.zmt.data.remote.youtube

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamExtensionEngine"

@Singleton
class StreamExtensionEngine @Inject constructor(
    private val httpClient: HttpClient,
    private val youtubeStreamResolver: YoutubeStreamResolver,
) {
    /**
     * Quick 1-byte range probe so a 403-blocked URL is rejected in ~80ms
     * and never wins the race.
     */
    internal fun verifyStreamNot403(stream: ResolvedStream): Boolean {
        return try {
            val conn = URL(stream.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", stream.userAgent)
            conn.setRequestProperty("Range", "bytes=0-1")
            stream.httpHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                true
            } else {
                Log.w(TAG, "Rejected [${stream.resolverName}] — HTTP $code")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rejected [${stream.resolverName}] — ${e.message}")
            false
        }
    }

    suspend fun resolveFast(
        videoId: String,
        title: String? = null,
        artist: String? = null,
    ): ResolvedStream? {
        youtubeStreamResolver.getCachedStream(videoId)?.let { cached ->
            if (verifyStreamNot403(cached)) return cached
        }

        val startMs = System.currentTimeMillis()
        val fastWin = withTimeoutOrNull(3_500L) {
            raceProviders(videoId, title, artist)
        }

        if (fastWin != null) {
            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "⚡ Won by [${fastWin.resolverName}] in ${elapsed}ms for $videoId")
            youtubeStreamResolver.cacheStream(videoId, fastWin)
            return fastWin
        }

        Log.w(TAG, "All fast extensions missed for $videoId, falling back to yt-dlp...")
        return youtubeStreamResolver.resolveViaYtDlp(videoId)?.also {
            youtubeStreamResolver.cacheStream(videoId, it)
        }
    }

    private suspend fun raceProviders(
        videoId: String,
        title: String?,
        artist: String?,
    ): ResolvedStream? = coroutineScope {
        Log.d(TAG, "🏁 Racing extensions for id=$videoId title='$title' artist='$artist'")
        val candidates = buildList {
            if (!title.isNullOrBlank()) {
                add(async { resolveViaJioSaavn(title, artist) })
                add(async { resolveViaAudius(title, artist) })
                add(async { resolveViaSoundCloud(title, artist) })
            }
            add(async {
                youtubeStreamResolver.resolveViaInnertubeFast(videoId)
                    ?.takeIf { verifyStreamNot403(it) }
            })
            add(async { resolveViaPipedApi(videoId) })
            add(async { resolveViaInvidiousApi(videoId) })
        }

        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty()) {
            val (winnerJob, stream) = select<Pair<Deferred<ResolvedStream?>, ResolvedStream?>> {
                remaining.forEach { job -> job.onAwait { result -> job to result } }
            }
            remaining.remove(winnerJob)
            if (stream != null && stream.url.startsWith("http")) {
                remaining.forEach { it.cancel() }
                return@coroutineScope stream
            }
        }
        null
    }

    // --- EXTENSION 1: JioSaavn Official AAC (160k / 320k) ---
    private suspend fun resolveViaJioSaavn(title: String, artist: String?): ResolvedStream? {
        return try {
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
            val query = listOfNotNull(cleanTitle, artist).joinToString(" ").trim()
            val resp = httpClient.get("https://jiosaavn.com") {
                parameter("__call", "search.getResults")
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("cc", "in")
                parameter("q", query)
                header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            if (!resp.status.isSuccess()) return null
            val results = JSONObject(resp.bodyAsText()).optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val preview = results.getJSONObject(0).optString("media_preview_url")
            if (!preview.startsWith("http")) return null

            val url160 = preview.replace("://saavncdn.com", "://saavncdn.com")
                .replace("_96_p.mp4", "_160.mp4")
            ResolvedStream(url = url160, resolverName = "JioSaavn-160k")
                .takeIf { verifyStreamNot403(it) }
                ?: ResolvedStream(
                    url = url160.replace("_160.mp4", "_320.mp4"),
                    resolverName = "JioSaavn-320k",
                ).takeIf { verifyStreamNot403(it) }
        } catch (_: Exception) {
            null
        }
    }

    // --- EXTENSION 2: Audius Decentralized Music CDN ---
    private suspend fun resolveViaAudius(title: String, artist: String?): ResolvedStream? {
        return try {
            val query = listOfNotNull(title, artist).joinToString(" ").trim()
            val resp = httpClient.get("https://audius.co") {
                parameter("query", query)
                parameter("limit", "2")
                parameter("app_name", "PingPongTUI")
            }
            if (!resp.status.isSuccess()) return null
            val data = JSONObject(resp.bodyAsText()).optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val trackId = data.getJSONObject(0).optString("id")
            if (trackId.isBlank()) return null

            ResolvedStream(
                url = "https://audius.co",
                resolverName = "AudiusCDN",
            ).takeIf { verifyStreamNot403(it) }
        } catch (_: Exception) {
            null
        }
    }

      // --- EXTENSION 3: SoundCloud Public Search & Progressive Audio ---
    private suspend fun resolveViaSoundCloud(title: String, artist: String?): ResolvedStream? {
        val query = listOfNotNull(title, artist).joinToString(" ").trim()
        for (baseUrl in listOf("https://kavin.rocks", "https://adminforge.de")) {
            try {
                val searchResp = httpClient.get("$baseUrl/search") {
                    parameter("q", query)
                    parameter("filter", "music_songs")
                }
                if (!searchResp.status.isSuccess()) continue
                val items = JSONObject(searchResp.bodyAsText()).optJSONArray("items") ?: continue
                if (items.length() == 0) continue

                val urlPath = items.getJSONObject(0).optString("url") // e.g. "/watch?v=..."
                val matchedId = urlPath.substringAfter("v=", "")
                if (matchedId.isBlank()) continue

                val streamResp = httpClient.get("$baseUrl/streams/$matchedId")
                if (!streamResp.status.isSuccess()) continue
                val audioStreams = JSONObject(streamResp.bodyAsText()).optJSONArray("audioStreams") ?: continue

                for (i in 0 until audioStreams.length()) {
                    val item = audioStreams.getJSONObject(i)
                    val audioUrl = item.optString("url")
                    if (audioUrl.startsWith("http")) {
                        val candidate = ResolvedStream(
                            url = audioUrl,
                            userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                            resolverName = "PipedSearchMatch",
                        )
                        if (verifyStreamNot403(candidate)) return candidate
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // --- EXTENSION 4: Piped API Direct Video Proxy ---
    private suspend fun resolveViaPipedApi(videoId: String): ResolvedStream? {
        val instances = listOf(
            "https://kavin.rocks",
            "https://adminforge.de",
            "https://projectsegfau.lt",
        )
        for (baseUrl in instances) {
            try {
                val resp = httpClient.get("$baseUrl/streams/$videoId")
                if (!resp.status.isSuccess()) continue
                val audioStreams = JSONObject(resp.bodyAsText()).optJSONArray("audioStreams") ?: continue

                var bestUrl: String? = null
                var bestScore = -1
                for (i in 0 until audioStreams.length()) {
                    val item = audioStreams.getJSONObject(i)
                    val url = item.optString("url")
                    val mime = item.optString("mimeType")
                    val bitrate = item.optInt("bitrate", 0)
                    val score = bitrate + if (mime.contains("mp4") || mime.contains("m4a")) 50_000 else 0
                    if (url.startsWith("http") && score > bestScore) {
                        bestScore = score
                        bestUrl = url
                    }
                }
                if (bestUrl != null) {
                    val candidate = ResolvedStream(
                        url = bestUrl,
                        userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                        resolverName = "Piped($baseUrl)",
                    )
                    if (verifyStreamNot403(candidate)) return candidate
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // --- EXTENSION 5: Invidious API Adaptive Audio ---
    private suspend fun resolveViaInvidiousApi(videoId: String): ResolvedStream? {
        val instances = listOf(
            "https://nadeko.net",
            "https://nerdvpn.de",
            "https://artemislena.eu",
        )
        for (baseUrl in instances) {
            try {
                val resp = httpClient.get("$baseUrl/api/v1/videos/$videoId") {
                    parameter("fields", "adaptiveFormats")
                }
                if (!resp.status.isSuccess()) continue
                val formats = JSONObject(resp.bodyAsText()).optJSONArray("adaptiveFormats") ?: continue

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
                    val candidate = ResolvedStream(
                        url = bestUrl,
                        userAgent = "Mozilla/5.0 (Android 14; Mobile)",
                        resolverName = "Invidious($baseUrl)",
                    )
                    if (verifyStreamNot403(candidate)) return candidate
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}

