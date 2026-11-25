package dev.abhi.zmt.presentation.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.R
import dev.abhi.zmt.core.common.AsciiCover
import dev.abhi.zmt.core.common.Caption
import dev.abhi.zmt.core.common.tuiClickable
import androidx.compose.runtime.LaunchedEffect
import dev.abhi.zmt.data.repository.YtHomePlaylist
import dev.abhi.zmt.data.repository.YtHomeArtist
import dev.abhi.zmt.domain.model.Album
import dev.abhi.zmt.domain.model.Playlist
import dev.abhi.zmt.domain.model.Artist
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.TrackSource
import dev.abhi.zmt.domain.model.asCredit
import dev.abhi.zmt.presentation.player.DmtView
import dev.abhi.zmt.presentation.library.artistLine2
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.ui.theme.TuiBright
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFg
import dev.abhi.zmt.ui.theme.TuiLine
import dev.abhi.zmt.ui.theme.TuiRaised

@Composable
fun HomePane(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap,
    onOpenAlbum: (String) -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenArtists: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    if (state.scanning && state.tracks.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.scanning),
                style = MaterialTheme.typography.bodyLarge,
                color = TuiDim,
            )
        }
        return
    }
    if (state.settings.sourceMode == SourceMode.YOUTUBE) {
        LaunchedEffect(state.settings.youtubeCookie) {
            if (state.ytHome.tracks.isEmpty() && !state.ytHomeLoading) {
                dispatch(DmtAction.RefreshYtHome)
            }
        }
        YoutubeHomeSection(state = state, dispatch = dispatch, art = art)
        return
    }

    if (state.tracks.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.no_audio, state.settings.sourceMode.label),
                style = MaterialTheme.typography.bodyLarge,
                color = TuiDim,
            )
        }
        return
    }


    val home = state.home

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (home.hasFavourites) {
            Text(
                text = stringResource(R.string.home_favourites),
                style = MaterialTheme.typography.titleLarge,
                color = TuiBright,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
        }

        if (home.albums.isNotEmpty()) {
            ShelfHeader(label = stringResource(R.string.home_albums), onMore = onOpenAlbums)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(home.albums, key = { it.name }) { album ->
                    AlbumCard(album = album, art = art, artKey = state.settings.rawArt) {
                        onOpenAlbum(album.name)
                    }
                }
            }
        }


        if (home.playlists.isNotEmpty()) {
            ShelfHeader(label = "playlists", onMore = onOpenPlaylists)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(home.playlists, key = { it.name }) { playlist ->
                    PlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist.name) })
                }
            }
        }

        if (home.artists.isNotEmpty()) {
            ShelfHeader(label = stringResource(R.string.home_artists), onMore = onOpenArtists)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(home.artists, key = { it.name }) { artist ->
                    ArtistCard(artist = artist, art = art, artKey = state.settings.rawArt) {
                        onOpenArtist(artist.name)
                    }
                }
            }
        }

        if (home.tracks.isNotEmpty()) {
            ShelfHeader(label = stringResource(R.string.home_tracks), onMore = onOpenTracks)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(home.tracks, key = { _, track -> track.id }) { index, track ->
                    TrackCard(track = track, art = art, artKey = state.settings.rawArt) {
                        dispatch(DmtAction.PlayAt(home.tracks, index))
                    }
                }
            }
        }

        if (home.recentlyPlayed.isNotEmpty()) {
            HorizontalDivider(
                color = TuiLine,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                text = stringResource(R.string.home_recently_played),
                style = MaterialTheme.typography.titleLarge,
                color = TuiBright,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            ShelfHeader(label = stringResource(R.string.home_tracks), onMore = onOpenTracks)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(home.recentlyPlayed, key = { _, track -> track.id }) { index, track ->
                    TrackCard(track = track, art = art, artKey = state.settings.rawArt) {
                        dispatch(DmtAction.PlayAt(home.recentlyPlayed, index))
                    }
                }
            }
        }

        if (home.fresh.isNotEmpty()) {
            if (home.hasFavourites) {
                HorizontalDivider(
                    color = TuiLine,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            Text(
                text = stringResource(R.string.home_try_new),
                style = MaterialTheme.typography.titleLarge,
                color = TuiBright,
                modifier = Modifier.padding(top = 14.dp),
            )
            Caption(stringResource(R.string.home_try_new_hint))
            ShelfHeader(label = stringResource(R.string.home_tracks), onMore = onOpenTracks)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(home.fresh, key = { _, track -> track.id }) { index, track ->
                    TrackCard(track = track, art = art, artKey = state.settings.rawArt) {
                        dispatch(DmtAction.PlayAt(home.fresh, index))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ShelfHeader(label: String, onMore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = TuiBright,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = ">",
            style = MaterialTheme.typography.labelLarge,
            color = TuiDim,
            modifier = Modifier
                .tuiClickable(onMore)
                .padding(horizontal = 8.dp),
        )
    }
}


@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.width(116.dp).tuiClickable(onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, TuiLine)
                .background(TuiRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${playlist.tracks.size}",
                style = MaterialTheme.typography.headlineMedium,
                color = TuiDim,
            )
        }
        Text(
            text = playlist.name.take(18),
            style = MaterialTheme.typography.labelMedium,
            color = TuiBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    ShelfCard(
        seed = album.tracks.first(),
        title = album.name,
        meta = album.artist,
        art = art,
        artKey = artKey,
        onClick = onClick,
    )
}

@Composable
private fun ArtistCard(
    artist: Artist,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    ShelfCard(
        seed = artist.tracks.first(),
        title = artist.name,
        meta = artistLine2(artist),
        art = art,
        artKey = artKey,
        onClick = onClick,
    )
}

@Composable
private fun TrackCard(
    track: Track,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    ShelfCard(
        seed = track,
        title = track.title,
        meta = track.artist.asCredit(),
        art = art,
        artKey = artKey,
        onClick = onClick,
    )
}

@Composable
private fun ShelfCard(
    seed: Track,
    title: String,
    meta: String,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    val cover by produceState<Bitmap?>(initialValue = null, seed.id, artKey) {
        value = art(seed)
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
                .aspectRatio(1f),
        ) {
            cover?.let {
                AsciiCover(
                    cover = it,
                    playing = false,
                    wave = false,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = TuiBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .padding(top = 6.dp),
        )
        Text(
            text = meta.lowercase().ifBlank { " " },
            style = MaterialTheme.typography.labelMedium,
            color = TuiDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun YoutubeHomeSection(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: suspend (Track) -> Bitmap,
) {
    val ytHome = state.ytHome

    if (state.ytHomeLoading && ytHome.tracks.isEmpty() && ytHome.playlists.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        ) {
            Text(
                text = "loading youtube music feed...",
                style = MaterialTheme.typography.bodyLarge,
                color = TuiDim,
            )
        }
        return
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = if (!state.settings.youtubeCookie.isNullOrBlank()) "for you" else "discover",
            style = MaterialTheme.typography.titleLarge,
            color = TuiBright,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )

        if (ytHome.playlists.isNotEmpty()) {
            ShelfHeader(label = "playlists & mixes") {
                dispatch(DmtAction.Show(DmtView.LIBRARY))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ytHome.playlists, key = { it.browseId }) { pl ->
                    YtPlaylistCard(
                        playlist = pl,
                        art = art,
                        artKey = state.settings.rawArt,
                    ) {
                        dispatch(DmtAction.OpenYtPlaylist(pl.browseId, pl.title, pl.coverUrl))
                    }

                }
            }
        }
        ytHome.shelves.forEach { shelf ->
            ShelfHeader(label = shelf.title) {
                dispatch(DmtAction.Show(DmtView.LIBRARY))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shelf.playlists, key = { "${shelf.title}_${it.browseId}" }) { pl ->
                     YtPlaylistCard(
                        playlist = pl,
                        art = art,
                        artKey = state.settings.rawArt,
                    ) {
                        dispatch(DmtAction.OpenYtPlaylist(pl.browseId, pl.title, pl.coverUrl))
                    }
                }
            }
        }

        if (ytHome.tracks.isNotEmpty()) {
            ShelfHeader(label = "recommended tracks") {
                dispatch(DmtAction.Show(DmtView.LIBRARY))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(ytHome.tracks, key = { _, track -> track.id }) { index, track ->
                    TrackCard(track = track, art = art, artKey = state.settings.rawArt) {
                        dispatch(DmtAction.PlayAt(ytHome.tracks, index))
                    }
                }
            }
        }

        if (ytHome.artists.isNotEmpty()) {
            ShelfHeader(label = "artists") {
                dispatch(DmtAction.Show(DmtView.LIBRARY))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ytHome.artists, key = { it.name }) { artist ->
                    YtArtistCard(
                        artist = artist,
                        art = art,
                        artKey = state.settings.rawArt,
                    ) {
                        val bId = artist.browseId
                        if (bId != null) {
                            dispatch(DmtAction.OpenYtArtist(bId, artist.name, artist.coverUrl))
                        } else {
                            dispatch(DmtAction.Query(artist.name))
                            dispatch(DmtAction.Show(DmtView.LIBRARY))
                            dispatch(DmtAction.Search)
                        }
                    }
                }
            }
        }

        if (state.home.recentlyPlayed.isNotEmpty()) {
            HorizontalDivider(color = TuiLine, modifier = Modifier.padding(top = 18.dp))
            Text(
                text = stringResource(R.string.home_recently_played),
                style = MaterialTheme.typography.titleLarge,
                color = TuiBright,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.home.recentlyPlayed, key = { _, track -> track.id }) { index, track ->
                    TrackCard(track = track, art = art, artKey = state.settings.rawArt) {
                        dispatch(DmtAction.PlayAt(state.home.recentlyPlayed, index))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
@Composable
private fun YtPlaylistCard(
    playlist: YtHomePlaylist,
    art: suspend (Track) -> Bitmap,
    artKey: Any,
    onClick: () -> Unit,
) {
    val cover by produceState<Bitmap?>(initialValue = null, playlist.browseId, playlist.coverUrl, artKey) {
        val url = playlist.coverUrl ?: return@produceState
        val seed = Track(
            id = playlist.browseId.hashCode().toLong(),
            uri = Uri.parse("youtube://playlist/${playlist.browseId}"),
            title = playlist.title,
            artist = playlist.subtitle,
            album = playlist.title,
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
                Text("[ playlist ]", style = MaterialTheme.typography.labelSmall, color = TuiDim)
            }
        }
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.bodyMedium,
            color = TuiBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 6.dp),
        )
        Text(
            text = playlist.subtitle.lowercase().ifBlank { "youtube music" },
            style = MaterialTheme.typography.labelMedium,
            color = TuiDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 2.dp, bottom = 8.dp),
        )
    }
}
@Composable
private fun YtArtistCard(
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



