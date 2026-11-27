package dev.abhi.zmt.data.repository

import android.net.Uri
import android.util.Log
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.data.remote.youtube.innertube.models.BrowseResponse
import dev.abhi.zmt.data.remote.youtube.innertube.models.bodies.ContinuationBody
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

data class YtHomeShelf(
    val title: String,
    val playlists: List<YtHomePlaylist>,
)

data class YtHomeFeed(
    val playlists: List<YtHomePlaylist> = emptyList(),
    val shelves: List<YtHomeShelf> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val artists: List<YtHomeArtist> = emptyList(),
)

data class YtPlaylistDetail(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
)

data class YtArtistDetail(
    val browseId: String,
    val name: String,
    val coverUrl: String? = null,
    val topSongs: List<Track> = emptyList(),
    val albums: List<YtHomePlaylist> = emptyList(),
    val playlists: List<YtHomePlaylist> = emptyList(),
    val similarArtists: List<YtHomeArtist> = emptyList(),
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
            var result: Innertube.ItemsPage<Innertube.SongItem>? = Innertube.searchPage(
                body = body,
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from,
            )?.onFailure { err ->
                Log.e(TAG, "searchPage error for '$query': ${err.message}", err)
            }?.getOrNull()

            if (result?.items.isNullOrEmpty() && Innertube.cookie != null) {
                val saved = Innertube.cookie
                Innertube.cookie = null
                result = Innertube.searchPage(
                    body = body,
                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from,
                )?.onFailure { err ->
                    Log.e(TAG, "searchPage retry error for '$query': ${err.message}", err)
                }?.getOrNull()
                Innertube.cookie = saved
            }


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

    /** Fetches similar/radio tracks for a YouTube videoId using YouTube Music studio automix. */
suspend fun radioFor(videoId: String): List<Track> {
    return try {
        // Use RDAMVM prefix to request YouTube Music studio audio tracks instead of video mixes
        val next = Innertube.nextPage(
            NextBody(
                videoId = videoId,
                playlistId = "RDAMVM$videoId"
            )
        )?.getOrNull() ?: Innertube.nextPage(NextBody(videoId = videoId))?.getOrNull()

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
                            val videoType = watch
                                ?.watchEndpointMusicSupportedConfigs
                                ?.watchEndpointMusicConfig
                                ?.musicVideoType
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
                                watch?.videoId != null && (videoType == null || videoType == "MUSIC_VIDEO_TYPE_ATV") -> {
                                    val vid = watch.videoId
                                    val artistName = twoRow.subtitle?.runs?.firstOrNull()?.text ?: "Unknown Artist"
                                    tracks.add(
                                        Track(
                                            id = vid.hashCode().toLong(),
                                            uri = Uri.parse("youtube://video/$vid"),
                                            title = title,
                                            artist = artistName,
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

            // 2b. Fallback: if FEmusic_home & FEmusic_explore returned empty, populate via Innertube search
            if (tracks.isEmpty()) {
                val fallbackTracks = search("top hits music")
                tracks.addAll(fallbackTracks)
            }
            if (playlists.isEmpty()) {
                runCatching {
                    val plResult = Innertube.searchPage(
                        body = SearchBody(
                            query = "top hits playlist",
                            params = Innertube.SearchFilter.CommunityPlaylist.value,
                        ),
                        fromMusicShelfRendererContent = Innertube.PlaylistItem.Companion::from,
                    )?.getOrNull()
                    plResult?.items?.forEach { pl ->
                        val bId = pl.info?.endpoint?.browseId ?: return@forEach
                        val name = pl.info.name ?: return@forEach
                        playlists.add(
                            YtHomePlaylist(
                                browseId = bId,
                                title = name,
                                subtitle = pl.channel?.name ?: "YouTube Music",
                                coverUrl = pl.thumbnail?.url,
                            )
                        )
                    }
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

            val shelves = listOf("trending", "hip hop", "pop", "afrobeats", "chill").mapNotNull { genre ->
    val found = Innertube.searchPage(
        body = SearchBody(query = "$genre playlist", params = Innertube.SearchFilter.CommunityPlaylist.value),
        fromMusicShelfRendererContent = Innertube.PlaylistItem.Companion::from,
    )?.getOrNull()?.items?.mapNotNull { pl ->
        val id = pl.info?.endpoint?.browseId ?: return@mapNotNull null
        YtHomePlaylist(id, pl.info.name ?: return@mapNotNull null, pl.channel?.name ?: genre, pl.thumbnail?.url)
    }?.take(8).orEmpty()
    if (found.isNotEmpty()) YtHomeShelf(genre, found) else null
}


            YtHomeFeed(
                playlists = playlists.distinctBy { it.browseId }.take(16),
                shelves = shelves,
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

        /** Loads full playlist/album/radio details (title, cover, and tracks) for browsing. */
    suspend fun getYtPlaylistDetail(
        browseId: String,
        fallbackTitle: String,
        fallbackCover: String? = null,
    ): YtPlaylistDetail {
        return try {
            val normalizedId = if (browseId.startsWith("PL") || browseId.startsWith("OLAK")) "VL$browseId" else browseId
            val page = Innertube.playlistPage(BrowseBody(browseId = normalizedId))?.getOrNull()
            val collected = mutableListOf<Track>()
            page?.songsPage?.items?.mapNotNull { it.toTrack() }?.let { collected.addAll(it) }
            var nextToken = page?.songsPage?.continuation
            var pagesFetched = 0
            while (nextToken != null && pagesFetched < 4) {
                val contPage = Innertube.playlistPage(ContinuationBody(continuation = nextToken))?.getOrNull()
                contPage?.items?.mapNotNull { it.toTrack() }?.let { collected.addAll(it) }
                nextToken = contPage?.continuation
                pagesFetched++
            }
            if (collected.isEmpty()) {
                val fallbackId = normalizedId.removePrefix("VL")
                Innertube.nextPage(NextBody(playlistId = fallbackId))?.getOrNull()?.itemsPage?.items?.mapNotNull { it.toTrack() }?.let { collected.addAll(it) }
            }
            val rawTracks = collected.distinctBy { it.remoteId }
            val title = page?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val subtitle = page?.description?.takeIf { it.isNotBlank() }
                ?: "${rawTracks.size} tracks"
            val cover = page?.thumbnail?.url ?: fallbackCover ?: rawTracks.firstOrNull()?.coverUri?.toString()
            val coverUriParsed = cover?.let { Uri.parse(it) }
            val tracks = if (coverUriParsed != null) {
                rawTracks.map { t -> if (t.coverUri == null) t.copy(coverUri = coverUriParsed) else t }
            } else {
                rawTracks
            }
            YtPlaylistDetail(
                browseId = browseId,
                title = title,
                subtitle = subtitle,
                coverUrl = cover,
                tracks = tracks,
            )
        } catch (e: Exception) {
            Log.e(TAG, "getYtPlaylistDetail failed for '$browseId': ${e.message}")
            YtPlaylistDetail(browseId = browseId, title = fallbackTitle, coverUrl = fallbackCover)
        }
    }

    /** Fetches the user's YouTube Music Liked Songs ("VLLM") and Saved Playlists ("FEmusic_liked_playlists"). */
    suspend fun youtubeLibrary(): Pair<List<Track>, List<YtHomePlaylist>> {
        val liked = runCatching {
            val remoteLiked = getYtPlaylistDetail("VLLM", "Liked Songs").tracks
            if (remoteLiked.isNotEmpty()) remoteLiked else likedTracks.all()
        }.getOrElse { likedTracks.all() }

        val savedPlaylists = mutableListOf<YtHomePlaylist>()
        runCatching {
            val resp = Innertube.client.post("/youtubei/v1/browse") {
                setBody(BrowseBody(browseId = "FEmusic_liked_playlists"))
            }.body<BrowseResponse>()

            val tabs = resp.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty()
            val sections = tabs.firstOrNull()
                ?.tabRenderer?.content
                ?.sectionListRenderer?.contents
                .orEmpty()

            for (section in sections) {
                val shelfItems = section.musicCarouselShelfRenderer?.contents.orEmpty()
                for (item in shelfItems) {
                    val twoRow = item.musicTwoRowItemRenderer ?: continue
                    val bId = twoRow.navigationEndpoint?.browseEndpoint?.browseId ?: continue
                    if (bId == "VLLM" || bId == "LM") continue
                    val title = twoRow.title?.text ?: continue
                    val subtitle = twoRow.subtitle?.runs?.joinToString("") { it.text.orEmpty() }.orEmpty()
                    val thumb = twoRow.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url
                    if (savedPlaylists.none { it.browseId == bId }) {
                        savedPlaylists.add(YtHomePlaylist(bId, title, subtitle, thumb))
                    }
                }
            }
        }
        return liked to savedPlaylists
    }

    /** Fetches official & community YouTube Music playlists for a genre (supports endless pagination via continuation). */
    suspend fun genrePlaylists(genre: String, pageIndex: Int = 0): Pair<List<YtHomePlaylist>, String?> {
        return try {
            val page = Innertube.searchPage(
                    body = SearchBody(
                        query = if (pageIndex == 0) "$genre music playlist" else "$genre hits playlist $pageIndex",
                        params = Innertube.SearchFilter.CommunityPlaylist.value,
                    ),
                    fromMusicShelfRendererContent = Innertube.PlaylistItem.Companion::from,
                )?.getOrNull()
            val list = page?.items?.mapNotNull { pl ->
                val bId = pl.info?.endpoint?.browseId ?: return@mapNotNull null
                val name = pl.info.name ?: return@mapNotNull null
                YtHomePlaylist(
                    browseId = bId,
                    title = name,
                    subtitle = pl.channel?.name ?: genre,
                    coverUrl = pl.thumbnail?.url,
                )
            }.orEmpty()
            list to if (list.isNotEmpty()) (pageIndex + 1).toString() else null
        } catch (e: Exception) {
            Log.e(TAG, "genrePlaylists failed for '$genre': ${e.message}")
            emptyList<YtHomePlaylist>() to null
        }
    }


    /** Searches YouTube Music specifically for artist channels so users can click into their profile. */
    suspend fun searchArtists(query: String): List<YtHomeArtist> {
        return try {
            val result = Innertube.searchPage(
                body = SearchBody(
                    query = query,
                    params = Innertube.SearchFilter.Artist.value,
                ),
                fromMusicShelfRendererContent = Innertube.ArtistItem.Companion::from,
            )?.getOrNull()
            result?.items?.mapNotNull { item ->
                val bId = item.info?.endpoint?.browseId ?: return@mapNotNull null
                val name = item.info.name ?: return@mapNotNull null
                YtHomeArtist(
                    name = name,
                    browseId = bId,
                    coverUrl = item.thumbnail?.url,
                )
            }?.take(5).orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "searchArtists failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /** Fetches full YouTube Music Artist profile: Top Songs, Albums, Featured Playlists, and Similar Artists. */
    suspend fun getYtArtistDetail(
        browseId: String,
        fallbackName: String,
        fallbackCover: String? = null,
    ): YtArtistDetail {
        return try {
            val resp = Innertube.client.post("/youtubei/v1/browse") {
                setBody(BrowseBody(browseId = browseId))
            }.body<BrowseResponse>()
            val headerThumb = resp.header?.musicImmersiveHeaderRenderer?.thumbnail
                ?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url
                ?: fallbackCover

            val headerTitle = resp.header?.musicImmersiveHeaderRenderer?.title?.text
                ?: fallbackName

            val sections = resp.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs?.firstOrNull()
                ?.tabRenderer?.content
                ?.sectionListRenderer?.contents
                .orEmpty()

            val topSongs = mutableListOf<Track>()
            val albums = mutableListOf<YtHomePlaylist>()
            val playlists = mutableListOf<YtHomePlaylist>()
            val similarArtists = mutableListOf<YtHomeArtist>()

            for (section in sections) {
                // 1. Top Songs shelf (musicShelfRenderer)
                val shelf = section.musicShelfRenderer
                if (shelf != null) {
                    shelf.contents.orEmpty().forEach { item ->
                        item.musicResponsiveListItemRenderer?.let { listItem ->
                            Innertube.SongItem.from(listItem)?.toTrack()?.let { topSongs.add(it) }
                        }
                    }
                }

                // 2. Carousel shelves (Albums, Singles, Featured Playlists, Fans Also Like)
                val carousel = section.musicCarouselShelfRenderer
                if (carousel != null) {
                    for (item in carousel.contents.orEmpty()) {
                        val twoRow = item.musicTwoRowItemRenderer ?: continue
                        val browse = twoRow.navigationEndpoint?.browseEndpoint ?: continue
                        val bId = browse.browseId ?: continue
                        val title = twoRow.title?.text ?: continue
                        val subtitle = twoRow.subtitle?.runs?.joinToString("") { it.text.orEmpty() }.orEmpty()
                        val thumb = twoRow.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url

                        when {
                            bId.startsWith("MPRE") || bId.startsWith("OLAK") -> {
                                albums.add(YtHomePlaylist(bId, title, subtitle.ifBlank { "album" }, thumb))
                            }
                            bId.startsWith("VL") || bId.startsWith("PL") || bId.startsWith("RD") -> {
                                playlists.add(YtHomePlaylist(bId, title, subtitle.ifBlank { "playlist" }, thumb))
                            }
                            bId.startsWith("UC") -> {
                                similarArtists.add(YtHomeArtist(title, bId, thumb))
                            }
                        }
                    }
                }
            }

            // Fallback if topSongs came back empty
            if (topSongs.isEmpty()) {
                topSongs.addAll(search(headerTitle).take(10))
            }

            YtArtistDetail(
                browseId = browseId,
                name = headerTitle,
                coverUrl = headerThumb ?: topSongs.firstOrNull()?.coverUri?.toString(),
                topSongs = topSongs,
                albums = albums,
                playlists = playlists,
                similarArtists = similarArtists,
            )
        } catch (e: Exception) {
            Log.e(TAG, "getYtArtistDetail failed for '$browseId': ${e.message}")
            YtArtistDetail(browseId = browseId, name = fallbackName, coverUrl = fallbackCover)
        }
    }

    
private fun Innertube.SongItem.toTrack(): Track? {
        if (!isAudioTrack) return null
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
