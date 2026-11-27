package dev.abhi.zmt.presentation.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.data.repository.YtHomeArtist
import dev.abhi.zmt.R
import dev.abhi.zmt.core.common.Caption
import dev.abhi.zmt.core.common.ListRow
import dev.abhi.zmt.core.common.SearchRow
import dev.abhi.zmt.core.common.TrackBadges
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.core.common.tuiClickable
import dev.abhi.zmt.core.common.AsciiCover
import dev.abhi.zmt.core.common.PacmanLoader
import dev.abhi.zmt.core.common.TuiTypingText
import dev.abhi.zmt.domain.model.Artist
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.domain.model.asCredit
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.presentation.player.LibrarySection
import dev.abhi.zmt.presentation.player.SheetHeader
import dev.abhi.zmt.presentation.player.TuiSheet
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiBright
import dev.abhi.zmt.ui.theme.TuiLine
import dev.abhi.zmt.ui.theme.TuiRaised
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFaint
import dev.abhi.zmt.ui.theme.TuiFg
import dev.abhi.zmt.util.asTime

@Composable
fun LibraryPane(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap = { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) },
) {
    val isYouTube = state.settings.sourceMode == SourceMode.YOUTUBE

    if (isYouTube) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 10.dp),
                ) {
                    TuiTypingText(
                        text = "> youtube music library",
                        style = MaterialTheme.typography.titleMedium,
                        color = TuiBright,
                        charDelayMs = 18L,
                    )
                    TuiKey(label = "[ sync ]") { dispatch(DmtAction.RefreshYtHome) }
                }

                // Liked Songs Folder Card
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TuiAccent)
                        .background(TuiRaised)
                        .tuiClickable {
                            dispatch(
                                DmtAction.OpenYtPlaylist(
                                    browseId = "VLLM",
                                    title = "liked songs",
                                    coverUrl = state.ytLikedSongs.firstOrNull()?.coverUri?.toString(),
                                )
                            )
                        }
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            text = "[ ♥ liked songs ]",
                            style = MaterialTheme.typography.titleMedium,
                            color = TuiAccent,
                        )
                        Text(
                            text = "${state.ytLikedSongs.size} tracks · ls/library/liked songs",
                            style = MaterialTheme.typography.labelMedium,
                            color = TuiDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(text = "->", style = MaterialTheme.typography.titleMedium, color = TuiAccent)
                }

                TuiTypingText(
                    text = "> saved playlists (${state.ytSavedPlaylists.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiBright,
                    charDelayMs = 18L,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }

            if (state.ytHomeLoading && state.ytSavedPlaylists.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    ) {
                        PacmanLoader(label = "loading library")
                    }
                }
            } else if (state.ytSavedPlaylists.isEmpty()) {
                item {
                    Caption("no saved playlists found — sign in with youtube cookies in sources")
                }
            } else {
                itemsIndexed(state.ytSavedPlaylists, key = { idx: Int, pl: dev.abhi.zmt.data.repository.YtHomePlaylist -> "${pl.browseId}_$idx" }) { index, pl ->
                    ListRow(
                        index = index,
                        line1 = pl.title,
                        line2 = pl.subtitle.ifBlank { "youtube playlist" }.lowercase(),
                        current = false,
                        onClick = {
                            dispatch(DmtAction.OpenYtPlaylist(pl.browseId, pl.title, pl.coverUrl))
                        },
                    )
                }
            }
        }
        return
    }

    Column {
        SearchRow(
            query = state.query,
            hint = pluralStringResource(
                R.plurals.search_tracks_hint,
                state.tracks.size,
                state.tracks.size,
            ),
            shown = state.filtered.size,
            onQuery = { dispatch(DmtAction.Query(it)) },
            onSearch = { dispatch(DmtAction.Search) },
            sort = state.settings.librarySort.label,
            onSort = {
                dispatch(
                    DmtAction.Config(
                        state.settings.copy(
                            librarySort = state.settings.librarySort.next(
                                state.settings.sourceMode,
                            ),
                        ),
                    ),
                )
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        ) {
            TuiKey(
                label = if (state.selectionMode) "[ select: on ]" else "[ select ]",
            ) {
                dispatch(DmtAction.ToggleSelectMode)
            }
        }

        if (state.tracks.isEmpty() && state.query.isBlank() && isYouTube) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = "type a song name above to search",
                style = MaterialTheme.typography.labelSmall,
                color = TuiDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        if (state.tracks.isEmpty() && state.query.isBlank() && !isYouTube) {
            Caption(stringResource(R.string.no_audio, state.settings.sourceMode.label))
        }

     if (state.scanning) {
         Box(
             contentAlignment = Alignment.Center,
             modifier = Modifier
                 .fillMaxWidth()
                 .padding(vertical = 32.dp),
         ) {
             PacmanLoader(label = if (state.query.isNotBlank()) "searching '${state.query.lowercase()}'" else "scanning library")
         }
     } else if (isYouTube && state.query.isNotBlank() && state.filtered.isNotEmpty()) {
         TuiTypingText(
             text = "> ${state.filtered.size} matches for '${state.query.lowercase()}'",
             style = MaterialTheme.typography.labelSmall,
             color = TuiDim,
             charDelayMs = 12L,
             modifier = Modifier.padding(vertical = 4.dp),
         )
     }

        if (!state.scanning && state.query.isNotBlank() && state.filtered.isEmpty()) {
            Caption(stringResource(R.string.no_match))
        }

        if (!isYouTube && state.tracks.isNotEmpty() && state.query.isBlank()) {
            SectionChips(
                selected = state.librarySection,
                onSelect = { dispatch(DmtAction.SetLibrarySection(it)) },
            )
        }

        // Selection mode: toggle a "select" action; when active, rows toggle
        // membership and a footer lets the user save the selection as a playlist.
        if (state.selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = "selected ${state.selectedTrackIds.size}".lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TuiAccent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuiKey(label = "[ clear ]") { dispatch(DmtAction.ClearSelection) }
                    TuiKey(label = "[ save as playlist ]") {
                        if (state.selectedTrackIds.isNotEmpty()) {
                            dispatch(DmtAction.ShowCreateSelectionSheet)
                        }
                    }
                }
            }
        }

        var longPress by remember { mutableStateOf<Track?>(null) }
        longPress?.let { track ->
            TuiSheet(onDismiss = { longPress = null }) {
                SheetHeader(title = track.title.lowercase(), meta = track.artist.lowercase())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    TuiKey(label = "[ play next ]") {
                        dispatch(DmtAction.PlayNextTrack(track))
                        longPress = null
                    }
                    TuiKey(label = "[ queue ]") {
                        dispatch(DmtAction.Enqueue(listOf(track), track.title))
                        longPress = null
                    }
                    if (track.source == TrackSource.YOUTUBE) {
                        TuiKey(label = "[ download ]") {
                            dispatch(DmtAction.DownloadToDevice(track))
                            longPress = null
                        }
                    }
                }
            }
        }

        // Create a playlist from the current selection.
        if (state.showCreateSelectionSheet) {
            CreateFromSelectionSheet(
                count = state.selectedTrackIds.size,
                onCreate = { name ->
                    dispatch(DmtAction.CreatePlaylistFromSelection(name))
                },
                onDismiss = { dispatch(DmtAction.DismissCreateSelectionSheet) },
            )
        }

        if (isYouTube && state.ytSearchedArtists.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                state.ytSearchedArtists.forEach { artist ->
                    val bId = artist.browseId ?: return@forEach
                    SearchArtistCard(
                        artist = artist,
                        art = art,
                        artKey = state.settings.rawArt,
                    ) {
                        dispatch(DmtAction.OpenYtArtist(bId, artist.name, artist.coverUrl))
                    }
                }
            }
        }

        LazyColumn {
            itemsIndexed(state.filtered, key = { _, track -> track.id }) { index, track ->
                val selected = track.id.toString() in state.selectedTrackIds
                if (state.selectionMode) {
                    ListRow(
                        index = index,
                        line1 = track.title,
                        line2 = trackLine2(track),
                        current = track.id.toString() == state.nowPlayingId,
                        onClick = { dispatch(DmtAction.ToggleTrackSelect(track.id.toString())) },
                        onLongClick = { longPress = track },
                        trailing = {
                            TrackBadges(track)
                            Text(
                                text = if (selected) "[x]" else "[ ]",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) TuiAccent else TuiFaint,
                            )
                        },
                        modifier = Modifier.animateItem(),
                    )
                } else {
                    ListRow(
                        index = index,
                        line1 = track.title,
                        line2 = trackLine2(track),
                        current = track.id.toString() == state.nowPlayingId,
                        onClick = { dispatch(DmtAction.PlayAt(state.filtered, index)) },
                        onLongClick = { longPress = track },
                        trailing = { TrackBadges(track) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

fun artistLine2(artist: Artist): String =
    "${artist.albums} alb · ${artist.tracks.size} trk"

fun trackLine2(
    track: Track,
    artist: Boolean = true,
    album: Boolean = true,
): String =
    listOfNotNull(
        track.artist.asCredit().takeIf { artist },
        track.album.takeIf { album },
        track.durationMs.asTime(),
    )
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .lowercase()

@Composable
private fun SectionChips(
    selected: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        LibrarySection.entries.forEach { section ->
            val label = when (section) {
                LibrarySection.ALL -> "all"
                LibrarySection.RECENT -> "recent"
                LibrarySection.PLAYED -> "played"
            }
            Text(
                text = "[$label]",
                style = MaterialTheme.typography.labelMedium,
                color = if (section == selected) TuiAccent else TuiDim,
                modifier = Modifier
                    .tuiClickable { onSelect(section) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CreateFromSelectionSheet(
    count: Int,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    TuiSheet(onDismiss = onDismiss) {
        SheetHeader(title = "save selection to playlist", meta = "$count selected")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                text = " > ",
                style = MaterialTheme.typography.bodyLarge,
                color = TuiAccent,
            )
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TuiFg),
                cursorBrush = SolidColor(TuiAccent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (name.isEmpty()) {
                        Text(
                            text = "playlist name",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TuiFaint,
                        )
                    }
                    inner()
                },
            )
            TuiKey(label = "[ create ]") {
                if (name.isNotBlank()) onCreate(name.trim())
            }
        }
    }
}

@Composable
private fun SearchArtistCard(
    artist: YtHomeArtist,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    val cover by produceState<Bitmap?>(initialValue = null, artist.name, artist.coverUrl, artKey) {
        val url = artist.coverUrl ?: return@produceState
        val seed = Track(
            id = (artist.browseId ?: artist.name).hashCode().toLong(),
            uri = Uri.parse("youtube://artist/${artist.browseId ?: artist.name}"),
            title = artist.name,
            artist = artist.name,
            album = "",
            path = "",
            durationMs = 0L,
            mime = "audio/mp4",
            bitrate = 0,
            size = 0L,
            trackNumber = 0,
            dateAdded = 0L,
            dateModified = 0L,
            coverUri = Uri.parse(url),
            source = TrackSource.YOUTUBE,
        )
        value = runCatching { art(seed) }.getOrNull()
    }
    Column(
        modifier = Modifier
            .width(124.dp)
            .border(1.dp, TuiLine)
            .tuiClickable(onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(TuiRaised),
        ) {
            if (cover != null) {
                AsciiCover(cover = cover!!, playing = false, wave = false)
            } else {
                Text("[ artist ]", style = MaterialTheme.typography.labelSmall, color = TuiDim)
            }
        }
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = TuiBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 6.dp),
        )
        Text(
            text = "artist",
            style = MaterialTheme.typography.labelMedium,
            color = TuiDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 2.dp, bottom = 8.dp),
        )
    }
}

private val SEARCH_GENRES = listOf(
    "pop" to "#FF5E7E",
    "hip hop" to "#F59E0B",
    "afrobeats" to "#10B981",
    "amapiano" to "#14B8A6",
    "r&b" to "#8B5CF6",
    "rock" to "#EF4444",
    "indie" to "#EC4899",
    "electronic" to "#06B6D4",
    "lo-fi" to "#6366F1",
    "jazz" to "#D97706",
    "latin" to "#F97316",
    "chill" to "#3B82F6",
)

@Composable
fun SearchPane(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap,
) {
    Column {
        // Linux-style `ls` search prompt with TuiTypingText animation
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TuiLine)
                .background(TuiRaised)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            TuiTypingText(
                text = "ls ~/music/ ",
                style = MaterialTheme.typography.labelMedium,
                color = TuiAccent,
                charDelayMs = 42L,
            )
            BasicTextField(
                value = state.query,
                onValueChange = { dispatch(DmtAction.Query(it)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TuiFg),
                cursorBrush = SolidColor(TuiAccent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) {
                        Text(
                            text = "grep -i \"track or artist\"...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TuiFaint,
                        )
                    }
                    inner()
                },
            )
            if (state.query.isNotBlank()) {
                TuiKey(label = "[ x ]") { dispatch(DmtAction.Query("")) }
                Spacer(modifier = Modifier.width(6.dp))
            }
            TuiKey(label = "[ run ]") { dispatch(DmtAction.Search) }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Mode A: Active Search Query -> Terminal path results `~music/<genre>/<title> <artist>`
        if (state.query.isNotBlank()) {
            if (state.scanning) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                ) {
                    PacmanLoader(label = "searching '${state.query.lowercase()}'")
                }
            } else {
                if (state.ytSearchedArtists.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                    ) {
                        state.ytSearchedArtists.forEach { artist ->
                            val bId = artist.browseId ?: return@forEach
                            SearchArtistCard(
                                artist = artist,
                                art = art,
                                artKey = state.settings.rawArt,
                            ) {
                                dispatch(DmtAction.OpenYtArtist(bId, artist.name, artist.coverUrl))
                            }
                        }
                    }
                }

                LazyColumn {
                    itemsIndexed(state.filtered, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        val genreSlug = state.ytSearchGenres[track.id] ?: "music"
                        val filePathLine = "~music/$genreSlug/${track.title.lowercase()} ${track.artist.lowercase()}"
                        ListRow(
                            index = index,
                            line1 = filePathLine,
                            line2 = "${track.album.lowercase()} · ${track.durationMs.asTime()}",
                            current = track.id.toString() == state.nowPlayingId,
                            onClick = { dispatch(DmtAction.PlayAt(state.filtered, index)) },
                            trailing = { TrackBadges(track) },
                        )
                    }
                }
            }
            return
        }

        // Mode B: Selected Genre -> Endless scrollable list of YouTube Music playlists
        val activeGenre = state.selectedGenre
        if (activeGenre != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                TuiKey(label = "[ < genres ]") { dispatch(DmtAction.CloseYtGenre) }
                TuiTypingText(
                    text = "ls ~/genres/${activeGenre.lowercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiAccent,
                    charDelayMs = 42L,
                )
            }

            LazyColumn {
                itemsIndexed(state.genrePlaylists, key = { idx: Int, pl: dev.abhi.zmt.data.repository.YtHomePlaylist -> "${pl.browseId}_$idx" }) { index, pl ->
                    if (index >= state.genrePlaylists.size - 3 && state.genreContinuation != null && !state.genreLoadingMore) {
                        androidx.compose.runtime.LaunchedEffect(state.genrePlaylists.size) {
                            dispatch(DmtAction.LoadMoreYtGenre)
                        }
                    }
                    ListRow(
                        index = index,
                        line1 = pl.title,
                        line2 = pl.subtitle.ifBlank { activeGenre }.lowercase(),
                        current = false,
                        onClick = {
                            dispatch(DmtAction.OpenYtPlaylist(pl.browseId, pl.title, pl.coverUrl))
                        },
                    )
                }
                if (state.genreLoadingMore) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        ) {
                            PacmanLoader(label = "loading more $activeGenre playlists")
                        }
                    }
                }
            }
            return
        }

        // Mode C: Default Search Tab -> Spotify-style Grid of Genre Boxes
        TuiTypingText(
            text = "> browse genres",
            style = MaterialTheme.typography.titleMedium,
            color = TuiBright,
            charDelayMs = 42L,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = SEARCH_GENRES.chunked(2)
            itemsIndexed(rows) { _, pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEach { (genre, _) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(68.dp)
                                .border(1.dp, TuiLine)
                                .background(TuiRaised)
                                .tuiClickable { dispatch(DmtAction.OpenYtGenre(genre)) }
                                .padding(12.dp),
                        ) {
                            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                                Text(
                                    text = "# $genre",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TuiBright,
                                )
                                Text(
                                    text = "playlists ->",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TuiAccent,
                                )
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


