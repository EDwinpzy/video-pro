package com.qiubo.optimaltv.auth

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.SystemEditText
import com.qiubo.optimaltv.ui.components.VideoProStartupLoading
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.rememberSystemEditTextHandle
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object LoginInputMode {
    @Volatile
    var active: Boolean = false
}

@Composable
fun DongpianLoginGate(content: @Composable (DongpianAuthSession, () -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by DongpianAuthClient.sessionState.collectAsStateWithLifecycle()
    val sessionRevision by DongpianAuthClient.sessionRevisionState.collectAsStateWithLifecycle()
    val notice by DongpianAuthClient.notice.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf(true) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    var restoreAttempt by remember { mutableIntStateOf(0) }
    var canRetryRestore by remember { mutableStateOf(false) }
    var preparedRevision by remember(sessionRevision, session?.userId) { mutableStateOf<Long?>(null) }
    var syncAttempt by remember(sessionRevision) { mutableIntStateOf(0) }
    var syncError by remember(sessionRevision) { mutableStateOf<String?>(null) }

    LaunchedEffect(restoreAttempt) {
        restoring = true
        restoreMessage = null
        canRetryRestore = false
        val hadStoredSession = DongpianAuthClient.hasStoredSession(context)
        try {
            DongpianAuthClient.restore(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            restoreMessage = failure.message ?: "无法恢复登录状态，请重试。"
            canRetryRestore =
                hadStoredSession && DongpianAuthClient.hasStoredSession(context)
        } finally {
            restoring = false
        }
    }

    LaunchedEffect(sessionRevision, session?.userId, syncAttempt) {
        session ?: return@LaunchedEffect
        val revision = sessionRevision ?: return@LaunchedEffect
        syncError = null
        try {
            prepareAccountStartup(
                prepareLocal = { Graph.officialLibrary.prepareLocalAccount(Graph.db, revision) },
                onPrepared = {
                    preparedRevision = revision
                    com.qiubo.optimaltv.OtvLog.i("account local content prepared; remote sync continues")
                },
                syncRemote = { Graph.officialLibrary.syncToRoom(Graph.db, force = true) },
                onSyncFailure = {
                    syncError = it.message ?: "收藏与历史同步失败"
                    com.qiubo.optimaltv.OtvLog.w("后台片库同步失败；保留当前账号本地内容")
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.qiubo.optimaltv.OtvLog.w("登录后同步收藏/历史失败: ${failure.message}")
            syncError = failure.message ?: "收藏与历史同步失败"
        }
    }

    DisposableEffect(session) {
        LoginInputMode.active = session == null
        onDispose { LoginInputMode.active = false }
    }

    when {
        restoring -> LoginLoading()
        session == null && canRetryRestore ->
            RestoreFailureScreen(
                message = restoreMessage ?: "无法恢复登录状态，请重试。",
                onRetry = { restoreAttempt += 1 },
                onUsePassword = {
                    canRetryRestore = false
                    scope.launch { DongpianAuthClient.logout(context) }
                },
            )
        session == null ->
            DongpianLoginScreen(
                initialMessage = restoreMessage ?: notice,
            )
        syncError != null && !accountContentIsPrepared(sessionRevision, preparedRevision) ->
            RestoreFailureScreen(
                message = syncError.orEmpty(),
                onRetry = { syncAttempt += 1 },
                onUsePassword = { scope.launch { DongpianAuthClient.logout(context) } },
                title = "收藏与历史同步失败",
                retryLabel = "重试同步",
            )
        !accountContentIsPrepared(sessionRevision, preparedRevision) ->
            LoginLoading()
        else -> {
            val current = session ?: return
            content(current) {
                scope.launch { DongpianAuthClient.logout(context) }
            }
        }
    }
}

@Composable
private fun DongpianLoginScreen(
    initialMessage: String?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val rememberLoginFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val accountInput = rememberSystemEditTextHandle()
    val passwordInput = rememberSystemEditTextHandle()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberLogin by remember { mutableStateOf(true) }
    var credentialsReady by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialMessage) }
    // Traversal state is an implementation detail. Keeping it outside Compose state avoids
    // recomposing the full-screen gradient and card for every D-pad focus move.
    val focusIndex = remember { intArrayOf(0) }

    fun requestLoginFocus(index: Int, showKeyboard: Boolean = true) {
        val target = index.coerceIn(0, 3)
        focusIndex[0] = target
        when (target) {
            0 -> accountInput.requestFocus(showKeyboard)
            1 -> passwordInput.requestFocus(showKeyboard)
            2 -> {
                accountInput.hideKeyboard()
                passwordInput.hideKeyboard()
                rememberLoginFocus.requestFocus()
            }
            else -> {
                accountInput.hideKeyboard()
                passwordInput.hideKeyboard()
                submitFocus.requestFocus()
            }
        }
    }

    fun setRememberLogin(enabled: Boolean) {
        rememberLogin = enabled
        if (enabled) return
        scope.launch {
            try {
                DongpianAuthClient.forgetRememberedLogin(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "无法清除已保存账号。"
            }
        }
    }

    fun submit() {
        accountInput.hideKeyboard()
        passwordInput.hideKeyboard()
        if (submitting) return
        if (account.trim().isEmpty() || password.isEmpty()) {
            message = "请输入账号和密码。"
            return
        }
        submitting = true
        message = null
        scope.launch {
            try {
                DongpianAuthClient.login(
                    context = context,
                    account = account,
                    password = password,
                    rememberCredentials = rememberLogin,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "登录失败，请稍后重试。"
            } finally {
                submitting = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val saved =
            try {
                DongpianAuthClient.rememberedLogin(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "无法读取已保存账号，请手动登录。"
                credentialsReady = true
                return@LaunchedEffect
            }
        if (saved == null) {
            credentialsReady = true
            return@LaunchedEffect
        }
        account = saved.account
        password = saved.password
        rememberLogin = true
        try {
            DongpianAuthClient.login(
                context = context,
                account = saved.account,
                password = saved.password,
                rememberCredentials = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message = failure.message ?: "自动登录失败，请重新输入密码。"
            credentialsReady = true
        }
    }

    if (!credentialsReady) {
        LoginLoading()
        return
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF45101A), Color(0xFF110609), Color(0xFF050507)),
                    ),
                )
                .tvReducedSemantics(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(560.dp)
                    .background(Color(0xE617171C), RoundedCornerShape(28.dp))
                    .height(560.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) {
                            return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            requestLoginFocus(
                                focusIndex[0] + if (event.key == Key.DirectionDown) 1 else -1,
                            )
                        }
                        // Consume both halves so the focused text field cannot also
                        // run its own traversal for the same key.
                        true
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Video Pro",
                color = Color(0xFFFF5A68),
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text("使用懂片帝账号登录", color = Color(0xFFC8C8CF), fontSize = 20.sp)
            Spacer(Modifier.height(34.dp))
            SystemEditText(
                value = account,
                onValueChange = { account = it },
                hint = "账号",
                enabled = !submitting,
                handle = accountInput,
                imeAction = EditorInfo.IME_ACTION_NEXT,
                onImeAction = { requestLoginFocus(1) },
                onFocusChanged = { focused ->
                    if (focused) {
                        focusIndex[0] = 0
                        com.qiubo.optimaltv.StartupReadiness.onInitialFocus("login-account")
                    }
                },
                onKeyDown = { event ->
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            requestLoginFocus(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        -> {
                            accountInput.showKeyboard()
                            true
                        }
                        else -> false
                    }
                },
                textSizeSp = 20f,
                autoFocus = true,
                modifier =
                    Modifier
                        .width(420.dp)
                        .height(64.dp),
            )
            Spacer(Modifier.height(18.dp))
            SystemEditText(
                value = password,
                onValueChange = { password = it },
                hint = "密码",
                enabled = !submitting,
                handle = passwordInput,
                password = true,
                imeAction = EditorInfo.IME_ACTION_DONE,
                onImeAction = { submit() },
                onFocusChanged = { focused ->
                    if (focused) focusIndex[0] = 1
                },
                onKeyDown = { event ->
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            requestLoginFocus(0)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            requestLoginFocus(2)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        -> {
                            passwordInput.showKeyboard()
                            true
                        }
                        else -> false
                    }
                },
                textSizeSp = 20f,
                modifier =
                    Modifier
                        .width(420.dp)
                        .height(64.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier =
                    Modifier
                        .width(420.dp)
                        .height(46.dp)
                        .dpadFocusable(
                            enabled = !submitting,
                            scaleFocused = 1f,
                            focusedBg = Color(0x24FFFFFF),
                            focusedBgRadius = 10.dp,
                            drawRing = false,
                            externalFocusRequester = rememberLoginFocus,
                            onFocusedChange = { focused ->
                                if (focused) focusIndex[0] = 2
                            },
                            onClick = { setRememberLogin(!rememberLogin) },
                        )
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rememberLogin,
                    onCheckedChange = ::setRememberLogin,
                    enabled = !submitting,
                    modifier = Modifier.focusProperties { canFocus = false },
                    colors =
                        CheckboxDefaults.colors(
                            checkedColor = Color(0xFFE84453),
                            uncheckedColor = Color(0xFFC8C8CF),
                            checkmarkColor = Color.White,
                            disabledCheckedColor = Color(0xFF743139),
                            disabledUncheckedColor = Color(0xFF6D6D74),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "记住账号密码，下次自动登录",
                    color = if (submitting) Color(0xFF8E8E95) else Color(0xFFE6E6EA),
                    fontSize = 17.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            LoginSubmitButton(
                onClick = { submit() },
                enabled = !submitting,
                submitting = submitting,
                modifier = Modifier.focusRequester(submitFocus),
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier.height(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                message?.let {
                    Text(it, color = Color(0xFFFFA3AA), fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun LoginSubmitButton(
    onClick: () -> Unit,
    enabled: Boolean,
    submitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            Modifier
                .width(420.dp)
                .height(54.dp)
                .background(
                    color = if (enabled) Color(0xFFE84453) else Color(0xFF743139),
                    shape = shape,
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else Color.Transparent,
                    shape = shape,
                )
                .then(modifier)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(22.dp).width(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text("登录", fontSize = 20.sp)
        }
    }
}

@Composable
private fun RestoreFailureScreen(
    message: String,
    onRetry: () -> Unit,
    onUsePassword: () -> Unit,
    title: String = "无法恢复懂片帝登录",
    retryLabel: String = "重试恢复登录",
) {
    val retryFocus = remember { FocusRequester() }
    InitialFocusEffect(retryFocus, "restore-retry")
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF45101A), Color(0xFF110609), Color(0xFF050507)),
                    ),
                )
                .tvReducedSemantics(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(560.dp)
                    .background(Color(0xE617171C), RoundedCornerShape(28.dp))
                    .padding(horizontal = 54.dp, vertical = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color(0xFFFF5A68),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            Text(message, color = Color(0xFFC8C8CF), fontSize = 18.sp)
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.width(420.dp).height(54.dp).focusRequester(retryFocus),
            ) {
                Text(retryLabel, fontSize = 20.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onUsePassword,
                modifier = Modifier.width(420.dp).height(54.dp),
            ) {
                Text("改用账号密码登录", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun LoginLoading() {
    VideoProStartupLoading()
}
