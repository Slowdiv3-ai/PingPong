package dev.abhi.zmt.presentation.player

import android.content.Context
import android.util.Log
import dev.abhi.zmt.BuildConfig
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlin.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.abhi.zmt.R
import dev.abhi.zmt.audio.RawAudioPlayer
import dev.abhi.zmt.core.base.BaseViewModel
import dev.abhi.zmt.core.common.generateAsciiPlaceholder
import dev.abhi.zmt.core.common.toAsciiBitmap
import dev.abhi.zmt.data.repository.PlaylistRepository
import dev.abhi.zmt.data.repository.PreferencesRepository
import dev.abhi.zmt.data.repository.TrackMediaRepository
import dev.abhi.zmt.domain.model.Album
import dev.abhi.zmt.domain.model.Artist
import dev.abhi.zmt.domain.model.Folder
import dev.abhi.zmt.domain.model.Genre
import dev.abhi.zmt.domain.model.homeShelves
import dev.abhi.zmt.domain.model.LibrarySort
import dev.abhi.zmt.domain.model.Playlist
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.domain.model.Spec
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.domain.usecase.EmbedLyricsUseCase
import dev.abhi.zmt.domain.usecase.GetLyricsUseCase
import dev.abhi.zmt.domain.usecase.GetTrackTechUseCase
import dev.abhi.zmt.domain.usecase.JellyfinLoginUseCase
import dev.abhi.zmt.domain.usecase.TelegramLoginUseCase
import dev.abhi.zmt.data.remote.telegram.TelegramAuthStep
import kotlinx.coroutines.flow.collectLatest
import dev.abhi.zmt.domain.usecase.ScanLibraryUseCase
import dev.abhi.zmt.playback.PlaybackCache
import dev.abhi.zmt.playback.PlaybackService
import dev.abhi.zmt.data.remote.playlist.PlaylistImporter
import dev.abhi.zmt.data.remote.playlist.UrlPlaylistResolver
import dev.abhi.zmt.util.audioPermission
import dev.abhi.zmt.util.cycleRepeat
import dev.abhi.zmt.util.mediaController
import dev.abhi.zmt.util.queueWithPosition
import dev.abhi.zmt.util.resolveQueue
import dev.abhi.zmt.util.toMediaItem
import dev.abhi.zmt.util.togglePlayPause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

val EQ_PRESETS = listOf(
    "flat" to null,
    "bass boost" to intArrayOf(12, 8, 0, -4, -2, 0, 4, 8, 10, 12),
    "treble boost" to intArrayOf(-8, -4, 0, 2, 4, 6, 8, 10, 12, 12),
    "vocal" to intArrayOf(-6, -2, 4, 8, 6, 4, 2, 0, -2, -4),
    "rock" to intArrayOf(6, 4, 0, -2, 2, 4, 6, 8, 8, 6),
    "electronic" to intArrayOf(6, 8, 4, 0, -2, 2, 4, 8, 8, 6),
    "classical" to intArrayOf(0, 2, 4, 6, 4, 2, 0, -2, -4, -4),
)
private val SLEEP_STEPS = listOf(0, -1, 15, 30, 60)
private val LIBRARY_SETTLE = 500.milliseconds
private const val HOME_ART_COLS = 48
private const val HOME_ART_CACHE_BYTES = 32 * 1024 * 1024

