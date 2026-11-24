package dev.abhi.zmt.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.abhi.zmt.R
import dev.abhi.zmt.core.common.Caption
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.data.remote.youtube.innertube.Innertube
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.presentation.player.DmtView
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFg
import dev.abhi.zmt.ui.theme.TuiLine

@Composable
fun SourceLoginPane(mode: SourceMode, state: DmtState, dispatch: (DmtAction) -> Unit) {
    when (mode) {
        SourceMode.TELEGRAM -> TelegramLoginPane(state, dispatch)
        SourceMode.YOUTUBE -> YoutubeWebViewLoginPane(state, dispatch)
        else -> JellyfinLoginPane(mode, dispatch)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeWebViewLoginPane(state: DmtState, dispatch: (DmtAction) -> Unit) {
    var captured by remember { mutableStateOf(false) }
    var redirectedToYtm by remember { mutableStateOf(false) }

    fun tryCaptureCookies(): Boolean {
        if (captured) return true
        val cm = CookieManager.getInstance()
        val ytmCookies = cm.getCookie("https://youtube.com").orEmpty()
        val ytCookies = cm.getCookie("https://youtube.com").orEmpty()
        val combined = listOf(ytmCookies, ytCookies)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        val isLoggedIn = combined.contains("SAPISID=") || combined.contains("__Secure-3PAPISID=")
        if (isLoggedIn) {
            captured = true
            cm.flush()
            Innertube.cookie = combined
            dispatch(
                DmtAction.Config(
                    state.settings.copy(
                        sourceMode = SourceMode.YOUTUBE,
                        youtubeCookie = combined,
                    )
                )
            )
            dispatch(DmtAction.Show(DmtView.HOME))
            return true
        }
        return false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Caption("youtube music sign in")
            Spacer(modifier = Modifier.weight(1f))
            TuiKey(
                label = "[ done ]",
                bright = true,
                onClick = {
                    if (!tryCaptureCookies()) {
                        dispatch(DmtAction.Show(DmtView.SOURCES))
                    }
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TuiKey(
                label = "[ cancel ]",
                onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (captured || url == null) return

                            // If Google finished sign-in on myaccount/accounts but stayed there, force-navigate to ://youtube.com
                            if (!redirectedToYtm && (url.startsWith("https://google.com") || url.contains("://google.com"))) {
                                redirectedToYtm = true
                                view?.loadUrl("https://youtube.com")
                                return
                            }

                            if (url.startsWith("https://youtube.com") || url.startsWith("https://youtube.com")) {
                                tryCaptureCookies()
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2F://youtube.com")
                }
            },
        )
    }
}


@Composable
private fun TelegramLoginPane(state: DmtState, dispatch: (DmtAction) -> Unit) {
    var phoneNumber by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var channelInput by remember { mutableStateOf("") }
    var showMissing by remember { mutableStateOf(false) }

    val authStep = state.telegramAuthStep
    val isScanning = state.scanning

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Caption(stringResource(R.string.source_login_title, SourceMode.TELEGRAM.label))

        when {
            authStep.isEmpty() || authStep == "phone" -> {
                LoginField(
                    label = stringResource(R.string.telegram_phone_label),
                    value = phoneNumber,
                    hint = stringResource(R.string.telegram_phone_hint),
                    onValueChange = { phoneNumber = it },
                    keyboardType = KeyboardType.Phone,
                )

                Row(modifier = Modifier.padding(top = 18.dp)) {
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                        onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TuiKey(
                        label = if (isScanning) "[ connecting... ]" else "[ ${stringResource(R.string.source_connect)} ]",
                        bright = !isScanning,
                        onClick = {
                            if (isScanning) return@TuiKey
                            if (phoneNumber.isBlank()) {
                                showMissing = true
                            } else {
                                showMissing = false
                                dispatch(DmtAction.TelegramSendPhone(phoneNumber.trim()))
                            }
                        },
                    )
                }

                if (isScanning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = TuiAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                        Text(
                            text = " connecting to telegram...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiDim,
                        )
                    }
                }
            }
            authStep == "code" -> {
                Text(
                    text = stringResource(R.string.telegram_code_sent),
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiDim,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LoginField(
                    label = stringResource(R.string.telegram_code_label),
                    value = code,
                    hint = stringResource(R.string.telegram_code_hint),
                    onValueChange = { code = it },
                    keyboardType = KeyboardType.Number,
                )

                Row(modifier = Modifier.padding(top = 18.dp)) {
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                        onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_connect)} ]",
                        bright = true,
                        onClick = {
                            if (code.isBlank()) {
                                showMissing = true
                            } else {
                                showMissing = false
                                dispatch(DmtAction.TelegramSubmitCode(code.trim()))
                            }
                        },
                    )
                }
            }
            authStep == "password" -> {
                Text(
                    text = "2FA password required",
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiDim,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LoginField(
                    label = stringResource(R.string.telegram_password_label),
                    value = password,
                    hint = stringResource(R.string.telegram_password_hint),
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    mask = true,
                )

                Row(modifier = Modifier.padding(top = 18.dp)) {
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                        onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_connect)} ]",
                        bright = true,
                        onClick = {
                            if (password.isBlank()) {
                                showMissing = true
                            } else {
                                showMissing = false
                                dispatch(DmtAction.TelegramSubmitPassword(password.trim()))
                            }
                        },
                    )
                }
            }
            authStep == "channel" -> {
                Text(
                    text = "enter channel username or id",
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiDim,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LoginField(
                    label = "channel",
                    value = channelInput,
                    hint = "@channel or numeric id",
                    onValueChange = { channelInput = it },
                )

                Row(modifier = Modifier.padding(top = 18.dp)) {
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                        onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TuiKey(
                        label = if (state.telegramSyncing) "[ syncing... ]" else "[ ${stringResource(R.string.source_connect)} ]",
                        bright = !state.telegramSyncing,
                        onClick = {
                            if (state.telegramSyncing) return@TuiKey
                            if (channelInput.isBlank()) {
                                showMissing = true
                            } else {
                                showMissing = false
                                dispatch(DmtAction.TelegramResolveChannel(channelInput.trim()))
                            }
                        },
                    )
                }

                if (state.telegramSyncing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = TuiAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                        Text(
                            text = " syncing channel...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiDim,
                        )
                    }
                }
            }
            authStep == "logged_in" -> {
                Text(
                    text = "logged in!",
                    style = MaterialTheme.typography.labelMedium,
                    color = TuiAccent,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LoginField(
                    label = "channel",
                    value = channelInput,
                    hint = "@channel or numeric id",
                    onValueChange = { channelInput = it },
                )

                Row(modifier = Modifier.padding(top = 18.dp)) {
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                        onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TuiKey(
                        label = "[ ${stringResource(R.string.source_connect)} ]",
                        bright = true,
                        onClick = {
                            if (channelInput.isBlank()) {
                                showMissing = true
                            } else {
                                showMissing = false
                                dispatch(DmtAction.TelegramResolveChannel(channelInput.trim()))
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                TuiKey(
                    label = "[ logout ]",
                    onClick = { dispatch(DmtAction.TelegramLogout) },
                )
            }
            authStep.startsWith("error") -> {
                Text(
                    text = authStep.removePrefix("error: "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiAccent,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TuiKey(
                    label = "[ try again ]",
                    onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
                )
            }
        }

        if (showMissing) {
            Text(
                text = stringResource(R.string.source_login_missing),
                style = MaterialTheme.typography.labelSmall,
                color = TuiAccent,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = TuiAccent,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun JellyfinLoginPane(mode: SourceMode, dispatch: (DmtAction) -> Unit) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showMissing by remember { mutableStateOf(false) }

    val submitLogin = {
        dispatch(DmtAction.SourceLogin(mode, url.trim(), username.trim(), password))
    }

    Column {
        Caption(stringResource(R.string.source_login_title, mode.label))

        LoginField(
            label = stringResource(R.string.source_url_label),
            value = url,
            hint = stringResource(R.string.source_url_hint),
            onValueChange = { url = it },
        )
        LoginField(
            label = stringResource(R.string.source_user_label),
            value = username,
            hint = stringResource(R.string.source_user_hint),
            onValueChange = { username = it },
        )
        LoginField(
            label = stringResource(R.string.source_pass_label),
            value = password,
            hint = stringResource(R.string.source_pass_hint),
            onValueChange = { password = it },
            keyboardType = KeyboardType.Password,
            mask = true,
        )

        Row(modifier = Modifier.padding(top = 18.dp)) {
            TuiKey(
                label = "[ ${stringResource(R.string.source_login_cancel)} ]",
                onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TuiKey(
                label = "[ ${stringResource(R.string.source_connect)} ]",
                bright = true,
                onClick = {
                    if (url.isBlank() || username.isBlank()) {
                        showMissing = true
                    } else {
                        showMissing = false
                        submitLogin()
                    }
                },
            )
        }

        if (showMissing) {
            Text(
                text = stringResource(R.string.source_login_missing),
                style = MaterialTheme.typography.labelSmall,
                color = TuiAccent,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    mask: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            Text(
                text = label.padEnd(6),
                style = MaterialTheme.typography.labelMedium,
                color = TuiDim,
            )
            Text(
                text = " > ",
                style = MaterialTheme.typography.bodyLarge,
                color = TuiAccent,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = TuiFg,
                    fontFeatureSettings = if (mask) "calt off" else null,
                ),
                cursorBrush = SolidColor(TuiAccent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (mask) {
                    PasswordVisualTransformation('*')
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TuiDim.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                },
            )
        }
        HorizontalDivider(color = TuiLine)
    }
}
