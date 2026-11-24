package dev.abhi.zmt.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.R
import dev.abhi.zmt.core.common.Caption
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.core.common.tuiClickable
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.domain.model.DmtSettings
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.presentation.player.DmtView
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiBright
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFaint
import dev.abhi.zmt.ui.theme.TuiFg
import dev.abhi.zmt.ui.theme.TuiLine

private data class SourceDescriptor(
    val mode: SourceMode,
    val label: String,
    val subtitle: (DmtSettings) -> String,
    val requiresAuth: Boolean,
    val connected: (DmtSettings) -> Boolean = { true },
    val logout: (DmtSettings) -> DmtSettings = { it },
)

private val SOURCE_REGISTRY = listOf(
    SourceDescriptor(
        mode = SourceMode.LOCAL,
        label = SourceMode.LOCAL.label,
        subtitle = { "files on this device" },
        requiresAuth = false,
    ),
    SourceDescriptor(
        mode = SourceMode.JELLYFIN,
        label = SourceMode.JELLYFIN.label,
        subtitle = { it.jellyfinUrl ?: "not connected" },
        requiresAuth = true,
        connected = { !it.jellyfinToken.isNullOrBlank() },
        logout = {
            it.copy(
                sourceMode = SourceMode.LOCAL,
                jellyfinUrl = null,
                jellyfinUserId = null,
                jellyfinToken = null,
            )
        },
    ),
    SourceDescriptor(
        mode = SourceMode.TELEGRAM,
        label = SourceMode.TELEGRAM.label,
        subtitle = { settings ->
            if (settings.telegramChannelName != null) {
                settings.telegramChannelName
            } else if (settings.telegramChannelId != null) {
                "channel #${settings.telegramChannelId}"
            } else {
                "not connected"
            }
        },
        requiresAuth = true,
        connected = { it.telegramChannelId != null },
        logout = {
            it.copy(
                sourceMode = SourceMode.LOCAL,
                telegramChannelId = null,
                telegramChannelName = null,
                telegramAuthState = null,
            )
        },
    ),
    SourceDescriptor(
        mode = SourceMode.YOUTUBE,
        label = SourceMode.YOUTUBE.label,
        subtitle = { settings ->
            if (!settings.youtubeCookie.isNullOrBlank()) "signed in via cookies" else "stream from YouTube Music (tap login for feed)"
        },
        requiresAuth = false,
        connected = { !it.youtubeCookie.isNullOrBlank() },
        logout = { it.copy(youtubeCookie = null) },
    ),
)

@Composable
fun SourcesPane(state: DmtState, dispatch: (DmtAction) -> Unit) {
    val settings = state.settings

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Caption(stringResource(R.string.sources_title))

        SOURCE_REGISTRY.forEach { source ->
            val connected = source.connected(settings)
            val needsGrant = source.mode == SourceMode.LOCAL && !state.hasPermission
            val supportsLogin = source.requiresAuth || source.mode == SourceMode.YOUTUBE
            SourceRow(
                label = source.label,
                subtitle = source.subtitle(settings),
                active = settings.sourceMode == source.mode,
                needsLogin = supportsLogin && !connected,
                onGrant = if (needsGrant) {
                    { dispatch(DmtAction.Show(DmtView.PERMISSIONS)) }
                } else {
                    null
                },
                onSelect = {
                    if (source.requiresAuth && !connected) {
                        dispatch(DmtAction.ShowLogin(source.mode))
                    } else {
                        dispatch(DmtAction.Config(settings.copy(sourceMode = source.mode)))
                    }
                },
                onLogin = if (supportsLogin && !connected) {
                    { dispatch(DmtAction.ShowLogin(source.mode)) }
                } else {
                    null
                },
                onLogout = if (supportsLogin && connected) {
                    {
                        if (source.mode == SourceMode.YOUTUBE) {
                            Innertube.cookie = null
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        }

                        dispatch(DmtAction.Config(source.logout(settings)))
                    }
                } else {
                    null
                },
            )
        }

        // Imported playlists entry
        val importedCount = state.playlists.count { it.name.startsWith("spotify") || it.name.startsWith("ytm") }
        if (importedCount > 0) {
            HorizontalDivider(color = TuiLine)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .tuiClickable { dispatch(DmtAction.Show(DmtView.IMPORTED)) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = "( ) ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TuiAccent,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "imported",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TuiBright,
                    )
                    Text(
                        text = "$importedCount playlists from spotify / ytm",
                        style = MaterialTheme.typography.labelSmall,
                        color = TuiDim,
                    )
                }
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelMedium,
                    color = TuiDim,
                )
            }
            HorizontalDivider(color = TuiLine)
        }

        Text(
            text = stringResource(R.string.sources_hint),
            style = MaterialTheme.typography.labelSmall,
            color = TuiFaint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SourceRow(
    label: String,
    subtitle: String,
    active: Boolean,
    needsLogin: Boolean,
    onSelect: () -> Unit,
    onLogin: (() -> Unit)? = null,
    onLogout: (() -> Unit)?,
    onGrant: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tuiClickable(onSelect),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = if (active) "(*) " else "( ) ",
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) TuiAccent else TuiFaint,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (active) TuiBright else TuiFg,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            onGrant?.let {
                TuiKey(
                    label = stringResource(R.string.grant),
                    onClick = it,
                )
            }
            if (needsLogin) {
                TuiKey(
                    label = "[ ${stringResource(R.string.source_login)} ]",
                    onClick = onLogin ?: onSelect,
                )
            }
            onLogout?.let {
                TuiKey(
                    label = "[ ${stringResource(R.string.source_logout)} ]",
                    onClick = it,
                )
            }
        }
        HorizontalDivider(color = TuiLine)
    }
}
