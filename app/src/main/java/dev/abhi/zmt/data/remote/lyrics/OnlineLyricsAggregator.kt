package dev.abhi.zmt.data.remote.lyrics

import dev.abhi.zmt.data.remote.lrclib.LrclibApi
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.NextBody
import dev.abhi.zmt.data.remote.youtube.innertube.requests.lyrics
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.lyrics.KrcLyricsParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnlineLyricsAggregator @Inject constructor(
    private val client: HttpClient,
    private val lrclibApi: LrclibApi,
) {

    suspend fun fetchBestLyrics(track: Track): String? {
        val cleanTitle = track.title
            .replace(Regex("""\s*[\(\[](?:official|audio|video|lyrics|visualizer|hd|4k|feat\.?|ft\.?)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { track.title }
        val cleanArtist = track.artist
            .substringBefore(",")
            .substringBefore("&")
            .substringBefore("feat.")
            .substringBefore("ft.")
            .trim()
            .ifBlank { track.artist }

        // 1. Kugou Word-by-Word (KRC)
        fetchKugouKrc(cleanTitle, cleanArtist, track.durationMs)?.let { return it }

        // 2. NetEase Word-by-Word (YRC) + Synced (LRC)
        fetchNetease(cleanTitle, cleanArtist)?.let { return it }

        // 3. LRCLIB Exact (/api/get) + Fuzzy Search (/api/search)
        runCatching { lrclibApi.fetchLyrics(track) }.getOrNull()?.let { return it }
        fetchLrclibSearch(cleanTitle, cleanArtist)?.let { return it }

        // 4. YouTube Music Native Lyrics (Innertube)
        val videoId = track.remoteId ?: track.uri.toString().substringAfterLast("/").takeIf { it.length == 11 }
        if (!videoId.isNullOrBlank()) {
            runCatching { Innertube.lyrics(NextBody(videoId = videoId)).getOrNull() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // 5. Lyrics.ovh Plain Fallback
        fetchLyricsOvh(cleanTitle, cleanArtist)?.let { return it }

        return null
    }

    private suspend fun fetchKugouKrc(title: String, artist: String, durationMs: Long): String? = runCatching {
        val searchRes = client.get("https://kugou.com") {
            parameter("ver", "1")
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("keyword", "$title - $artist".trim(' ', '-'))
            if (durationMs > 0) parameter("duration", durationMs)
        }
        if (!searchRes.status.isSuccess()) return@runCatching null
        val candidates = JSONObject(searchRes.bodyAsText()).optJSONArray("candidates") ?: return@runCatching null
        if (candidates.length() == 0) return@runCatching null

        val first = candidates.getJSONObject(0)
        val id = first.optString("id").ifBlank { return@runCatching null }
        val key = first.optString("accesskey").ifBlank { return@runCatching null }

        val dlRes = client.get("https://kugou.com") {
            parameter("ver", "1")
            parameter("client", "pc")
            parameter("id", id)
            parameter("accesskey", key)
            parameter("fmt", "krc")
            parameter("charset", "utf8")
        }
        if (!dlRes.status.isSuccess()) return@runCatching null
        val encoded = JSONObject(dlRes.bodyAsText()).optString("content").ifBlank { return@runCatching null }
        KrcLyricsParser.decodeBase64Krc(encoded)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun fetchNetease(title: String, artist: String): String? = runCatching {
        val searchRes = client.get("https://163.com") {
            parameter("s", "$title $artist".trim())
            parameter("type", "1")
            parameter("limit", "5")
            header("Referer", "https://163.com")
        }
        if (!searchRes.status.isSuccess()) return@runCatching null
        val songs = JSONObject(searchRes.bodyAsText())
            .optJSONObject("result")
            ?.optJSONArray("songs") ?: return@runCatching null
        if (songs.length() == 0) return@runCatching null

        val songId = songs.getJSONObject(0).optLong("id", 0L)
        if (songId == 0L) return@runCatching null

        val lyricRes = client.get("https://163.comapi/song/lyric/v1") {
            parameter("id", songId)
            parameter("tv", "-1")
            parameter("lv", "-1")
            parameter("rv", "-1")
            parameter("kv", "-1")
            parameter("yv", "-1")
            header("Referer", "https://163.com")
        }
        if (!lyricRes.status.isSuccess()) return@runCatching null
        val root = JSONObject(lyricRes.bodyAsText())
        val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
        if (yrc.isNotBlank()) return@runCatching yrc

        val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        lrc.ifBlank { null }
    }.getOrNull()

    private suspend fun fetchLrclibSearch(title: String, artist: String): String? = runCatching {
        val res = client.get("https://lrclib.net") {
            parameter("track_name", title)
            if (artist.isNotBlank() && artist != "unknown artist") parameter("artist_name", artist)
        }
        if (!res.status.isSuccess()) return@runCatching null
        val arr = JSONArray(res.bodyAsText())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val synced = obj.optString("syncedLyrics")
            if (synced.isNotBlank()) return@runCatching synced
        }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val plain = obj.optString("plainLyrics")
            if (plain.isNotBlank()) return@runCatching plain
        }
        null
    }.getOrNull()

    private suspend fun fetchLyricsOvh(title: String, artist: String): String? = runCatching {
        if (artist.isBlank() || artist == "unknown artist") return@runCatching null
        val url = "https://lyrics.ovh{artist.encodeURLPathPart()}/${title.encodeURLPathPart()}"
        val res = client.get(url)
        if (!res.status.isSuccess()) return@runCatching null
        JSONObject(res.bodyAsText()).optString("lyrics").ifBlank { null }
    }.getOrNull()
}