private data class FilteredLibrary(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<Folder>,
    val genres: List<Genre> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val rawPlayer: RawAudioPlayer,
    private val scanLibrary: ScanLibraryUseCase,
    private val jellyfinLogin: JellyfinLoginUseCase,
    private val telegramLogin: TelegramLoginUseCase,
    private val getLyrics: GetLyricsUseCase,
    private val embedLyrics: EmbedLyricsUseCase,
    private val getTrackTech: GetTrackTechUseCase,
    private val trackMediaRepository: TrackMediaRepository,
    private val playlistRepository: PlaylistRepository,
    private val mediaSourceProvider: dev.abhi.zmt.domain.usecase.MediaSourceProvider,
    private val youtubeRepository: dev.abhi.zmt.data.repository.YoutubeMediaRepositoryImpl,
    private val downloadManager: dev.abhi.zmt.data.remote.download.TrackDownloadManager,
    private val streamCache: dev.abhi.zmt.playback.StreamCacheManager,
    private val youtubeStreamResolver: dev.abhi.zmt.data.remote.youtube.YoutubeStreamResolver,
    private val playbackCache: PlaybackCache,
    private val telegramClient: dev.abhi.zmt.data.remote.telegram.TelegramClient,
    private val playlistImporter: PlaylistImporter,
    private val urlPlaylistResolver: UrlPlaylistResolver,
    private val importedTracks: dev.abhi.zmt.data.repository.ImportedTracksRepository,
) : BaseViewModel<DmtAction, DmtState, PlayerEffect>(
    DmtState(
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            audioPermission,
        ) == PackageManager.PERMISSION_GRANTED,
    ),
) {

    private var controller: MediaController? = null
    private var pendingEmbed: Pair<Track, String>? = null
    private var noticeJob: Job? = null
    private var coverJob: Job? = null
    private var techJob: Job? = null
    private var lyricsJob: Job? = null
    private var sleepEndAt: Long? = null
    private var sessionRestored = false

    /** mediaId -> track path, indexed at play time so likes work for any source. */
    private val trackPathIndex = mutableMapOf<String, String>()

    /** Last removed queue item, kept for undo. */
    private var pendingRestore: Pair<Int, MediaItem>? = null

    init {
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            dev.abhi.zmt.data.remote.youtube.innertube.Innertube.cookie = settings.youtubeCookie
            reduce { it.copy(settings = settings, settingsLoaded = true) }
            if (settings.sourceMode == SourceMode.YOUTUBE) {
                loadYoutubeHome()
            }
        }

        viewModelScope.launch {
            val initErr = telegramLogin.initialize()
            if (initErr != null) {
                reduce { it.copy(error = initErr) }
            }
            telegramLogin.observeAuthState().collectLatest { step ->
                reduce { it.copy(telegramAuthStep = step) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.stats.collect { stats ->
                reduce { if (it.stats == stats) it else it.copy(stats = stats).withHome() }
            }
        }
        viewModelScope.launch {
            trackMediaRepository.routeSpecs()
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .collect { route ->
                    reduce { if (it.route == route) it else it.copy(route = route) }
                }
        }
        viewModelScope.launch {
            scanLibrary.changes()
                .debounce(LIBRARY_SETTLE)
                .collect { if (currentState.hasPermission) scan() }
        }
        if (currentState.hasPermission) scan()
        connect()
    }

      private suspend fun performYouTubeSearch(query: String) {
        if (query.isBlank()) {
            reduce {
                it.copy(
                    tracks = emptyList(),
                    filtered = emptyList(),
                    ytSearchedArtists = emptyList(),
                    scanning = false,
                )
            }
            return
        }
        reduce { it.copy(scanning = true) }
        try {
            val results = youtubeRepository.search(query)
            val artists = youtubeRepository.searchArtists(query)
            indexTracks(results)
            if (currentState.query == query) {
                reduce {
                    it.copy(
                        scanning = false,
                        tracks = results,
                        filtered = results,
                        ytSearchedArtists = artists,
                    )
                }
            }
        } catch (e: Exception) {
            if (currentState.query == query) {
                reduce {
                    it.copy(
                        scanning = false,
                        error = "YouTube search failed: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun performLocalFilter(query: String) {
        val tracks = currentState.tracks
        val albums = currentState.albums
        val artists = currentState.artists
        val folders = currentState.folders
        val genres = currentState.genres
        val playlists = currentState.playlists
        val sort = currentState.settings.librarySort
        val (filteredTracks, filteredAlbums, filteredArtists, filteredFolders, filteredGenres, filteredPlaylists) =
            withContext(Dispatchers.Default) {
                FilteredLibrary(
                    tracks = sectioned(filter(tracks, query, sort)),
                    albums = filterAlbums(albums, query),
                    artists = filterArtists(artists, query),
                    folders = filterFolders(folders, query),
                    genres = filterGenres(genres, query),
                    playlists = filterPlaylists(playlists, query),
                )
            }
        if (currentState.query == query) {
            reduce {
                it.copy(
                    filtered = filteredTracks,
                    filteredAlbums = filteredAlbums,
                    filteredArtists = filteredArtists,
                    filteredFolders = filteredFolders,
                    filteredGenres = filteredGenres,
                    filteredPlaylists = filteredPlaylists,
                )
            }
        }
    }

    private fun lookupCurrentTrack(): dev.abhi.zmt.domain.model.Track? {
        val id = currentState.nowPlayingId
        if (id == null) return null
        // Try to find the track in available lists by matching nowPlayingId
        for (list in listOf(currentState.filtered, currentState.tracks, currentState.queue.mapNotNull { entry ->
            currentState.tracks.find { it.id == entry.index.toLong() }
        })) {
            val found = list.find { it.id.toString() == id }
            if (found != null) return found
        }
        return null
    }

    /** Stable identity for a track, used to remember that it was uploaded. */
    private fun trackKey(track: Track): String =
        track.remoteId?.let { "$it|${track.source.name}" }
            ?: track.id.toString()

    /**
     * Returns a local file for [track], auto-downloading streamed sources
     * (YouTube/Telegram/Jellyfin) when needed. [onProgress] receives 0f..1f
     * while the audio is being fetched.
     */
    private suspend fun acquireLocalFile(
        track: Track,
        onProgress: (Float) -> Unit,
    ): java.io.File? {
        // 1. Real local file
        val path = track.path
        if (path.isNotBlank() && !path.startsWith("tg://") && !path.startsWith("youtube://") && !path.startsWith("http")) {
            val f = java.io.File(path)
            if (f.exists() && f.length() > 0) return f
        }

        // 2. Already cached by the stream cache (played before)
        val uriStr = track.uri.toString()
        streamCache.cachedFileFor(uriStr)?.let { return it }
        if (track.source.name == "TELEGRAM") {
            streamCache.cachedFileFor("tg://audio/${track.remoteId}")?.let { return it }
        }

        // 3. Download on demand
        return when (track.source) {
            TrackSource.YOUTUBE -> {
                val videoId = track.remoteId ?: track.uri.lastPathSegment ?: return null
                streamCache.downloadSync(
                    uriStr,
                    resolveUrl = {
                        youtubeStreamResolver.resolve(videoId)?.let { Pair(it.url, it.userAgent) }
                    },
                    onProgress = onProgress,
                )
            }
            TrackSource.TELEGRAM -> {
                val fileId = track.remoteId?.toLongOrNull() ?: track.uri.lastPathSegment?.toLongOrNull() ?: return null
                onProgress(0.5f)
                val file = telegramClient.downloadFile(fileId)
                val f = file.local.path?.let { java.io.File(it) }
                if (f != null && f.exists() && f.length() > 0) {
                    onProgress(1f)
                    f
                } else {
                    null
                }
            }
            TrackSource.JELLYFIN -> {
                streamCache.downloadSync(
                    uriStr,
                    resolveUrl = { Pair(uriStr, "okhttp/4.9.3") },
                    onProgress = onProgress,
                )
            }
            else -> null
        }
    }

    private fun indexTracks(list: List<Track>) {
        list.forEach { track -> trackPathIndex[track.id.toString()] = track.path }
    }

    private fun sectioned(tracks: List<Track>): List<Track> =
        when (currentState.librarySection) {
            LibrarySection.ALL -> tracks
            LibrarySection.RECENT -> tracks.sortedByDescending { it.dateAdded }
            LibrarySection.PLAYED -> tracks
                .filter { (currentState.stats.counts[it.id] ?: 0) > 0 }
                .sortedByDescending { currentState.stats.counts[it.id] ?: 0 }
        }

    private fun clearRemovalSoon() {
        viewModelScope.launch {
            delay(5000L)
            reduce { it.copy(lastRemoved = null) }
        }
    }

    private fun saveQueueAsPlaylist() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val paths = (0 until c.mediaItemCount)
                .mapNotNull { i ->
                    val id = c.getMediaItemAt(i).mediaId ?: return@mapNotNull null
                    trackPathIndex[id]
                        ?: currentState.tracks.find { it.id.toString() == id }?.path
                }
                .filter { it.isNotBlank() }
            if (paths.isEmpty()) return@launch
            val name = "queue-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            playlistRepository.create(name)
            paths.forEach { playlistRepository.addPath(name, it) }
            mutatePlaylists()
            reduce { it.copy(notice = "saved $name") }
        }
    }

    private fun <T> List<T>.matching(query: String, fields: (T) -> List<String>): List<T> {
        if (query.isBlank()) return this
        return mapNotNull { item ->
            val best = fields(item).maxOfOrNull { field -> fuzzyScore(query, field) } ?: 0
            if (best > 0) item to best else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Typo-tolerant scoring: exact contains > ordered subsequence > 1-char prefix edit. */
    private fun fuzzyScore(query: String, candidate: String): Int {
        val q = query.trim().lowercase()
        val c = candidate.trim().lowercase()
        if (q.isEmpty() || c.isEmpty()) return 0
        val at = c.indexOf(q)
        if (at >= 0) return 1000 - at
        var qi = 0
        for (ch in c) {
            if (qi < q.length && ch == q[qi]) qi++
        }
        if (qi == q.length) return 500 - c.length.coerceAtMost(200)
        val prefix = c.take(q.length + 2)
        if (editDistance(q, prefix) <= 1) return 300
        return 0
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun filter(tracks: List<Track>, query: String, sort: LibrarySort): List<Track> =
        if (query.isBlank()) {
            tracks.sortedWith(sort.comparator)
        } else {
            // relevance order while searching (fuzzy-scored), library sort when idle
            tracks.matching(query) { listOf(it.title, it.artist, it.album) }
        }

    private fun filterAlbums(albums: List<Album>, query: String): List<Album> =
        albums.matching(query) { listOf(it.name, it.artist) }

    private fun filterArtists(artists: List<Artist>, query: String): List<Artist> =
        artists.matching(query) { listOf(it.name) }

    private fun filterFolders(folders: List<Folder>, query: String): List<Folder> =
        folders.matching(query) { listOf(it.name) }

    private fun filterGenres(genres: List<Genre>, query: String): List<Genre> =
        genres.matching(query) { listOf(it.name) }

    private fun filterPlaylists(playlists: List<Playlist>, query: String): List<Playlist> =
        playlists.matching(query) { listOf(it.name) }

    private fun mutatePlaylists(block: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            block()
            val playlists = playlistRepository.load(currentState.tracks)
            reduce {
                it.copy(
                    playlists = playlists,
                    filteredPlaylists = filterPlaylists(playlists, it.query),
                )
            }
        }
    }

    override fun onIntent(intent: DmtAction) {
        val c = controller
        when (intent) {
            is DmtAction.Permission -> {
                reduce { it.copy(hasPermission = intent.granted) }
                if (intent.granted) scan()
            }

            DmtAction.Rescan -> scan()
            is DmtAction.SetLibrarySection -> {
                reduce { it.copy(librarySection = intent.section) }
                if (currentState.settings.sourceMode != SourceMode.YOUTUBE) {
                    viewModelScope.launch { performLocalFilter(currentState.query) }
                }
            }
            is DmtAction.Query -> {
                reduce { it.copy(query = intent.value) }
                // Local filter is instant (in-memory), keep it on every keystroke
                if (currentState.settings.sourceMode == SourceMode.YOUTUBE) {
                    if (intent.value.isBlank()) {
                        // empty query in YouTube mode shows the liked library again
                        scan()
                    }
                } else {
                    viewModelScope.launch {
                        performLocalFilter(intent.value)
                    }
                }
            }
            is DmtAction.Search -> {
                val query = currentState.query
                if (query.isNotBlank() && currentState.settings.sourceMode == SourceMode.YOUTUBE) {
                    viewModelScope.launch {
                        performYouTubeSearch(query)
                    }
                }
            }
            DmtAction.ShowUploadSheet -> {
                reduce { it.copy(showUploadSheet = true) }
            }
            DmtAction.DismissUploadSheet -> {
                reduce { it.copy(showUploadSheet = false) }
            }
            DmtAction.UploadToTelegram -> {
                reduce { it.copy(showUploadSheet = false, uploadProgress = 0, uploadError = null) }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val track = currentState.currentTrack
                        val channelId = currentState.settings.telegramChannelId
                        if (track == null) {
                            reduce { it.copy(uploadError = "no track playing", uploadProgress = -1) }
                            return@launch
                        }
                        if (channelId == null) {
                            reduce { it.copy(uploadError = "no telegram channel connected", uploadProgress = -1) }
                            return@launch
                        }

                        // Resolve/reuse a local file for this track. Streamed tracks
                        // (YouTube/Telegram/Jellyfin) are auto-downloaded first.
                        val localFile = acquireLocalFile(track) { fraction ->
                            // Map down+upload total into 0..80 so upload has headroom
                            reduce { it.copy(uploadProgress = (fraction * 80).toInt().coerceIn(0, 80), uploadError = null) }
                        }
                        if (localFile == null) {
                            reduce { it.copy(uploadError = "could not download track for upload", uploadProgress = -1) }
                            return@launch
                        }

                        // Upload phase: 80 -> 101
                        reduce { it.copy(uploadProgress = 85, uploadError = null) }
                        val client = telegramClient
                        client.sendAudioToChannel(
                            channelId = channelId,
                            filePath = localFile.absolutePath,
                            title = track.title,
                            performer = track.artist,
                            duration = (track.durationMs / 1000).toInt(),
                            track.mime.ifBlank { "audio/mpeg" },
                        )

                        // Remember this track as uploaded so the tg button stays white.
                        val uploadId = trackKey(track)
                        val newIds = (currentState.settings.uploadedTrackIds + uploadId)
                        reduce {
                            it.copy(
                                uploadProgress = 101,
                                settings = it.settings.copy(uploadedTrackIds = newIds),
                            )
                        }
                        preferencesRepository.save(currentState.settings.copy(uploadedTrackIds = newIds))
                        // Hold the "done / filled" state briefly, then reset the progress
                        // indicator. The white button is remembered via uploadedTrackIds,
                        // so it stays filled for this track without leaking to others.
                        kotlinx.coroutines.delay(4000L)
                        if (currentState.uploadProgress >= 101) {
                            reduce { it.copy(uploadProgress = -1, uploadError = null) }
                        }
                    } catch (e: Exception) {
                        reduce { it.copy(uploadError = "upload failed: ${e.message}", uploadProgress = -1) }
                    }
                }
            }
            DmtAction.ShowDownloadSheet -> {
                reduce { it.copy(showDownloadSheet = true) }
            }
            DmtAction.DismissDownloadSheet -> {
                reduce { it.copy(showDownloadSheet = false) }
            }
            is DmtAction.DownloadToDevice -> {
                val track = intent.track ?: lookupCurrentTrack()
                if (track == null) { reduce { it.copy(downloadError = "No track playing") }; return@onIntent }
                val videoId = track.remoteId ?: track.uri.lastPathSegment ?: run {
                    reduce { it.copy(downloadError = "No video ID") }; return@onIntent
                }
                reduce { it.copy(showDownloadSheet = false, downloadingVideoId = videoId, downloadProgress = 0, downloadError = null) }
                // Download runs on TrackDownloadManager's own IO scope — NOT viewModelScope
                downloadManager.downloadToDevice(
                    context = context,
                    videoId = videoId,
                    title = track.title,
                    artist = track.artist,
                    onProgress = { progress ->
                        if (progress.isFinished) {
                            reduce {
                                it.copy(
                                    downloadingVideoId = null,
                                    downloadProgress = 101,
                                    downloadError = null,
                                )
                            }
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(2000L)
                                reduce { it.copy(downloadingVideoId = null, downloadProgress = -1, downloadError = null) }
                            }
                        } else if (progress.error != null) {
                            reduce {
                                it.copy(
                                    downloadingVideoId = null,
                                    downloadProgress = -2,
                                    downloadError = progress.error,
                                )
                            }
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(2000L)
                                reduce { it.copy(downloadProgress = -1, downloadError = null) }
                            }
                        } else {
                            reduce {
                                it.copy(
                                    downloadProgress = progress.percent.coerceIn(0, 99),
                                    downloadError = null,
                                )
                            }
                        }
                    },
                )
            }
            is DmtAction.Show -> {
                reduce { it.copy(view = intent.view, error = null) }
            }

            is DmtAction.OpenAlbum -> reduce { it.copy(openAlbum = intent.name) }
            is DmtAction.OpenArtist -> reduce { it.copy(openArtist = intent.name) }
            is DmtAction.OpenGenre -> reduce { it.copy(openGenre = intent.name) }
            is DmtAction.OpenFolder -> reduce { it.copy(openFolder = intent.path) }
            is DmtAction.OpenPlaylist -> reduce { it.copy(openPlaylist = intent.name) }

            is DmtAction.CreatePlaylist -> mutatePlaylists {
                playlistRepository.create(intent.name)
            }

            is DmtAction.DeletePlaylist -> {
                reduce { it.copy(openPlaylist = null) }
                mutatePlaylists { playlistRepository.delete(intent.name) }
            }

            is DmtAction.AddToPlaylist -> mutatePlaylists {
                playlistRepository.addTrack(intent.name, intent.track)
            }

            is DmtAction.RemoveFromPlaylist -> mutatePlaylists {
                playlistRepository.removeTrack(intent.name, intent.path)
            }

            is DmtAction.ToggleLike -> {
                val track = currentState.currentTrack ?: lookupCurrentTrack()
                if (track == null) {
                    reduce { it.copy(notice = "no track to like") }
                    return@onIntent
                }
                viewModelScope.launch(Dispatchers.IO) {
                    val liked = playlistRepository.toggleLiked(track)
                    reduce { it.copy(liked = liked) }
                    mutatePlaylists()
                    if (currentState.settings.sourceMode == SourceMode.YOUTUBE) {
                        scan()
                    }
                }
            }
            is DmtAction.PlayAt -> c?.run {
                reduce { it.copy(error = null) }
                lastPlayUriString = ""
                crossfadedForTrackId = null
                rawPlayer.isExoPlaying = true
                val selected = intent.list.getOrNull(intent.index)
                if (selected != null && selected.source == TrackSource.YOUTUBE && currentState.query.isNotBlank()) {
                    indexTracks(listOf(selected))
                    setMediaItems(listOf(selected.toMediaItem()), 0, 0L)
                    prepare()
                    play()
                    val videoId = selected.remoteId ?: selected.uri.lastPathSegment
                    if (!videoId.isNullOrBlank()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val similar = youtubeRepository.radioFor(videoId)
                            if (similar.isNotEmpty()) {
                                indexTracks(similar)
                                withContext(Dispatchers.Main) {
                                    if (controller?.currentMediaItem?.mediaId == selected.id.toString()) {
                                        controller?.addMediaItems(similar.map { it.toMediaItem() })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    indexTracks(intent.list)
                    setMediaItems(
                        intent.list.map { it.toMediaItem() },
                        intent.index,
                        0L,
                    )
                    prepare()
                    play()
                }
            }

            is DmtAction.Enqueue -> c?.run {
                indexTracks(intent.list)
                addMediaItems(intent.list.map { it.toMediaItem() })
                prepare()
                notify(context.getString(R.string.queued, intent.label))
            }

            is DmtAction.Jump -> {
                reduce { it.copy(showDownloadSheet = false) }
                rawPlayer.isExoPlaying = true
                c?.run {
                    seekTo(intent.index, 0L)
                    prepare()
                    play()
                }
            }


            DmtAction.TogglePlay -> c?.togglePlayPause()
            DmtAction.Next -> {
                reduce { it.copy(showDownloadSheet = false) }
                c?.seekToNext() // ExoPlayer automatically keeps playing if it was already playing
            }
            DmtAction.Prev -> {
                reduce { it.copy(showDownloadSheet = false) }
                c?.seekToPrevious()
            }
            DmtAction.ToggleShuffle -> c?.run { shuffleModeEnabled = !shuffleModeEnabled }
            DmtAction.CycleRepeat -> c?.cycleRepeat()

            is DmtAction.Seek -> c?.run {
                val duration = currentState.durationMs
                if (duration > 0) {
                    val target = (intent.fraction * duration).toLong()
                    seekTo(target)
                    reduce { it.copy(positionMs = target) }
                }
            }

            is DmtAction.Expand -> reduce { it.copy(expanded = intent.value) }

            is DmtAction.RemoveAt -> c?.run {
                if (intent.index in 0 until mediaItemCount) {
                    val item = getMediaItemAt(intent.index)
                    pendingRestore = intent.index to item
                    val label = item.mediaMetadata.run { "$title · $artist" }
                    removeMediaItem(intent.index)
                    reduce {
                        it.copy(
                            lastRemoved = QueueRemoval(index = intent.index, label = label),
                        )
                    }
                    clearRemovalSoon()
                }
            }

            is DmtAction.RestoreQueueItem -> c?.run {
                val pending = pendingRestore
                if (pending != null && pending.first == intent.index) {
                    addMediaItem(intent.index, pending.second)
                    pendingRestore = null
                    reduce { it.copy(lastRemoved = null) }
                }
            }

            is DmtAction.PlayNext -> c?.run {
                if (intent.index in 0 until mediaItemCount) {
                    val target = (currentMediaItemIndex + 1).coerceIn(0, mediaItemCount - 1)
                    moveMediaItem(intent.index, target)
                }
            }

            is DmtAction.PlayNextTrack -> c?.run {
                indexTracks(listOf(intent.track))
                val target = (currentMediaItemIndex + 1).coerceAtMost(mediaItemCount)
                addMediaItem(target, intent.track.toMediaItem())
                prepare()
                notify(context.getString(R.string.queued, intent.track.title))
            }


            DmtAction.ToggleSelectMode -> {
                reduce {
                    it.copy(
                        selectionMode = !it.selectionMode,
                        selectedTrackIds = if (it.selectionMode) emptySet() else it.selectedTrackIds,
                    )
                }
            }
            is DmtAction.ToggleTrackSelect -> {
                reduce {
                    val ids = it.selectedTrackIds.toMutableSet()
                    if (intent.trackId in ids) ids.remove(intent.trackId) else ids.add(intent.trackId)
                    it.copy(selectedTrackIds = ids)
                }
            }

            DmtAction.ShowCreateSelectionSheet -> {
                reduce { it.copy(showCreateSelectionSheet = true) }
            }
            DmtAction.DismissCreateSelectionSheet -> {
                reduce { it.copy(showCreateSelectionSheet = false) }
            }
            DmtAction.ClearSelection -> {
                reduce { it.copy(selectionMode = false, selectedTrackIds = emptySet()) }
            }
            is DmtAction.CreatePlaylistFromSelection -> {
                val tracks = currentState.tracks.filter { it.id.toString() in currentState.selectedTrackIds }
                if (tracks.isEmpty()) {
                    reduce { it.copy(notice = "no tracks selected") }
                } else {
                    val name = intent.name
                    mutatePlaylists {
                        playlistRepository.create(name)
                        tracks.forEach { playlistRepository.addTrack(name, it) }
                    }
                    reduce {
                        it.copy(
                            selectionMode = false,
                            selectedTrackIds = emptySet(),
                            notice = "playlist '$name' created with ${tracks.size} tracks",
                        )
                    }
                }
            }

            DmtAction.SaveQueueAsPlaylist -> saveQueueAsPlaylist()

            DmtAction.FetchLyrics -> fetchOnlineLyrics()
            is DmtAction.EmbedLyrics -> embedPendingLyrics(intent.granted)
            DmtAction.CycleSleep -> cycleSleep()
            DmtAction.CycleSpeed -> cycleSpeed()
            DmtAction.OpenEqualizer -> openEqualizer()
            DmtAction.NoEqualizer -> notify(context.getString(R.string.no_eq))

            is DmtAction.Config -> {
                val old = currentState.settings
                reduce { it.copy(settings = intent.settings) }
                if (old.librarySort != intent.settings.librarySort) {
                    reduce {
                        it.copy(
                            filtered = filter(it.tracks, it.query, intent.settings.librarySort),
                        )
                    }
                }
                if (old.sourceMode != intent.settings.sourceMode) {
                    c?.run {
                        stop()
                        clearMediaItems()
                    }
                }
                viewModelScope.launch {
                    preferencesRepository.save(intent.settings)
                    if (old.sourceMode != intent.settings.sourceMode ||
                        old.blockedFolders != intent.settings.blockedFolders
                    ) {
                        scan()
                    }
                }
                if (intent.settings.sourceMode == SourceMode.YOUTUBE &&
                    (old.sourceMode != SourceMode.YOUTUBE || old.youtubeCookie != intent.settings.youtubeCookie || currentState.ytHome.tracks.isEmpty())
                ) {
                    loadYoutubeHome()
                }
                if (old.cols != intent.settings.cols) loadCover(c?.currentMediaItem)
            }


            is DmtAction.ShowLogin ->
                reduce {
                    it.copy(
                        view = DmtView.SOURCE_LOGIN,
                        loginSource = intent.mode,
                        error = null,
                    )
                }

            is DmtAction.SourceLogin -> when (intent.mode) {
                SourceMode.JELLYFIN -> loginToJellyfin(intent)
                SourceMode.LOCAL -> Unit
                SourceMode.YOUTUBE -> Unit
                SourceMode.TELEGRAM -> {
                    viewModelScope.launch {
                        val initErr = telegramLogin.initialize()
                        if (initErr != null) {
                            reduce { it.copy(error = initErr) }
                            return@launch
                        }
                        val result = telegramLogin.sendPhoneNumber(intent.username)
                        if (result.isFailure) {
                            reduce { it.copy(error = result.exceptionOrNull()?.message ?: "Failed") }
                        }
                    }
                }
            }
            is DmtAction.TelegramSendPhone -> {
                viewModelScope.launch {
                    reduce { it.copy(error = null, scanning = true) }
                    val initErr = telegramLogin.initialize()
                    if (initErr != null) {
                        if (BuildConfig.DEBUG) Log.e("TDLibDebug", "Init failed: $initErr")
                        reduce { it.copy(error = "Init failed: $initErr", scanning = false) }
                        return@launch
                    }
                    if (BuildConfig.DEBUG) Log.d("TDLibDebug", "Init OK, sending phone")
                    try {
                        val result = telegramLogin.sendPhoneNumber(intent.phoneNumber)
                        if (result.isFailure) {
                            val errMsg = result.exceptionOrNull()?.message ?: "Failed to send phone number"
                            if (BuildConfig.DEBUG) Log.e("TDLibDebug", "Send phone failed: $errMsg")
                            reduce { it.copy(error = "Error: $errMsg", scanning = false) }
                        } else {
                            if (BuildConfig.DEBUG) Log.d("TDLibDebug", "Send phone OK, waiting for auth state...")
                            reduce { it.copy(scanning = false) }
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e("TDLibDebug", "Send phone exception: ${e.message}", e)
                        reduce { it.copy(error = "Connection error: ${e.message}", scanning = false) }
                    }
                }
            }
            is DmtAction.TelegramSubmitCode -> {
                viewModelScope.launch {
                    val result = telegramLogin.submitCode(intent.code)
                    if (result.isFailure) {
                        reduce { it.copy(error = result.exceptionOrNull()?.message ?: "Invalid code") }
                    }
                }
            }
            is DmtAction.TelegramSubmitPassword -> {
                viewModelScope.launch {
                    val result = telegramLogin.submitPassword(intent.password)
                    if (result.isFailure) {
                        reduce { it.copy(error = result.exceptionOrNull()?.message ?: "Invalid password") }
                    }
                }
            }
            is DmtAction.TelegramResolveChannel -> {
                viewModelScope.launch {
                    reduce { it.copy(telegramSyncing = true) }
                    val result = telegramLogin.resolveChannel(intent.channelInput)
                    if (result.isSuccess) {
                        val settings = preferencesRepository.settings.first()
                        reduce {
                            it.copy(
                                settings = settings,
                                telegramSyncing = false,
                                notice = "Channel connected",
                            )
                        }
                        scan()
                        onIntent(DmtAction.Show(DmtView.LIBRARY))
                    } else {
                        reduce {
                            it.copy(
                                telegramSyncing = false,
                                error = result.exceptionOrNull()?.message ?: "Failed to connect channel",
                            )
                        }
                    }
                }
            }
            is DmtAction.SetVolume -> {
                val c = controller ?: return@onIntent
                val vol = intent.fraction.coerceIn(0f, 1f)
                c.sendCustomCommand(
                    PlaybackService.CMD_SET_VOLUME,
                    Bundle().apply { putFloat(PlaybackService.KEY_VOLUME, vol) },
                )
                reduce { it.copy(volume = vol) }
            }
            is DmtAction.ShowImportDialog -> {
                reduce { it.copy(showImportDialog = true) }
            }
            is DmtAction.DismissImportDialog -> {
                reduce { it.copy(showImportDialog = false) }
            }
            is DmtAction.ImportPlaylistFromUrl -> {
                reduce { it.copy(showImportDialog = false, notice = "importing playlist...") }
                viewModelScope.launch(Dispatchers.IO) {
                    val library = currentState.tracks
                    val url = intent.url
                    val isSpotify = url.contains("spotify")

                    when (val result = if (isSpotify) {
                        urlPlaylistResolver.resolveSpotifyToTracks(url)
                    } else {
                        val r = urlPlaylistResolver.resolve(url, library)
                        when (r) {
                            is UrlPlaylistResolver.ResolveResult.Tracks -> {
                                val matched = urlPlaylistResolver.matchTracks(r.tracks, library)
                                if (matched.isEmpty()) {
                                    UrlPlaylistResolver.ImportResult.Error("no matching tracks found in your library")
                                } else {
                                    UrlPlaylistResolver.ImportResult.Success(r.name, matched, r.tracks.size)
                                }
                            }
                            is UrlPlaylistResolver.ResolveResult.Error ->
                                UrlPlaylistResolver.ImportResult.Error(r.message)
                        }
                    }) {
                        is UrlPlaylistResolver.ImportResult.Success -> {
                            val name = result.name
                            importedTracks.upsertAll(result.matched)
                            playlistRepository.create(name)
                            result.matched.forEach { playlistRepository.addTrack(name, it) }
                            reduce { it.copy(notice = "imported ${result.matched.size}/${result.total} tracks to $name") }
                            mutatePlaylists()
                            onIntent(DmtAction.Show(DmtView.LIBRARY))
                        }
                        is UrlPlaylistResolver.ImportResult.Error -> {
                            reduce { it.copy(error = "import failed: ${result.message}") }
                        }
                    }
                }
            }
            is DmtAction.PlaylistImported -> {
                reduce { it.copy(notice = intent.message) }
            }
            is DmtAction.RefreshYtHome -> loadYoutubeHome()
            is DmtAction.PlayYtPlaylist -> {
                notify("loading ${intent.title.lowercase()}...")
                viewModelScope.launch {
                    val tracks = withContext(Dispatchers.IO) {
                        youtubeRepository.playlistTracks(intent.browseId)
                    }
                    if (tracks.isEmpty()) {
                        notify("no playable tracks in playlist")
                    } else {
                        onIntent(DmtAction.PlayAt(tracks, 0))
                    }
                }
            }

            is DmtAction.OpenYtPlaylist -> {
                reduce {
                    it.copy(
                        ytPreviousView = if (it.view != DmtView.YT_PLAYLIST) it.view else it.ytPreviousView,
                        view = DmtView.YT_PLAYLIST,
                        ytDetailLoading = true,
                        ytPlaylistDetail = dev.abhi.zmt.data.repository.YtPlaylistDetail(
                            browseId = intent.browseId,
                            title = intent.title,
                            coverUrl = intent.coverUrl,
                        ),
                    )
                }
                viewModelScope.launch {
                    val detail = withContext(Dispatchers.IO) {
                        youtubeRepository.getYtPlaylistDetail(intent.browseId, intent.title, intent.coverUrl)
                    }
                    reduce {
                        it.copy(ytPlaylistDetail = detail, ytDetailLoading = false)
                    }
                }
            }
            is DmtAction.OpenYtArtist -> {
                reduce {
                    it.copy(
                        ytPreviousView = if (it.view == DmtView.LIBRARY) DmtView.LIBRARY else DmtView.HOME,
                        view = DmtView.YT_ARTIST,
                        ytDetailLoading = true,
                        ytArtistDetail = dev.abhi.zmt.data.repository.YtArtistDetail(
                            browseId = intent.browseId,
                            name = intent.name,
                            coverUrl = intent.coverUrl,
                        ),
                    )
                }
                viewModelScope.launch {
                    val detail = withContext(Dispatchers.IO) {
                        youtubeRepository.getYtArtistDetail(intent.browseId, intent.name, intent.coverUrl)
                    }
                    reduce {
                        it.copy(ytArtistDetail = detail, ytDetailLoading = false)
                    }
                }
            }

            is DmtAction.SetEqualizerPreset -> {
                val c = controller ?: return@onIntent
                c.sendCustomCommand(
                    PlaybackService.CMD_SET_EQ_PRESET,
                    Bundle().apply { putInt(PlaybackService.KEY_PRESET_INDEX, intent.presetIndex) },
                )
                val name = EQ_PRESETS.getOrNull(intent.presetIndex)?.first ?: "flat"
                reduce { it.copy(equalizerPresetName = name) }
                viewModelScope.launch {
                    preferencesRepository.save(
                        currentState.settings.copy(equalizerPreset = intent.presetIndex)
                    )
                }
            }
            is DmtAction.TelegramLogout -> {
                viewModelScope.launch {
                    telegramLogin.logout()
                    val settings = preferencesRepository.settings.first()
                    reduce { it.copy(settings = settings, telegramAuthStep = "") }
                    onIntent(DmtAction.Show(DmtView.SOURCES))
                }
            }
        }
    }

    private fun loginToJellyfin(intent: DmtAction.SourceLogin) =
        viewModelScope.launch {
            reduce { it.copy(scanning = true, error = null) }
            jellyfinLogin(intent.url, intent.username, intent.password)
                .onSuccess {
                    val settings = preferencesRepository.settings.first()
                    reduce { it.copy(settings = settings, view = DmtView.LIBRARY) }
                    scan()
                }
                .onFailure {
                    reduce {
                        it.copy(
                            scanning = false,
                            error = context.getString(R.string.source_login_failed),
                        )
                    }
                }
        }

    private fun connect() =
        viewModelScope.launch {
            val c = runCatching { context.mediaController() }.getOrNull()
                ?: return@launch
            controller = c
            c.addListener(listener)
            
            // CRITICAL: Mute ExoPlayer permanently. It should NEVER output audio.
            c.volume = 0f 
            
            syncFrom(c)
            restoreSleep(c)
            restoreSpeed(c)
            loadCover(c.currentMediaItem)
            loadTech(c.currentMediaItem)
            loadLyrics(c.currentMediaItem)
            restoreSession()
            while (isActive) {
                // CRITICAL: Force mute ExoPlayer continuously to prevent ANY audio leak, 
                // especially during track transitions before callbacks fire.
                c.volume = 0f 
                
                val position = c.currentPosition.coerceAtLeast(0L)
                val duration = c.duration.takeIf { d -> d != C.TIME_UNSET }?.coerceAtLeast(0L) ?: currentState.durationMs
                val index = c.currentMediaItemIndex

                val fadeSec = getCrossfadeSeconds()
                val fadeMs = fadeSec * 1000L
                val currentId = c.currentMediaItem?.mediaId
                if (c.isPlaying && fadeSec > 0 && duration > fadeMs && (duration - position) in 1..fadeMs) {
                    if (currentId != null && crossfadedForTrackId != currentId && c.hasNextMediaItem()) {
                        crossfadedForTrackId = currentId
                        val nextItem = c.getMediaItemAt(c.nextMediaItemIndex)
                        val nextUri = nextItem.localConfiguration?.uri
                        if (nextUri != null) {
                            lastPlayUriString = nextUri.toString()
                            lastPlayUriTime = System.currentTimeMillis()
                            playResolvedUri(
                                nextUri,
                                0L,
                                fadeSec,
                                onError = { msg -> android.util.Log.e("RawAudioPlayer", "Crossfade error: " + msg) },
                                onSuccess = { }
                            )
                            c.seekToNextMediaItem()
                        }

                    }
                }

                val sleepLeft = sleepEndAt?.let { end ->

                    (end - System.currentTimeMillis()).coerceAtLeast(0L)
                } ?: 0L
                val sleepExpired = sleepEndAt != null && sleepLeft == 0L
                if (sleepExpired) sleepEndAt = null
                reduce {
                    val vol = c.volume
                    if (it.positionMs == position &&
                        it.durationMs == duration &&
                        it.queueIndex == index &&
                        it.sleepLeftMs == sleepLeft &&
                        abs(it.volume - vol) < 0.01f &&
                        !sleepExpired
                    ) {
                        it
                    } else {
                        it.copy(
                            positionMs = position,
                            durationMs = duration,
                            queueIndex = index,
                            sleepLeftMs = sleepLeft,
                            volume = vol,
                            sleepMinutes = if (sleepExpired) 0 else it.sleepMinutes,
                        )
                    }
                }
                delay((if (c.isPlaying) 500 else 1500).milliseconds)
            }
        }
    


    private var lastPlayedTrackId: String? = null
    private var currentTrackUri: Uri? = null

        private var lastPlayUriTime = 0L
    private var lastPlayUriString = ""
    private var crossfadedForTrackId: String? = null

    private fun getCrossfadeSeconds(): Int =
        currentState.settings.crossfadeDuration.seconds

    private fun loadYoutubeHome() {
        if (currentState.ytHomeLoading) return
        reduce { it.copy(ytHomeLoading = true) }
        viewModelScope.launch {
            val feed = withContext(Dispatchers.IO) { youtubeRepository.homeFeed() }
            reduce { it.copy(ytHome = feed, ytHomeLoading = false) }
        }
    }


    private var currentTrackHeaders: Map<String, String>? = null
    private var resolveJob: Job? = null

    private var isSyncingLocalFile = false

    private fun syncExoToLocalFile(fileUri: Uri, positionMs: Long) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        val item = c.currentMediaItem ?: return
        if (idx < 0 || item.localConfiguration?.uri == fileUri) return

        val wasPlaying = c.playWhenReady || c.isPlaying
        isSyncingLocalFile = true
        lastPlayUriString = fileUri.toString()
        lastPlayUriTime = System.currentTimeMillis()

        val localItem = item.buildUpon()
            .setUri(fileUri)
            .build()
        c.replaceMediaItem(idx, localItem)
        c.seekTo(idx, positionMs)
        c.prepare()
        if (wasPlaying) c.play()
    }

    private fun playResolvedUri(
        uri: Uri,
        startTimeMs: Long,
        crossfadeSeconds: Int,
        onError: (String) -> Unit,
        onSuccess: () -> Unit,
    ) {
        resolveJob?.cancel()
        currentTrackUri = uri
        val uriStr = uri.toString()
        if (uri.scheme != "youtube") {
            currentTrackUri = uri
            currentTrackHeaders = null
            rawPlayer.playUri(context, uri, startTimeMs, crossfadeSeconds, null, onError, onSuccess)
            return
        }

        // Check if already cached locally first
        streamCache.cachedFileFor(uriStr)?.let { cachedFile ->
            val fileUri = Uri.fromFile(cachedFile)
            currentTrackUri = fileUri
            currentTrackHeaders = null
            syncExoToLocalFile(fileUri, startTimeMs)
            rawPlayer.playUri(context, fileUri, startTimeMs, crossfadeSeconds, null, onError, onSuccess)
            return
        }

        // Cancel any previous download and stop old audio immediately while loading the new track
        rawPlayer.stop()
        resolveJob = viewModelScope.launch {
            val videoId = uri.lastPathSegment ?: uriStr.removePrefix("youtube://video/")
            val cachedFile = withContext(Dispatchers.IO) {
                streamCache.downloadSync(
                    streamUri = uriStr,
                    resolveUrl = {
                        youtubeStreamResolver.resolve(videoId)?.let { Pair(it.url, it.userAgent) }
                    }
                ) ?: streamCache.downloadSync(
                    streamUri = uriStr,
                    resolveUrl = {
                        youtubeStreamResolver.resolveViaYtDlp(videoId)?.let { Pair(it.url, it.userAgent) }
                    }
                )
            }
            // Ignore if user already skipped to another track while downloading
            if (currentTrackUri != uri) return@launch
            if (cachedFile == null) {
                Log.e("RawAudioPlayer", "Download/resolve failed for $videoId")
                onError("Could not load YouTube stream for $videoId")
                return@launch
            }
            val fileUri = Uri.fromFile(cachedFile)
            currentTrackUri = fileUri
            currentTrackHeaders = null
            syncExoToLocalFile(fileUri, startTimeMs)
            if (controller?.playWhenReady == true || controller?.isPlaying == true) {
                rawPlayer.isExoPlaying = true
            }
            rawPlayer.playUri(
                context,
                fileUri,
                startTimeMs,
                crossfadeSeconds,
                null,
                onError = { err ->
                    Log.e("RawAudioPlayer", "DSP playback failed ($err), unmuting ExoPlayer fallback")
                    onError(err)
                },
                onSuccess = onSuccess
            )
        }
    }
 
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // CRITICAL: Mute ExoPlayer immediately to prevent the 1-second leak
            controller?.volume = 0f 
            if (isSyncingLocalFile) {
                isSyncingLocalFile = false
                return
            }
            crossfadedForTrackId = null

            
            val shouldCrossfade = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                                   reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            if (shouldCrossfade) {
                rawPlayer.isExoPlaying = true
                controller?.play()
            } else {
                rawPlayer.isExoPlaying = controller?.isPlaying == true
            }


            reduce {
                it.withNowPlaying(controller).copy(showDownloadSheet = false, lyrics = null, fault = null, error = null)
            }
            loadCover(mediaItem)
            loadTech(mediaItem)
            loadLyrics(mediaItem)
            loadLiked(mediaItem)

            val id = mediaItem?.mediaId
            val uri = mediaItem?.localConfiguration?.uri
            currentTrackUri = uri

            if (id != null && uri != null) {
                val track = trackPathIndex[id]?.let { path -> currentState.tracks.find { it.path == path } }
                    ?: currentState.tracks.find { it.id.toString() == id }
                if (track != null) {
                    reduce { if (it.nowPlayingId == id) it.copy(currentTrack = track) else it }
                }

                val now = System.currentTimeMillis()
                val uriStr = uri.toString()
                if (uriStr == lastPlayUriString && now - lastPlayUriTime < 600L) {
                    return
                }
                lastPlayUriString = uriStr
                lastPlayUriTime = now

                val fadeSeconds = 0
                val startPos = if (shouldCrossfade) 0L else (controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)

                playResolvedUri(uri, startPos, fadeSeconds,
                    onError = { msg ->
                        android.util.Log.e("RawAudioPlayer", "DSP FAILED: " + msg)
                        controller?.volume = 1f
                    },
                    onSuccess = {
                        controller?.volume = 0f
                    }
                )
            } else {
                rawPlayer.stop()
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            reduce { it.withNowPlaying(controller) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isRestoringSession && isPlaying) {
                controller?.pause()
                rawPlayer.isExoPlaying = false
                rawPlayer.pause()
                reduce { it.copy(isPlaying = false) }
                return
            }
            val shouldBePlaying = isPlaying || (controller?.playWhenReady == true && controller?.playbackState == Player.STATE_BUFFERING)
            rawPlayer.isExoPlaying = shouldBePlaying
            reduce { it.copy(isPlaying = isPlaying) }
            if (shouldBePlaying) {
                controller?.volume = 0f
                rawPlayer.resume()
            } else {
                rawPlayer.pause()
            }
        }
        

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                rawPlayer.isExoPlaying = false
                rawPlayer.pause()
            } else if (playbackState == Player.STATE_READY && controller?.playWhenReady == true) {
                rawPlayer.isExoPlaying = true
                rawPlayer.resume()
            }
        }


        
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                controller?.volume = 0f
                controller?.play()

                val uri = currentTrackUri
                if (uri != null) {
                    val now = System.currentTimeMillis()
                    val uriStr = uri.toString()
                    if (uriStr == lastPlayUriString && now - lastPlayUriTime < 600L) {
                        return
                    }
                    lastPlayUriString = uriStr
                    lastPlayUriTime = now
                    playResolvedUri(uri, newPosition.positionMs, 0,
                        onError = { msg -> android.util.Log.e("RawAudioPlayer", "Seek error: " + msg) },
                        onSuccess = { }
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            reduce { it.copy(repeat = repeatMode) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            reduce { it.copy(speed = playbackParameters.speed) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            controller?.let { c ->
                val (queue, queuePosition) = c.queueWithPosition()
                reduce { it.copy(queue = queue, queuePosition = queuePosition) }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.isEmpty() || tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) return
            controller?.pause()
            reduce {
                val format = currentState.tech
                    .firstOrNull { spec -> spec.label == "FMT" }
                    ?.value
                    ?.lowercase()
                    .orEmpty()
                it.copy(fault = context.getString(R.string.playback_unsupported, format))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val sb = StringBuilder()
            sb.appendLine("PlaybackError: ${error.errorCodeName} (${error.errorCode})")
            var httpCode = -1
            var rootCause: Throwable? = null
            var c: Throwable? = error.cause
            while (c != null) {
                val cn = c::class.simpleName ?: ""
                sb.appendLine("  Cause: $cn: ${c.message?.take(200)}")
                rootCause = c
                if (cn == "InvalidResponseCodeException") {
                    try {
                        val codeField = c::class.java.getDeclaredField("responseCode")
                        codeField.isAccessible = true
                        httpCode = codeField.get(c) as? Int ?: -1
                        sb.appendLine("  >>> HTTP Status: $httpCode <<<")
                    } catch (_: Exception) {
                        sb.appendLine("  >>> HTTP error (reflection failed) <<<")
                    }
                    break
                }
                c = c.cause
            }
            if (BuildConfig.DEBUG) Log.e("PlaybackDebug", sb.toString())

            val rootMsg = rootCause?.message ?: ""
            val message = when {
                httpCode == 403 -> "stream blocked (403) \u2014 trying another source"
                httpCode == 429 -> "rate limited (429) \u2014 retrying soon"
                httpCode in 500..599 -> "server error ($httpCode) \u2014 skipping"
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> when {
                    rootMsg.contains("403") -> "stream blocked \u2014 trying another source"
                    rootMsg.contains("429") -> "rate limited \u2014 retrying soon"
                    rootMsg.contains("timeout", ignoreCase = true) -> "connection timed out \u2014 check network"
                    rootMsg.contains("reset", ignoreCase = true) -> "connection reset \u2014 check network"
                    rootMsg.contains("unreachable", ignoreCase = true) -> "host unreachable \u2014 check network"
                    else -> "stream unavailable \u2014 skipping"
                }
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "bad HTTP response \u2014 skipping"
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "network error \u2014 check connection"
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "connection timed out \u2014 check network"
                error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    "insecure connection blocked \u2014 check network"
                error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                    "content unavailable"
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    "stream expired \u2014 skipping"
                else -> context.getString(R.string.playback_error, error.errorCodeName.lowercase())
            }
            reduce { it.copy(fault = message) }
        }
    } // <-- THIS BRACE CLOSES THE LISTENER OBJECT. DO NOT DELETE IT.

    private fun triggerDspPlay(uri: Uri, timeMs: Long) {
            val now = System.currentTimeMillis()
            val uriStr = uri.toString()
            
            // If we played this exact URI at this exact time in the last 500ms, IGNORE IT.
            if (uriStr == lastPlayUriString && Math.abs(timeMs - lastPlayUriTime) < 500 && now - lastPlayUriTime < 500) {
                return
            }
            
            lastPlayUriString = uriStr
            lastPlayUriTime = timeMs
            
            rawPlayer.playUri(context, uri, timeMs,
                onError = { msg -> android.util.Log.e("RawAudioPlayer", "DSP FAILED: $msg"); controller?.volume = 1f },
                onSuccess = { }
            )
            controller?.volume = 0f
    }
    
    private fun DmtState.withNowPlaying(c: MediaController?): DmtState {
        if (c == null) return this
        val meta = c.mediaMetadata
        return copy(
            nowPlayingId = c.currentMediaItem?.mediaId,
            title = meta.title?.toString() ?: title,
            artist = meta.artist?.toString() ?: artist,
            album = meta.albumTitle?.toString() ?: album,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET }?.coerceAtLeast(0L) ?: durationMs,
        )
    }

        private fun syncFrom(c: MediaController) {
        val (queue, queuePosition) = c.queueWithPosition()
        reduce {
            it.withNowPlaying(c).copy(
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = c.repeatMode,
                speed = c.playbackParameters.speed,
                queue = queue,
                queuePosition = queuePosition,
            )
        }
        loadLiked(c.currentMediaItem)
        loadCurrentTrack(c.currentMediaItem)
    }

    private fun loadCurrentTrack(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId ?: return
        val track = trackPathIndex[id]?.let { path ->
            currentState.tracks.find { it.path == path }
        } ?: currentState.tracks.find { it.id.toString() == id }
        if (track != null) {
            reduce { if (it.nowPlayingId == id) it.copy(currentTrack = track) else it }
        }
    }

    private fun loadLiked(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId
        viewModelScope.launch(Dispatchers.IO) {
            val path = id?.let { mid ->
                trackPathIndex[mid] ?: currentState.tracks.find { t -> t.id.toString() == mid }?.path
            }
            val liked = path?.let { playlistRepository.isLiked(it) } ?: false
            reduce { if (it.nowPlayingId != id) it else it.copy(liked = liked) }
        }
    }

    private fun scan() =
        viewModelScope.launch {
            reduce { it.copy(scanning = true) }
            val query = currentState.query
            val library = runCatching { scanLibrary() }.getOrElse {
                reduce { state ->
                    state.copy(
                        scanning = false,
                        tracks = emptyList(),
                        albums = emptyList(),
                        artists = emptyList(),
                        folders = emptyList(),
                        genres = emptyList(),
                        filtered = emptyList(),
                        filteredAlbums = emptyList(),
                        filteredArtists = emptyList(),
                        filteredFolders = emptyList(),
                        filteredGenres = emptyList(),
                        error = context.getString(
                            R.string.scan_failed,
                            state.settings.sourceMode.label,
                        ),
                    )
                }
                return@launch
            }
            indexTracks(library.tracks)
            val (filteredTracks, filteredAlbums, filteredArtists, filteredFolders, filteredGenres) = withContext(
                Dispatchers.Default,
            ) {
                FilteredLibrary(
                    tracks = sectioned(filter(library.tracks, query, currentState.settings.librarySort)),
                    albums = filterAlbums(library.albums, query),
                    artists = filterArtists(library.artists, query),
                    folders = filterFolders(library.folders, query),
                    genres = filterGenres(library.genres, query),
                )
            }
            reduce {
                it.copy(
                    scanning = false,
                    tracks = library.tracks,
                    albums = library.albums,
                    artists = library.artists,
                    folders = library.folders,
                    genres = library.genres,
                    filtered = filteredTracks,
                    filteredAlbums = filteredAlbums,
                    filteredArtists = filteredArtists,
                    filteredFolders = filteredFolders,
                    filteredGenres = filteredGenres,
                    error = null,
                ).withHome()
            }
            mutatePlaylists()
            restoreSession()
        }

    private fun DmtState.withHome(): DmtState =
        copy(home = homeShelves(tracks, albums, artists, playlists, stats.counts))

    private val homeArtCache = object : LruCache<String, Bitmap>(HOME_ART_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun homeArt(track: Track): Bitmap {
        val rawMode = currentState.settings.rawArt
        val key = "${track.id}:$rawMode"
        homeArtCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val raw = track.coverUri?.let { trackMediaRepository.loadArt(it, track.uri) }
            val art = when {
                raw != null && rawMode -> raw
                raw != null ->
                    runCatching { raw.toAsciiBitmap(context, HOME_ART_COLS) }.getOrNull()
                        ?: generateAsciiPlaceholder(context, track.id, HOME_ART_COLS)

                else -> generateAsciiPlaceholder(context, track.id, HOME_ART_COLS)
            }
            homeArtCache.put(key, art)
            art
        }
    }

    private var isRestoringSession = false

    private fun restoreSession() {
        if (sessionRestored) return
        val c = controller ?: return
        val tracks = currentState.tracks
        if (tracks.isEmpty()) return
        if (c.mediaItemCount > 0) {
            sessionRestored = true
            return
        }
        sessionRestored = true
        viewModelScope.launch {
            val session = preferencesRepository.lastSession() ?: return@launch
            val (existing, index, position) = session.resolveQueue(tracks) ?: return@launch
            isRestoringSession = true
            indexTracks(existing)
            c.playWhenReady = false
            rawPlayer.isExoPlaying = false
            rawPlayer.pause()
            c.setMediaItems(
                existing.map { it.toMediaItem() },
                index,
                position,
            )
            c.prepare()
            c.seekTo(index, position)
            c.pause()
            val track = existing.getOrNull(index)
            if (track != null) {
                lastPlayUriString = track.uri.toString()
                lastPlayUriTime = System.currentTimeMillis()
                playResolvedUri(
                    track.uri,
                    position,
                    0,
                    onError = { msg -> android.util.Log.e("RawAudioPlayer", "Restore error: " + msg) },
                    onSuccess = { }
                )
            }
            delay(600L)
            c.pause()
            rawPlayer.isExoPlaying = false
            rawPlayer.pause()
            isRestoringSession = false
        }

    }
    

    private fun fetchOnlineLyrics() {
        val id = currentState.nowPlayingId ?: return
        if (currentState.lyricsFetching || currentState.lyrics != null) return
        val track = currentState.tracks.find { it.id.toString() == id }
            ?: currentState.currentTrack
            ?: lookupCurrentTrack()
            ?: controller?.currentMediaItem?.let { item ->
                Track(
                    id = id.toLongOrNull() ?: 0L,
                    uri = item.localConfiguration?.uri ?: Uri.EMPTY,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                    path = item.localConfiguration?.uri?.toString().orEmpty(),
                    durationMs = currentState.durationMs,
                    mime = "audio/mpeg",
                    bitrate = 0,
                    size = 0L,
                    trackNumber = 0,
                    dateAdded = 0L,
                    dateModified = 0L,
                    source = TrackSource.YOUTUBE,
                )
            }
            ?: return

        reduce { it.copy(lyricsFetching = true) }
        viewModelScope.launch {
            val text = getLyrics.onlineText(track)
            val lyrics = text?.let { getLyrics.parse(it) }

            reduce {
                if (it.nowPlayingId != id) {
                    it.copy(lyricsFetching = false)
                } else {
                    it.copy(lyricsFetching = false, lyrics = lyrics)
                }
            }
            if (lyrics == null) {
                notify(context.getString(R.string.no_lyrics_found))
                return@launch
            }

            // Skip file embedding for streamed YouTube tracks
            if (track.source != TrackSource.LOCAL) return@launch

            val intentSender = embedLyrics.writeRequest(track)
            if (intentSender == null) {
                notify(context.getString(R.string.lyrics_embed_unsupported))
                return@launch
            }
            pendingEmbed = track to text
            sendEffect(PlayerEffect.RequestWrite(intentSender))
        }
    }

    private fun embedPendingLyrics(granted: Boolean) {
        val (track, text) = pendingEmbed ?: return
        pendingEmbed = null
        if (!granted) return

        viewModelScope.launch {
            val done = embedLyrics(track, text)
            notify(
                context.getString(
                    if (done) R.string.lyrics_embedded else R.string.lyrics_embed_failed,
                ),
            )
        }
    }

    private fun loadLyrics(mediaItem: MediaItem?) {
        val forId = mediaItem?.mediaId
        reduce { it.copy(lyricsFetching = true) }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val track = currentState.tracks.find { it.id.toString() == forId }
            val lyrics = track?.let { getLyrics(it) }
            reduce {
                if (it.nowPlayingId != forId) {
                    it
                } else {
                    it.copy(lyrics = lyrics, lyricsFetching = false)
                }
            }
        }
    }

    private fun loadCover(mediaItem: MediaItem?) {
        val uri: Uri? = mediaItem?.mediaMetadata?.artworkUri
        val fileUri: Uri? = mediaItem?.localConfiguration?.uri
        val forId = mediaItem?.mediaId
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                uri?.let { trackMediaRepository.loadArt(it, fileUri) }
            }
            val cover = withContext(Dispatchers.IO) {
                raw?.let { art ->
                    runCatching {
                        art.toAsciiBitmap(context, currentState.settings.cols)
                    }.getOrNull()
                } ?: mediaItem?.let {
                    generateAsciiPlaceholder(
                        context = context,
                        seed = forId?.toLongOrNull() ?: forId.hashCode().toLong(),
                        cols = currentState.settings.cols,
                    )
                }
            }
            reduce {
                if (it.nowPlayingId != forId) it else it.copy(cover = cover, artRaw = raw)
            }
        }
    }

    private fun loadTech(mediaItem: MediaItem?) {
        val uri = mediaItem?.localConfiguration?.uri
        val id = mediaItem?.mediaId
        techJob?.cancel()
        techJob = viewModelScope.launch {
            val track = currentState.tracks.find { t -> t.id.toString() == id }
            val tech = uri?.let { getTrackTech(it, track) }.orEmpty()
            val specs = tech + cacheSpec(uri)
            reduce {
                if (it.nowPlayingId != id) it else it.copy(tech = specs)
            }
        }
    }

    private fun cacheSpec(uri: Uri?): List<Spec> {
        val cached = playbackCache.cachedBytes(uri)
        if (cached <= 0L) return emptyList()
        val total = playbackCache.contentLength(uri)
        val value = when {
            total > 0L && cached >= total -> "on-disk"
            total > 0L -> "${(cached * 100 / total).coerceAtMost(99)}%"
            else -> "partial"
        }
        return listOf(Spec(label = "CACHE", value = value, hot = true))
    }

    private fun openEqualizer() {
        val c = controller ?: return
        viewModelScope.launch {
            val sessionId = runCatching {
                c.sendCustomCommand(PlaybackService.CMD_AUDIO_SESSION, Bundle.EMPTY)
                    .await()
                    .extras
                    .getInt(PlaybackService.KEY_AUDIO_SESSION)
            }.getOrDefault(0)
            sendEffect(PlayerEffect.OpenEqualizer(sessionId))
        }
    }

    private fun cycleSpeed() {
        val c = controller ?: return
        val currentIndex = SPEED_STEPS.indexOfFirst { abs(it - currentState.speed) < 0.01f }
        val next = SPEED_STEPS[(currentIndex + 1).mod(SPEED_STEPS.size)]
        c.setPlaybackSpeed(next)
        viewModelScope.launch {
            preferencesRepository.saveSpeed(next)
        }
    }

    private fun cycleSleep() {
        val c = controller ?: return
        val currentIndex = SLEEP_STEPS.indexOf(currentState.sleepMinutes)
        val next = SLEEP_STEPS[(currentIndex + 1).mod(SLEEP_STEPS.size)]
        val endAt = when {
            next == 0 -> 0L
            next == -1 -> {
                // End-of-track: schedule at current track's remaining time
                val remaining = c.duration - c.currentPosition
                if (remaining > 0) System.currentTimeMillis() + remaining else 0L
            }
            else -> System.currentTimeMillis() + next * 60_000L
        }
        c.sendCustomCommand(
            PlaybackService.CMD_SLEEP_SET,
            Bundle().apply { putLong(PlaybackService.KEY_END_AT, endAt) },
        )
        sleepEndAt = endAt.takeIf { it > 0L }
        reduce {
            it.copy(
                sleepMinutes = next,
                sleepLeftMs = when {
                    next == 0 -> 0L
                    next == -1 -> c.duration - c.currentPosition
                    else -> next * 60_000L
                },
            )
        }
    }

    private suspend fun restoreSpeed(c: MediaController) {
        val saved = preferencesRepository.savedSpeed()
        if (abs(c.playbackParameters.speed - saved) > 0.01f) {
            c.setPlaybackSpeed(saved)
        }
    }

    private suspend fun restoreSleep(c: MediaController) {
        runCatching {
            val result = c.sendCustomCommand(PlaybackService.CMD_SLEEP_GET, Bundle.EMPTY).await()
            val endAt = result.extras.getLong(PlaybackService.KEY_END_AT)
            if (endAt > System.currentTimeMillis()) {
                sleepEndAt = endAt
                val left = endAt - System.currentTimeMillis()
                val step = when {
                    left <= 15 * 60_000L -> 15
                    left <= 30 * 60_000L -> 30
                    else -> 60
                }
                reduce { it.copy(sleepMinutes = step, sleepLeftMs = left) }
            }
        }
    }

    private fun notify(message: String) {
        noticeJob?.cancel()
        reduce { it.copy(notice = message) }
        noticeJob = viewModelScope.launch {
            delay(2.seconds)
            reduce { it.copy(notice = null) }
        }
    }

    override fun onCleared() {
        // 1. Detach the ExoPlayer listener so it doesn't trigger multiple times
        controller?.removeListener(listener)
        
        // 2. Release the ExoPlayer controller
        controller?.release()
        controller = null
        
        // 3. Detach and stop the RawAudioPlayer background thread to prevent crashes
        rawPlayer.stop()
    }
} // <-- This is the final closing brace of the whole file
