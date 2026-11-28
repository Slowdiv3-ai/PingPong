package dev.abhi.zmt.data.remote.youtube

import android.util.Log
import dev.abhi.zmt.data.remote.youtubedl.YouTubeDLBridge
import dev.abhi.zmt.data.remote.youtubedl.YouTubeDLResponse
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.data.remote.youtube.innertube.models.Context
import dev.abhi.zmt.data.remote.youtube.innertube.models.UserAgents
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.PlayerBody
import dev.abhi.zmt.data.remote.youtube.innertube.requests.player
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YoutubeStreamResolver"
private const val MAX_ATTEMPTS = 5
private const val RETRY_DELAY_MS = 200L

/**
 * Shared Innertube client contexts ordered by reliability.
 * VisionOS and AndroidVR bypass cipher; others are fallbacks.
 * Used by both [resolve] and [resolveAll].
 */
private val INNERTUBE_CONTEXTS = listOf(
    Context.DefaultAndroidMusic,
    Context.DefaultTV,
    Context.DefaultWeb,
)


private val PIPED_INSTANCES = listOf(
    "https://kavin.rocks",
    "https://adminforge.de",
    "https://projectsegfau.lt",
)


data class ResolvedStream(
    val url: String,
    val userAgent: String = UserAgents.IOS,
    val contentLength: Long = 0L,
    val resolverName: String = "unknown",
    val httpHeaders: Map<String, String> = emptyMap(),
)

