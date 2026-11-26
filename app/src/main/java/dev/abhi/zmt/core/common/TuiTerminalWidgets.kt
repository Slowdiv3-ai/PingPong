package dev.abhi.zmt.core.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiBright
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFaint
import kotlinx.coroutines.delay

@Composable
fun TuiTypingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = TuiBright,
    charDelayMs: Long = 45L,
    showCursor: Boolean = true,
    maxLines: Int = 1,
) {
    var visibleCount by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        visibleCount = 0
        for (i in 1..text.length) {
            delay(charDelayMs)
            visibleCount = i
        }
    }
    val typing = visibleCount < text.length
    val shown = text.take(visibleCount) + if (showCursor && typing) "█" else ""
    Text(
        text = shown,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun PacmanLoader(
    label: String? = null,
    progress: Float? = null,
    slots: Int = 16,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "pacman")
    val autoPos by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pacPos",
    )
    val mouth by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 220, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pacMouth",
    )
    val fraction = (progress ?: autoPos).coerceIn(0f, 1f)
    val pacIndex = (fraction * (slots - 1)).toInt().coerceIn(0, slots - 1)
    val pacChar = if (mouth > 0.5f) "C" else "c"
    val pct = (fraction * 100).toInt()

    val bar = buildAnnotatedString {
        withStyle(SpanStyle(color = TuiDim)) { append("[") }
        for (i in 0 until slots) {
            when {
                i < pacIndex -> withStyle(SpanStyle(color = TuiFaint)) { append("-") }
                i == pacIndex -> withStyle(SpanStyle(color = TuiAccent, fontWeight = FontWeight.Bold)) { append(pacChar) }
                i % 2 == 0 -> withStyle(SpanStyle(color = TuiBright)) { append("•") }
                else -> append(" ")
            }
        }
        withStyle(SpanStyle(color = TuiDim)) { append("] $pct%") }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(text = bar, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (!label.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            TuiTypingText(
                text = "> $label...",
                style = MaterialTheme.typography.labelSmall,
                color = TuiDim,
                charDelayMs = 16L,
            )
        }
    }
}
@Composable
fun OdometerTimeText(
    time: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = TuiBright,
) {
    Row(modifier = modifier.clipToBounds()) {
        time.forEachIndexed { index, ch ->
            if (ch.isDigit()) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (slideInVertically(tween(480, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(360)))
                            .togetherWith(slideOutVertically(tween(480, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(360)))
                    },
                    label = "digit_$index",
                ) { digit ->
                    Text(
                        text = digit.toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = ch.toString(),
                    style = style,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}
@Composable
fun PacmanSeekBar(
    fraction: Float,
    isPlaying: Boolean,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "seekPacman")
    val mouth by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 240, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "seekMouth",
    )
    val pacChar = if (isPlaying && mouth > 0.5f) "c" else "C"

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek(f)
                }
            }
            .pointerInput(Unit) {
                var dragFraction = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onScrub(dragFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onScrub(dragFraction)
                    },
                    onDragEnd = {
                        onSeek(dragFraction)
                    },
                    onDragCancel = {
                        onScrub(null)
                    },
                )
            },
    ) {
        // Stretch full width from [ to ] using larger bodyMedium monospace glyphs (~8.4dp/char)
        val slots = ((maxWidth.value / 8.4f).toInt() - 2).coerceIn(18, 48)
        val clamped = fraction.coerceIn(0f, 1f)
        val pacIndex = (clamped * (slots - 1)).toInt().coerceIn(0, slots - 1)

        val bar = buildAnnotatedString {
            withStyle(SpanStyle(color = TuiDim, fontWeight = FontWeight.Bold)) { append("[") }
            for (i in 0 until slots) {
                when {
                    i < pacIndex -> withStyle(SpanStyle(color = TuiFaint, fontWeight = FontWeight.Bold)) { append("-") }
                    i == pacIndex -> withStyle(SpanStyle(color = TuiAccent, fontWeight = FontWeight.Bold)) { append(pacChar) }
                    i % 2 == 0 -> withStyle(SpanStyle(color = TuiBright)) { append("•") }
                    else -> append(" ")
                }
            }
            withStyle(SpanStyle(color = TuiDim, fontWeight = FontWeight.Bold)) { append("]") }
        }

        Text(
            text = bar,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

