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
        val key = track.id.toString() + ":" + rawMode.toString()
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
            indexTracks(existing)
            c.playWhenReady = false
            rawPlayer.isExoPlaying = false
            c.setMediaItems(
                existing.map { it.toMediaItem() },
                index,
                position,
            )
            c.prepare()
        }
    }

    private fun fetchOnlineLyrics() {
        val id = currentState.nowPlayingId ?: return
        if (currentState.lyricsFetching || currentState.lyrics != null) return
        val track = currentState.tracks.find { it.id.toString() == id } ?: return

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
            total > 0L -> ((cached * 100 / total).coerceAtMost(99)).toString() + "%"
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
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        rawPlayer.stop()
    }
}
 
