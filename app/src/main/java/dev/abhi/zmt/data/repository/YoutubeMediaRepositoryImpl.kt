package dev.abhi.zmt.data.repository

import android.net.Uri
import android.util.Log
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.NextBody
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.SearchBody
import dev.abhi.zmt.data.remote.youtube.innertube.requests.discoverPage
import dev.abhi.zmt.data.remote.youtube.innertube.requests.nextPage
import dev.abhi.zmt.data.remote.youtube.innertube.requests.searchPage
import dev.abhi.zmt.data.remote.youtube.innertube.utils.from
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.domain.repository.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YoutubeMediaRepo"

@Singleton
class YoutubeMediaRepositoryImpl @Inject constructor(
    private val likedTracks: LikedTracksRepository,
) : MediaRepository {

    suspend fun search(query: String): List<Track> {
        return try {
            val body = SearchBody(
                query = query,
                params = Innertube.SearchFilter.Song.value,
            )
            val result: Innertube.ItemsPage<Innertube.SongItem>? = Innertube.searchPage(
                body = body,
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from,
            )?.getOrNull()

            val songs = result?.items
            Log.d(TAG, "Found ${songs?.size ?: 0} results for '$query'")

            songs?.mapNotNull { song: Innertube.SongItem ->
                val videoId = song.info?.endpoint?.videoId ?: return@mapNotNull null
                val thumbUrl = song.thumbnail?.hd()
                Track(
                    id = videoId.hashCode().toLong(),
                    uri = Uri.parse("youtube://video/$videoId"),
                    title = song.info.name ?: "Unknown",
                    artist = song.authors?.firstOrNull()?.name ?: "Unknown Artist",
                    album = song.album?.name ?: "YouTube",
                    path = "youtube://video/$videoId",
                    durationMs = song.durationText?.let { parseDuration(it) } ?: 0L,
                    mime = "audio/mpeg",
                    bitrate = 0,
                    size = 0L,
                    trackNumber = 0,
                    dateAdded = System.currentTimeMillis(),
                    dateModified = System.currentTimeMillis(),
                    coverUri = thumbUrl?.let { Uri.parse(it) },
                    source = TrackSource.YOUTUBE,
                    remoteId = videoId,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /** Fetches similar/radio tracks for a YouTube videoId using Innertube automix. */
    suspend fun radioFor(videoId: String): List<Track> {
        return try {
            val next = Innertube.nextPage(NextBody(videoId = videoId))?.getOrNull()
            val items = next?.itemsPage?.items.orEmpty()
            items
                .mapNotNull { it.toTrack() }
                .filter { it.remoteId != videoId }
        } catch (e: Exception) {
            Log.e(TAG, "Radio fetch failed for '$videoId': ${e.message}")
            emptyList()
        }
    }

    /** Fetches trending/explore songs for the YouTube Home feed. */
    suspend fun trendingSongs(): List<Track> {
        return try {
            val page = Innertube.discoverPage()?.getOrNull()
            page?.trending?.songs?.mapNotNull { it.toTrack() }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Discover fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun Innertube.SongItem.toTrack(): Track? {
        val videoId = info?.endpoint?.videoId ?: return null
        return Track(
            id = videoId.hashCode().toLong(),
            uri = Uri.parse("youtube://video/$videoId"),
            title = info.name ?: "Unknown",
            artist = authors?.firstOrNull()?.name ?: "Unknown Artist",
            album = album?.name ?: "YouTube",
            path = "youtube://video/$videoId",
            durationMs = durationText?.let { parseDuration(it) } ?: 0L,
            mime = "audio/mpeg",
            bitrate = 0,
            size = 0L,
            trackNumber = 0,
            dateAdded = System.currentTimeMillis(),
            dateModified = System.currentTimeMillis(),
            coverUri = thumbnail?.hd()?.let { Uri.parse(it) },
            source = TrackSource.YOUTUBE,
            remoteId = videoId,
        )
    }

    override suspend fun scan(): List<Track> =
        likedTracks.all()
            .filter { it.source == TrackSource.YOUTUBE }
            .sortedBy { it.title.lowercase() }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3_600_000 + parts[1] * 60_000 + parts[2] * 1_000
            2 -> parts[0] * 60_000 + parts[1] * 1_000
            1 -> parts[0] * 1_000
            else -> 0L
        }
    }
}
