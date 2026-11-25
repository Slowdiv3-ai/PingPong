package dev.abhi.zmt.presentation.player
import dev.abhi.zmt.R

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.core.common.OdometerTimeText
import dev.abhi.zmt.core.common.CursorTitle
import dev.abhi.zmt.core.common.Hairline
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.domain.model.Track
import dev.abhi.zmt.domain.model.asCredit
import dev.abhi.zmt.ui.theme.TuiBg
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFaint
import dev.abhi.zmt.ui.theme.TuiLine
import dev.abhi.zmt.ui.theme.TuiRed
import dev.abhi.zmt.ui.theme.TuiRaised
import dev.abhi.zmt.util.asTime

@Composable
fun MiniPlayer(
    state: DmtState,
    dispatch: (DmtAction) -> Unit,
    art: (suspend (Track) -> Bitmap?)? = null,
) {
    val fraction =
        if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Hairline(fraction)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            // Artwork thumbnail
            if (art != null || state.cover != null || state.artRaw != null) {
                val track = state.currentTrack
                val loaded by produceState<Bitmap?>(initialValue = null, state.nowPlayingId, track?.coverUri) {
                    value = if (art != null && track != null) runCatching { art(track) }.getOrNull() else null
                }
                val cover = state.artRaw ?: state.cover ?: loaded
                cover?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.album_art),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, TuiLine),
                    )
                } ?: Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(1.dp, TuiLine)
                        .background(TuiRaised),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
            ) {
                CursorTitle(
                    text = state.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val position = state.positionMs.asTime()
                val duration = state.durationMs.asTime()
                if (state.fault != null) {
                    Text(
                        text = state.fault,
                        style = MaterialTheme.typography.labelSmall,
                        color = TuiRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${state.artist.asCredit()} · ".lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        OdometerTimeText(
                            time = position,
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiDim,
                        )
                        Text(
                            text = "/$duration",
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiDim,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = "^",
                style = MaterialTheme.typography.labelMedium,
                color = TuiFaint,
                modifier = Modifier.padding(end = 10.dp),
            )
            TuiKey(if (state.isPlaying) "||" else "|>") { dispatch(DmtAction.TogglePlay) }
            Spacer(modifier = Modifier.width(8.dp))
            TuiKey(">>|") { dispatch(DmtAction.Next) }
        }
    }
}
