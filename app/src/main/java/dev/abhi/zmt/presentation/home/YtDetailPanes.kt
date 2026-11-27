package dev.abhi.zmt.presentation.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.core.common.AsciiCover
import dev.abhi.zmt.core.common.ListRow
import dev.abhi.zmt.core.common.TrackBadges
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.core.common.tuiClickable
import dev.abhi.zmt.core.common.PacmanLoader
import dev.abhi.zmt.core.common.TuiTypingText
import dev.abhi.zmt.data.repository.YtHomeArtist
import dev.abhi.zmt.data.repository.YtHomePlaylist
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.presentation.library.trackLine2
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.presentation.player.DmtView
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiBright
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiLine
import dev.abhi.zmt.ui.theme.TuiRaised

@Composable
fun YtPlaylistDetailPane(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap,
) {
    val detail = state.ytPlaylistDetail ?: return
    val coverSeed = detail.tracks.firstOrNull() ?: detail.coverUrl?.let { url ->
        Track(
            id = detail.browseId.hashCode().toLong(),
            uri = Uri.parse("youtube://playlist/${detail.browseId}"),
            title = detail.title,
            artist = "",
            album = detail.title,
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
    }

    val coverBitmap by produceState<Bitmap?>(initialValue = null, coverSeed?.id, state.settings.rawArt) {
        if (coverSeed != null) value = runCatching { art(coverSeed) }.getOrNull()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            val pathHeader = if (state.ytPreviousView == DmtView.LIBRARY) {
                "ls/library/${detail.title.lowercase()}"
            } else {
                val artistSlug = detail.tracks.firstOrNull()?.artist?.lowercase()
                    ?: state.ytArtistDetail?.name?.lowercase()
                    ?: "youtube"
                "ls/music/$artistSlug/${detail.title.lowercase()}"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                TuiKey(label = "[ < back ]") {
                    dispatch(DmtAction.Show(state.ytPreviousView))
                }
                TuiTypingText(
                    text = pathHeader,
                    style = MaterialTheme.typography.labelMedium,
                    color = TuiAccent,
                    charDelayMs = 42L,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TuiLine)
                    .background(TuiRaised)
                    .padding(12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(116.dp)
                        .border(1.dp, TuiLine),
                ) {
                    if (coverBitmap != null) {
                        AsciiCover(cover = coverBitmap!!, playing = false, wave = false)
                    } else {
                        Text("[ ascii ]", style = MaterialTheme.typography.labelSmall, color = TuiDim)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    TuiTypingText(
                        text = detail.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TuiBright,
                        maxLines = 2,
                    )
                    Text(
                        text = detail.subtitle.lowercase().ifBlank { "${detail.tracks.size} tracks" },
                        style = MaterialTheme.typography.labelMedium,
                        color = TuiDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TuiKey(label = "[ play all ]") {
                            if (detail.tracks.isNotEmpty()) {
                                dispatch(DmtAction.PlayAt(detail.tracks, 0))
                            }
                        }
                        TuiKey(label = "[ shuffle ]") {
                            if (detail.tracks.isNotEmpty()) {
                                dispatch(DmtAction.PlayAt(detail.tracks.shuffled(), 0))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (state.ytDetailLoading && detail.tracks.isEmpty()) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                ) {
                    PacmanLoader(label = "loading tracks")
                }
            }
        } else {
            itemsIndexed(detail.tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                ListRow(
                    index = index,
                    line1 = track.title,
                    line2 = trackLine2(track),
                    current = track.id.toString() == state.nowPlayingId,
                    onClick = { dispatch(DmtAction.PlayAt(detail.tracks, index)) },
                    trailing = { TrackBadges(track) },
                )
            }
        }
    }
}

@Composable
fun YtArtistDetailPane(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap,
) {
    val artist = state.ytArtistDetail ?: return
    val coverSeed = artist.coverUrl?.let { url ->
        Track(
            id = artist.browseId.hashCode().toLong(),
            uri = Uri.parse("youtube://artist/${artist.browseId}"),
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
    } ?: artist.topSongs.firstOrNull()

    val coverBitmap by produceState<Bitmap?>(initialValue = null, coverSeed?.id, state.settings.rawArt) {
        if (coverSeed != null) value = runCatching { art(coverSeed) }.getOrNull()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                TuiKey(label = "[ < back ]") {
                    dispatch(DmtAction.Show(state.ytPreviousView))
                }
                TuiTypingText(
                    text = "ls/music/${artist.name.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TuiAccent,
                    charDelayMs = 42L,
                    maxLines = 1,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TuiLine)
                    .background(TuiRaised)
                    .padding(12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(104.dp)
                        .border(1.dp, TuiLine),
                ) {
                    if (coverBitmap != null) {
                        AsciiCover(cover = coverBitmap!!, playing = false, wave = false)
                    } else {
                        Text("[ artist ]", style = MaterialTheme.typography.labelSmall, color = TuiDim)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    TuiTypingText(
                        text = artist.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = TuiBright,
                        maxLines = 2,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (artist.topSongs.isNotEmpty()) {
                        TuiKey(label = "[ play all ]") {
                            dispatch(DmtAction.PlayAt(artist.topSongs, 0))
                        }
                    }
                }
            }
        }

        if (state.ytDetailLoading && artist.topSongs.isEmpty()) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                ) {
                    PacmanLoader(label = "loading artist profile")
                }
            }
        }

        // 1. Top Songs
        if (artist.topSongs.isNotEmpty()) {
            item {
                TuiTypingText(
                    text = "> top songs",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiBright,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
            }
            itemsIndexed(artist.topSongs, key = { idx, t -> "top_${t.id}_$idx" }) { index, track ->
                ListRow(
                    index = index,
                    line1 = track.title,
                    line2 = trackLine2(track, artist = false),
                    current = track.id.toString() == state.nowPlayingId,
                    onClick = { dispatch(DmtAction.PlayAt(artist.topSongs, index)) },
                    trailing = { TrackBadges(track) },
                )
            }
        }

        // 2. Albums (clickable to browse)
        if (artist.albums.isNotEmpty()) {
            item {
                HorizontalDivider(color = TuiLine, modifier = Modifier.padding(top = 16.dp))
                TuiTypingText(
                    text = "> albums",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiBright,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(artist.albums, key = { it.browseId }) { album ->
                        YtCollectionCard(
                            item = album,
                            tag = "[ album ]",
                            art = art,
                            artKey = state.settings.rawArt,
                        ) {
                            dispatch(DmtAction.OpenYtPlaylist(album.browseId, album.title, album.coverUrl))
                        }
                    }
                }
            }
        }

        // 3. Featured Playlists (clickable to browse)
        if (artist.playlists.isNotEmpty()) {
            item {
                HorizontalDivider(color = TuiLine, modifier = Modifier.padding(top = 16.dp))
                TuiTypingText(
                    text = "> featured on playlists",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiBright,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(artist.playlists, key = { it.browseId }) { pl ->
                        YtCollectionCard(
                            item = pl,
                            tag = "[ playlist ]",
                            art = art,
                            artKey = state.settings.rawArt,
                        ) {
                            dispatch(DmtAction.OpenYtPlaylist(pl.browseId, pl.title, pl.coverUrl))
                        }
                    }
                }
            }
        }

        // 4. Similar Artists at the bottom (clickable to browse)
        if (artist.similarArtists.isNotEmpty()) {
            item {
                HorizontalDivider(color = TuiLine, modifier = Modifier.padding(top = 16.dp))
                TuiTypingText(
                    text = "> similar artists",
                    style = MaterialTheme.typography.titleMedium,
                    color = TuiBright,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(artist.similarArtists, key = { it.browseId ?: it.name }) { sim ->
                        YtArtistChipCard(
                            artist = sim,
                            art = art,
                            artKey = state.settings.rawArt,
                        ) {
                            sim.browseId?.let { id ->
                                dispatch(DmtAction.OpenYtArtist(id, sim.name, sim.coverUrl))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
@Composable
private fun YtCollectionCard(
    item: YtHomePlaylist,
    tag: String,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    val cover by produceState<Bitmap?>(initialValue = null, item.browseId, item.coverUrl, artKey) {
        val url = item.coverUrl ?: return@produceState
        val seed = Track(
            id = item.browseId.hashCode().toLong(),
            uri = Uri.parse("youtube://collection/${item.browseId}"),
            title = item.title,
            artist = item.subtitle,
            album = item.title,
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
            .width(148.dp)
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
                Text(text = tag, style = MaterialTheme.typography.labelSmall, color = TuiDim)
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = TuiBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 6.dp),
        )
        Text(
            text = item.subtitle.lowercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TuiDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 2.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun YtArtistChipCard(
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
            .width(136.dp)
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
                Text(text = "[ artist ]", style = MaterialTheme.typography.labelSmall, color = TuiDim)
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

