package com.qiubo.optimaltv.dictation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qiubo.optimaltv.ui.components.OtvHint
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.theme.AccentBlue
import com.qiubo.optimaltv.ui.theme.OptimalTvTheme
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs

class DictationSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent {
            OptimalTvTheme {
                DictationSetupScreen(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun DictationSetupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val s = rememberUiScale()
    var refresh by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    val micGranted =
        remember(refresh) {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
    val serviceEnabled = remember(refresh) { SystemDictationAccess.isEnabled(context) }
    val vendorReady = remember(refresh) { SystemDictationAccess.canUseVendorEngine(context) }
    var waitingForVendorEnable by remember { mutableStateOf(false) }
    val firstAction = remember { FocusRequester() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh++
        }

    BackHandler(onBack = onClose)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        runCatching { firstAction.requestFocus() }
    }
    LaunchedEffect(waitingForVendorEnable) {
        if (!waitingForVendorEnable) return@LaunchedEffect
        repeat(20) {
            kotlinx.coroutines.delay(300)
            if (SystemDictationAccess.isEnabled(context)) {
                waitingForVendorEnable = false
                hint = "系统语音听写已开启"
                refresh++
                return@LaunchedEffect
            }
        }
        waitingForVendorEnable = false
        hint = "原厂系统未确认开启，请重试"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(OtvColors.Bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 96f.sx(s), vertical = 78f.sx(s)),
        ) {
            Text(
                "语音输入",
                color = OtvColors.White,
                fontSize = 42f.sxs(s),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12f.sx(s)))
            Text(
                "在任意输入框中，按住遥控器语音键说话，松开后文字会直接填入当前光标。",
                color = OtvColors.White50,
                fontSize = 23f.sxs(s),
            )
            Spacer(Modifier.height(38f.sx(s)))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x14FFFFFF), RoundedCornerShape(18.dp))
                    .padding(horizontal = 30f.sx(s), vertical = 26f.sx(s)),
                verticalArrangement = Arrangement.spacedBy(14f.sx(s)),
            ) {
                StatusLine("麦克风权限", micGranted, "已允许", "未允许")
                StatusLine("系统语音听写", serviceEnabled, "已开启", "未开启")
                StatusLine("原厂识别引擎", vendorReady, "可用", "当前设备不可用")
            }
            Spacer(Modifier.height(34f.sx(s)))

            Row(horizontalArrangement = Arrangement.spacedBy(22f.sx(s))) {
                SetupAction(
                    label = if (micGranted) "麦克风已允许" else "允许麦克风",
                    primary = !micGranted,
                    focusRequester = if (!micGranted) firstAction else null,
                ) {
                    if (!micGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                SetupAction(
                    label =
                        when {
                            serviceEnabled -> "系统语音听写（已开启）"
                            waitingForVendorEnable -> "正在开启…"
                            else -> "开启系统语音听写"
                        },
                    primary = micGranted && !serviceEnabled && !waitingForVendorEnable,
                    focusRequester = if (micGranted && !serviceEnabled) firstAction else null,
                ) {
                    if (serviceEnabled || waitingForVendorEnable) return@SetupAction
                    when (SystemDictationAccess.requestEnable(context)) {
                        SystemDictationAccess.EnableRequest.ALREADY_ENABLED -> {
                            hint = "系统语音听写已开启"
                            refresh++
                        }
                        SystemDictationAccess.EnableRequest.VENDOR_REQUESTED -> {
                            hint = "已请求原厂系统开启，正在等待"
                            waitingForVendorEnable = true
                        }
                        SystemDictationAccess.EnableRequest.SETTINGS_OPENED -> Unit
                        SystemDictationAccess.EnableRequest.UNAVAILABLE -> {
                            hint = "无法打开系统语音听写设置"
                        }
                    }
                }
            }
            Spacer(Modifier.height(30f.sx(s)))
            Text(
                "系统限制要求手动开启一次无障碍服务。开启后对所有应用的输入框生效，不会打开语音助手。",
                color = OtvColors.White50,
                fontSize = 19f.sxs(s),
            )
            Spacer(Modifier.weight(1f))
            SetupAction(
                label = "返回",
                primary = false,
                focusRequester = if (micGranted && serviceEnabled) firstAction else null,
            ) {
                onClose()
            }
        }
        OtvHint(hint)
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(2_500)
            hint = null
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    ready: Boolean,
    readyText: String,
    missingText: String,
) {
    val s = rememberUiScale()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(10f.sx(s))
                .height(10f.sx(s))
                .background(
                    if (ready) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.width(14f.sx(s)))
        Text(
            label,
            color = OtvColors.White,
            fontSize = 23f.sxs(s),
            modifier = Modifier.width(230f.sx(s)),
        )
        Text(
            if (ready) readyText else missingText,
            color = if (ready) OtvColors.White50 else Color(0xFFFFB340),
            fontSize = 21f.sxs(s),
        )
    }
}

@Composable
private fun SetupAction(
    label: String,
    primary: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val s = rememberUiScale()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .dpadFocusable(
                    externalFocusRequester = focusRequester,
                    focusedBg = OtvColors.White,
                    focusedBgRadius = 14.dp,
                    onFocusedChange = { focused = it },
                ) { onClick() }
                .background(
                    when {
                        focused -> OtvColors.White
                        primary -> AccentBlue
                        else -> Color(0x22FFFFFF)
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 28f.sx(s), vertical = 18f.sx(s)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF0E0E0F) else OtvColors.White,
            fontSize = 22f.sxs(s),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
