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

    Column {
        SearchRow(
            query = state.query,
            hint = if (isYouTube) {
                "search youtube music..."
            } else {
                pluralStringResource(
                    R.plurals.search_tracks_hint,
                    state.tracks.size,
                    state.tracks.size,
                )
            },
            shown = state.filtered.size,
            onQuery = { dispatch(DmtAction.Query(it)) },
            onSearch = { dispatch(DmtAction.Search) },
            sort = if (isYouTube) null else state.settings.librarySort.label,
            onSort = if (isYouTube) null else {
                {
                    dispatch(
                        DmtAction.Config(
                            state.settings.copy(
                                librarySort = state.settings.librarySort.next(
                                    state.settings.sourceMode,
                                ),
                            ),
                        ),
                    )
                }
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
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = TuiAccent,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isYouTube) "searching..." else "scanning...",
                style = MaterialTheme.typography.labelSmall,
                color = TuiDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
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

