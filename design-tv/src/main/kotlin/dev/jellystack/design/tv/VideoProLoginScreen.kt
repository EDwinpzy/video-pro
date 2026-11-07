@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
internal fun VideoProLoginScreen(
    strings: VideoProStrings,
    state: VideoProLoginState,
    onLogin: (account: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accountFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    var account = remember { androidx.compose.runtime.mutableStateOf("") }
    var password = remember { androidx.compose.runtime.mutableStateOf("") }

    LaunchedEffect(Unit) {
        accountFocus.requestFocus()
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF650012), Color(0xFF1B0308), Color(0xFF060102)),
                        radius = 1_200f,
                    ),
                ).padding(TvScreenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(96.dp),
        ) {
            VideoProLoginHero(strings, Modifier.weight(1f))
            VideoProLoginForm(
                strings = strings,
                state = state,
                inputs =
                    VideoProLoginFormInputs(
                        values =
                            VideoProLoginValues(
                                account = account.value,
                                password = password.value,
                            ),
                        callbacks =
                            VideoProLoginCallbacks(
                                onAccountChange = { account.value = it },
                                onPasswordChange = { password.value = it },
                                onLogin = onLogin,
                            ),
                        focus =
                            VideoProLoginFocus(
                                account = accountFocus,
                                password = passwordFocus,
                                submit = submitFocus,
                            ),
                    ),
            )
        }
    }
}

@Composable
private fun VideoProLoginHero(
    strings: VideoProStrings,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        VideoProBrandMark()
        Text(
            strings.brand,
            color = TvText,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            strings.loginSubtitle,
            color = TvTextMuted,
            fontSize = 21.sp,
            maxLines = 3,
        )
    }
}

@Composable
private fun VideoProLoginForm(
    strings: VideoProStrings,
    state: VideoProLoginState,
    inputs: VideoProLoginFormInputs,
) {
    Column(
        modifier =
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(TvSurface.copy(alpha = 0.94f))
                .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            strings.loginTitle,
            color = TvText,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = inputs.values.account,
            onValueChange = inputs.callbacks.onAccountChange,
            label = { Text(strings.accountLabel) },
            singleLine = true,
            enabled = !state.submitting,
            colors = tvOutlinedTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { inputs.focus.password.requestFocus() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(inputs.focus.account)
                    .testTag("video-pro-login-account")
                    .focusProperties { down = inputs.focus.password },
        )
        OutlinedTextField(
            value = inputs.values.password,
            onValueChange = inputs.callbacks.onPasswordChange,
            label = { Text(strings.passwordLabel) },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
            colors = tvOutlinedTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (!state.submitting) {
                            inputs.callbacks.onLogin(inputs.values.account, inputs.values.password)
                            inputs.focus.submit.requestFocus()
                        }
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(inputs.focus.password)
                    .testTag("video-pro-login-password")
                    .focusProperties {
                        up = inputs.focus.account
                        down = inputs.focus.submit
                    },
        )
        TvActionButton(
            label = if (state.submitting) strings.loggingIn else strings.loginAction,
            onClick = { inputs.callbacks.onLogin(inputs.values.account, inputs.values.password) },
            modifier = Modifier.fillMaxWidth().testTag("video-pro-login-submit"),
            primary = true,
            enabled = !state.submitting,
            focusRequester = inputs.focus.submit,
        )
        state.failure?.let { failure ->
            TvStatusAnchor(failure.message(strings))
        }
    }
}

private class VideoProLoginFormInputs(
    val values: VideoProLoginValues,
    val callbacks: VideoProLoginCallbacks,
    val focus: VideoProLoginFocus,
)

private class VideoProLoginValues(
    val account: String,
    val password: String,
)

private class VideoProLoginCallbacks(
    val onAccountChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onLogin: (account: String, password: String) -> Unit,
)

private class VideoProLoginFocus(
    val account: FocusRequester,
    val password: FocusRequester,
    val submit: FocusRequester,
)
