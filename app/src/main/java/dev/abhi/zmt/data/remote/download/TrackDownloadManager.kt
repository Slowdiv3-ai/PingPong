package dev.abhi.zmt.data.remote.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.abhi.zmt.data.remote.youtube.YoutubeStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

private const val TAG = "TrackDL"
private const val BUFFER_SIZE = 65536  // 64 KB chunks — much faster than 8KB

data class DownloadProgress(
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val isFinished: Boolean = false,
    val error: String? = null,
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                 else if (isFinished) 100 else if (error != null) -1 else 0
}

@Singleton
class TrackDownloadManager @Inject constructor(
    private val resolver: YoutubeStreamResolver,
) : CoroutineScope {

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.IO

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // longer read timeout for large files
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Download a YouTube track to the device's Music directory.
     * Runs on a dedicated IO scope — NOT tied to any ViewModel lifecycle.
     */
    fun downloadToDevice(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        album: String = "",
        lyricsText: String? = null,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        // Dispatch progress updates to Main thread for UI safety
        val mainProgress: (DownloadProgress) -> Unit = { p ->
            kotlinx.coroutines.MainScope().launch { onProgress(p) }
        }
        launch {
            mainProgress(DownloadProgress())
            Log.i(TAG, "Starting download for $videoId")

             // 1. Resolve stream URL via yt-dlp (runs on IO)
            val resolved = resolver.resolve(videoId, preferHighQuality = true)
                ?: resolver.resolve(videoId, preferHighQuality = false)
            if (resolved == null) {
                mainProgress(DownloadProgress(error = "Could not resolve stream URL"))
                return@launch
            }

            Log.i(TAG, "URL: ${resolved.url.take(80)}... UA: ${resolved.userAgent}")

            // 2. HTTP request with exact User-Agent from resolver
            val request = Request.Builder()
                .url(resolved.url)
                .header("User-Agent", resolved.userAgent)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .header("Accept-Encoding", "identity")  // don't let OkHttp gzip — we want raw bytes
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                mainProgress(DownloadProgress(error = "HTTP ${response.code}: ${response.message}"))
                return@launch
            }

            val body = response.body ?: run {
                mainProgress(DownloadProgress(error = "Empty response body"))
                return@launch
            }

            // 3. Read in chunks, report progress
            val totalLen = body.contentLength()  // -1 for chunked streams
            val contentType = body.contentType()?.toString() ?: "audio/webm"

            val extension = when {
                contentType.contains("mp4") -> "m4a"
                contentType.contains("webm") -> "webm"
                contentType.contains("ogg") -> "ogg"
                contentType.contains("aac") -> "aac"
                else -> "m4a"
            }
            val mimeType = when {
                contentType.contains("mp4") -> "audio/mp4"
                contentType.contains("webm") -> "audio/webm"
                contentType.contains("ogg") -> "audio/ogg"
                contentType.contains("aac") -> "audio/aac"
                else -> "audio/mp4"
            }

            val fileName = "${title} - ${artist}.${extension}"
                .replace(Regex("[/\\\\:*?\"<>|]"), "_")

// 4. Download stream to temporary cache file first
val tempFile = java.io.File(context.cacheDir, "dl_${System.currentTimeMillis()}.$extension")
try {
    val totalBytes = body.contentLength()
    var bytesReadTotal = 0L
    body.byteStream().use { input ->
        tempFile.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                bytesReadTotal += bytesRead
                if (totalBytes > 0) {
                    mainProgress(DownloadProgress(bytesDownloaded = bytesReadTotal, totalBytes = totalBytes))
                }
            }
        }
    }

    // 5. Write MP4/OGG metadata tags directly into tempFile
    val tagMap = buildMap {
        put("TITLE", title)
        if (artist.isNotBlank() && artist != "Unknown Artist") {
            put("ARTIST", artist)
            put("ALBUMARTIST", artist)
        }
        if (album.isNotBlank()) put("ALBUM", album)
        if (!lyricsText.isNullOrBlank()) put("LYRICS", lyricsText)
    }
    dev.jyotiraditya.metadata.AudioTags.write(tempFile, context.cacheDir, tagMap)

    // 6. Save tagged audio file into MediaStore.Audio.Media
    val resolver_ = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(android.provider.MediaStore.Audio.Media.TITLE, title)
        put(android.provider.MediaStore.Audio.Media.ARTIST, artist)
        if (album.isNotBlank()) put(android.provider.MediaStore.Audio.Media.ALBUM, album)
        put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC)
        put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
    }

    val collection = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val itemUri = resolver_.insert(collection, values) ?: throw IllegalStateException("Failed to create MediaStore entry")

    resolver_.openOutputStream(itemUri)?.use { out ->
        tempFile.inputStream().use { inp -> inp.copyTo(out) }
    }

    values.clear()
    values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
    resolver_.update(itemUri, values, null, null)

    // 7. Save matching .lrc sidecar file if lyrics exist
    if (!lyricsText.isNullOrBlank()) {
        val lrcName = "${title} - ${artist}.lrc".replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val lrcValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, lrcName)
            put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC)
            put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        val filesUri = android.provider.MediaStore.Files.getContentUri("external")
        resolver_.insert(filesUri, lrcValues)?.let { lrcUri ->
            resolver_.openOutputStream(lrcUri)?.use { out ->
                out.write(lyricsText.toByteArray(Charsets.UTF_8))
            }
            lrcValues.clear()
            lrcValues.put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 0)
            resolver_.update(lrcUri, lrcValues, null, null)
        }
    }
    Log.i(TAG, "Saved to Music: $fileName")
    mainProgress(DownloadProgress(bytesDownloaded = totalBytes.coerceAtLeast(0), totalBytes = totalBytes.coerceAtLeast(0), isFinished = true))
} catch (e: Exception) {
    Log.e(TAG, "Download failed: ${e.message}", e)
    mainProgress(DownloadProgress(error = "Download failed: ${e.message}"))
} finally {
    tempFile.delete()
}

        }
    }

}