@Singleton
class YoutubeStreamResolver @Inject constructor(
    private val youTubeDLBridge: YouTubeDLBridge,
) {
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ResolvedStream>>()

    fun getCachedStream(videoId: String): ResolvedStream? {
        val entry = urlCache[videoId] ?: return null
        if (System.currentTimeMillis() - entry.first > 30 * 60 * 1000L) {
            urlCache.remove(videoId)
            return null
        }
        return entry.second
    }

    fun cacheStream(videoId: String, stream: ResolvedStream) {
        urlCache[videoId] = System.currentTimeMillis() to stream
    }

    fun invalidateCache(videoId: String) {
        urlCache.remove(videoId)
    }

        suspend fun resolveViaInnertubeFast(
        videoId: String,
        preferHighQuality: Boolean = false
    ): ResolvedStream? {
        for (context in INNERTUBE_CONTEXTS) {
            if (!currentCoroutineContext().isActive) return null
            val stream = resolveViaInnertubeContext(videoId, context, preferHighQuality)
            if (stream != null) return stream
        }
        return null
    }

        suspend fun resolve(videoId: String, preferHighQuality: Boolean = false): ResolvedStream? {
        if (!preferHighQuality) {
            getCachedStream(videoId)?.let { return it }
        }

        // 1. Fast native Innertube (~0.3s)
        resolveViaInnertubeFast(videoId, preferHighQuality)?.let {
            if (!preferHighQuality) cacheStream(videoId, it)
            return it
        }

        // 2. Fast Piped fallback
        resolveViaPiped(videoId)?.let {
            if (!preferHighQuality) cacheStream(videoId, it)
            return it
        }

        // 3. Slow yt-dlp last resort (3-5s)
        resolveViaYtDlp(videoId)?.let {
            if (!preferHighQuality) cacheStream(videoId, it)
            return it
        }

        Log.e(TAG, "All resolvers failed for $videoId")
        return null
    }

    private suspend fun resolveViaPiped(videoId: String): ResolvedStream? {
        for (instance in PIPED_INSTANCES) {
            if (!currentCoroutineContext().isActive) return null
            try {
                val raw = Innertube.baseClient.get("$instance/streams/$videoId").bodyAsText()
                val root = Json.parseToJsonElement(raw).jsonObject
                val audioStreams = root["audioStreams"]?.jsonArray ?: continue

                val best = audioStreams
                    .map { it.jsonObject }
                    .filter { it["url"]?.jsonPrimitive?.contentOrNull != null }
                    .maxByOrNull { it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0 }
                    ?: continue

                val url = best["url"]!!.jsonPrimitive.content
                val length = best["contentLength"]?.jsonPrimitive?.longOrNull ?: 0L

                Log.d(TAG, "Piped($instance): resolved stream for $videoId")
                return ResolvedStream(
                    url = url,
                    userAgent = UserAgents.DESKTOP,
                    contentLength = length,
                    resolverName = "Piped($instance)",
                )
            } catch (e: Exception) {
                Log.w(TAG, "Piped($instance) failed for $videoId: ${e.message}")
            }
        }
        return null
    }


    suspend fun resolveAll(videoId: String): List<ResolvedStream> {
        getCachedStream(videoId)?.let { return listOf(it) }

        val results = mutableListOf<ResolvedStream>()

        // Try yt-dlp first since it handles YouTube 403/PO-token workarounds cleanly
        val ytdl = resolveViaYtDlp(videoId)
        if (ytdl != null) {
            urlCache[videoId] = System.currentTimeMillis() to ytdl
            results.add(ytdl)
        }


        // Only query Innertube if yt-dlp returned nothing
        if (results.isEmpty()) {
            for (context in INNERTUBE_CONTEXTS) {
                if (!currentCoroutineContext().isActive) break
                val stream = resolveViaInnertubeContext(videoId, context)
                if (stream != null) {
                    results.add(stream)
                    break
                }
            }
        }

        Log.d(TAG, "resolveAll($videoId): ${results.size} options")
        return results
    }

    suspend fun resolveViaYtDlp(videoId: String): ResolvedStream? {
        if (!youTubeDLBridge.isReady()) {
            Log.w(TAG, "yt-dlp bridge not ready")
            return null
        }

        return try {
            val jsonStr = youTubeDLBridge.runDownload(videoId)
            if (jsonStr == null) {
                Log.w(TAG, "yt-dlp returned null for $videoId")
                return null
            }

            val response = YouTubeDLResponse.fromString(jsonStr)

            if (response.hasError) {
                Log.w(TAG, "yt-dlp error for $videoId: ${response.error}")
                return null
            }

            if (response.id != videoId) {
                Log.w(TAG, "yt-dlp returned wrong video ID: ${response.id}")
                return null
            }
            var url: String? = null
            var formatId: String? = null
            var fileSize = 0L
            var headers = emptyMap<String, String>()
            if (response.formats != null) {
                val validFormats = response.formats.filter { fmt ->
                    val id = fmt.formatId.orEmpty()
                    val ext = fmt.ext.orEmpty()
                    fmt.url != null &&
                        !id.startsWith("sb") &&
                        ext != "mhtml"
                }
                val audioFormats = validFormats.filter { it.isAudioOnly }
                val bestAudio = audioFormats.maxByOrNull { it.audioBitrate ?: 0.0 }

                if (bestAudio != null) {
                    url = bestAudio.url
                    formatId = bestAudio.formatId
                    fileSize = bestAudio.fileSize ?: 0L
                    headers = bestAudio.httpHeaders ?: emptyMap()
                    Log.d(TAG, "yt-dlp: picked audio format ${bestAudio.formatId} (${bestAudio.audioBitrate}kbps)")
                } else {
                    val anyFormat = validFormats.firstOrNull()
                    if (anyFormat != null) {
                        url = anyFormat.url
                        formatId = anyFormat.formatId
                        fileSize = anyFormat.fileSize ?: 0L
                        headers = anyFormat.httpHeaders ?: emptyMap()
                        Log.d(TAG, "yt-dlp: picked format ${anyFormat.formatId}")
                    } else if (!response.formatId.orEmpty().startsWith("sb")) {
                        url = response.url
                        formatId = response.formatId
                        fileSize = response.fileSize
                    }
                }
            }


            if (url == null) {
                Log.w(TAG, "yt-dlp returned no URL for $videoId (${response.formats?.size ?: 0} formats)")
                return null
            }

            val userAgent = headers["User-Agent"] ?: UserAgents.DESKTOP

            Log.d(TAG, "yt-dlp success: format=$formatId, size=$fileSize")
            ResolvedStream(
                url = url,
                userAgent = userAgent,
                contentLength = fileSize,
                resolverName = "yt-dlp($formatId)",
                httpHeaders = headers,
            )
        } catch (e: Exception) {
            Log.e(TAG, "yt-dlp failed for $videoId: ${e.message}")
            null
        }
    }

        private suspend fun resolveViaInnertubeContext(
        videoId: String,
        context: Context,
        preferHighQuality: Boolean = false,
    ): ResolvedStream? {
        val label = context.client.clientName
        return try {
            val result = Innertube.player(
                body = PlayerBody(videoId = videoId, context = context),
                checkIsValid = false,
            )
            val response = result?.getOrNull() ?: return null
            val streamingData = response.streamingData ?: return null
            val adaptive = streamingData.adaptiveFormats.orEmpty()
            val progressive = streamingData.formats.orEmpty()

            val audioFormats = adaptive.filter { it.mimeType.startsWith("audio/") }
            val format = if (preferHighQuality) {
                audioFormats.filter { it.url != null && it.mimeType.contains("mp4") }.maxByOrNull { it.bitrate ?: 0 }
                    ?: audioFormats.filter { it.url != null }.maxByOrNull { it.bitrate ?: 0 }
                    ?: progressive.find { it.url != null }
                    ?: return null
            } else {
                audioFormats.find { it.url != null && it.itag == 140 }
                    ?: audioFormats.find { it.url != null && it.itag == 251 }
                    ?: audioFormats.find { it.url != null }
                    ?: progressive.find { it.url != null && it.itag == 18 }
                    ?: progressive.find { it.url != null }
                    ?: return null
            }
            val url = format.url
            if (url == null) {
                Log.d(TAG, "Innertube($label): format has signatureCipher only, skipping")
                return null
            }

            // Reject any URL that downgraded to c=IOS (which triggers 403 on full downloads)
            if (url.contains("c=IOS")) {
                Log.d(TAG, "Innertube($label): skipping c=IOS URL to avoid 403")
                return null
            }

            val userAgent = when {
                url.contains("c=ANDROID_MUSIC") -> UserAgents.ANDROID_MUSIC
                url.contains("c=ANDROID") -> "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
                url.contains("c=TVHTML5") -> UserAgents.TV
                label == "ANDROID_MUSIC" -> UserAgents.ANDROID_MUSIC
                label == "TVHTML5" -> UserAgents.TV
                else -> "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
            }

            Log.d(TAG, "Innertube($label): ${format.mimeType} itag=${format.itag} (${format.bitrate}kbps)")
            ResolvedStream(
                url = url,
                userAgent = userAgent,
                contentLength = format.contentLength ?: 0L,
                resolverName = "Innertube($label)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Innertube($label) failed for $videoId: ${e.message}")
            null
        }
    }

}
