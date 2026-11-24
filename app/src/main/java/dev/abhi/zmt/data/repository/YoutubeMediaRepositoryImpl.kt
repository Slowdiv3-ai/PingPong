package dev.abhi.zmt.data.repository

import android.net.Uri
import android.util.Log
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.data.remote.youtube.innertube.models.BrowseResponse
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.BrowseBody
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.NextBody
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.SearchBody
import dev.abhi.zmt.data.remote.youtube.innertube.requests.discoverPage
import dev.abhi.zmt.data.remote.youtube.innertube.requests.nextPage
import dev.abhi.zmt.data.remote.youtube.innertube.requests.playlistPage
import dev.abhi.zmt.data.remote.youtube.innertube.requests.searchPage
import dev.abhi.zmt.data.remote.youtube.innertube.utils.from
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.domain.repository.MediaRepository
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import javax.inject.Inject
import javax.inject.Singleton

data class YtHomePlaylist(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
)

data class YtHomeArtist(
    val name: String,
    val browseId: String?,
    val coverUrl: String?,
)

data class YtHomeFeed(
    val playlists: List<YtHomePlaylist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val artists: List<YtHomeArtist> = emptyList(),
)

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

    /** Fetches personalized/explore YouTube Music Home feed (playlists, tracks, artists). */
    suspend fun homeFeed(): YtHomeFeed {
        return try {
            val playlists = mutableListOf<YtHomePlaylist>()
            val tracks = mutableListOf<Track>()
            val artists = mutableListOf<YtHomeArtist>()

            // 1. Fetch FEmusic_home (personalized when signed in with cookies, curated otherwise)
            runCatching {
                val resp = Innertube.client.post("/youtubei/v1/browse") {
                    setBody(BrowseBody(browseId = "FEmusic_home"))
                }.body<BrowseResponse>()

                val sections = resp.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs?.firstOrNull()
                    ?.tabRenderer?.content
                    ?.sectionListRenderer?.contents
                    .orEmpty()

                for (section in sections) {
                    val shelf = section.musicCarouselShelfRenderer ?: continue
                    val items = shelf.contents.orEmpty()
                    for (item in items) {
                        val twoRow = item.musicTwoRowItemRenderer
                        if (twoRow != null) {
                            val browse = twoRow.navigationEndpoint?.browseEndpoint
                            val watch = twoRow.navigationEndpoint?.watchEndpoint
                            val title = twoRow.title?.text ?: continue
                            val subtitle = twoRow.subtitle?.runs?.joinToString("") { it.text.orEmpty() }.orEmpty()
                            val thumb = twoRow.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url

                            when {
                                browse?.browseId != null && (browse.browseId.startsWith("VL") || browse.browseId.startsWith("MPRE") || browse.browseId.startsWith("RD")) -> {
                                    playlists.add(YtHomePlaylist(browse.browseId, title, subtitle, thumb))
                                }
                                browse?.browseId != null && browse.browseId.startsWith("UC") -> {
                                    artists.add(YtHomeArtist(title, browse.browseId, thumb))
                                }
                                watch?.videoId != null -> {
                                    val vid = watch.videoId
                                    tracks.add(
                                        Track(
                                            id = vid.hashCode().toLong(),
                                            uri = Uri.parse("youtube://video/$vid"),
                                            title = title,
                                            artist = subtitle.ifBlank { "YouTube Music" },
                                            album = "YouTube",
                                            path = "youtube://video/$vid",
                                            durationMs = 0L,
                                            mime = "audio/mpeg",
                                            bitrate = 0,
                                            size = 0L,
                                            trackNumber = 0,
                                            dateAdded = System.currentTimeMillis(),
                                            dateModified = System.currentTimeMillis(),
                                            coverUri = thumb?.let { Uri.parse(it) },
                                            source = TrackSource.YOUTUBE,
                                            remoteId = vid,
                                        )
                                    )
                                }
                            }
                        }
                        val listItem = item.musicResponsiveListItemRenderer
                        if (listItem != null) {
                            Innertube.SongItem.from(listItem)?.toTrack()?.let { tracks.add(it) }
                        }
                    }
                }
            }

            // 2. Also pull FEmusic_explore trending songs & new releases so logged-out home is always rich
            val discover = Innertube.discoverPage()?.getOrNull()
            discover?.trending?.songs?.mapNotNull { it.toTrack() }?.forEach { t ->
                if (tracks.none { it.remoteId == t.remoteId }) tracks.add(t)
            }
            discover?.newReleaseAlbums?.forEach { album ->
                val bId = album.info?.endpoint?.browseId ?: return@forEach
                val name = album.info.name ?: return@forEach
                if (playlists.none { it.browseId == bId }) {
                    playlists.add(
                        YtHomePlaylist(
                            browseId = bId,
                            title = name,
                            subtitle = album.authors?.firstOrNull()?.name ?: "Album",
                            coverUrl = album.thumbnail?.url,
                        )
                    )
                }
            }

            // 3. Derive artists from recommended tracks if FEmusic_home didn't return artist cards
            if (artists.isEmpty()) {
                tracks
                    .map { it.artist to it.coverUri?.toString() }
                    .filter { it.first.isNotBlank() && it.first != "Unknown Artist" }
                    .distinctBy { it.first.lowercase() }
                    .take(12)
                    .forEach { (name, cover) ->
                        artists.add(YtHomeArtist(name = name, browseId = null, coverUrl = cover))
                    }
            }

            YtHomeFeed(
                playlists = playlists.distinctBy { it.browseId }.take(16),
                tracks = tracks.distinctBy { it.remoteId }.take(20),
                artists = artists.take(12),
            )
        } catch (e: Exception) {
            Log.e(TAG, "homeFeed failed: ${e.message}")
            YtHomeFeed()
        }
    }

    /** Loads all playable tracks inside a YouTube Music playlist or album browseId. */
    suspend fun playlistTracks(browseId: String): List<Track> {
        return try {
            val normalizedId = if (browseId.startsWith("PL") || browseId.startsWith("OLAK")) "VL$browseId" else browseId
            val page = Innertube.playlistPage(BrowseBody(browseId = normalizedId))?.getOrNull()
            page?.songsPage?.items?.mapNotNull { it.toTrack() }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "playlistTracks failed for '$browseId': ${e.message}")
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
