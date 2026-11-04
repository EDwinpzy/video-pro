package com.dongpiandi.voicehelper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore

class VoiceHelperSetupActivity : Activity() {
    private val configStore by lazy { AssistantConfigStore(this) }
    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var statusView: TextView
    private lateinit var conflictView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(buildContent())
        loadConfig()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) refreshStatus()
    }

    private fun buildContent(): View {
        val root =
            FrameLayout(this).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.rgb(7, 7, 11),
                            Color.rgb(16, 11, 24),
                            Color.rgb(7, 7, 11),
                        ),
                    )
            }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(72), dp(54), dp(72), dp(54))
            }
        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(scroll)

        column.addView(
            text(
                value = "VOICE ROUTER",
                size = 15f,
                color = ACCENT,
                bold = true,
            ).apply {
                letterSpacing = 0.14f
            },
        )
        column.addView(
            text(
                value = "全局语音助手",
                size = 42f,
                color = Color.WHITE,
                bold = true,
            ).apply {
                setPadding(0, dp(8), 0, 0)
            },
        )
        column.addView(
            text(
                value =
                    "输入框内只做原厂语音听写；没有输入框时使用原厂语音窗口识别，" +
                        "再由自定义大模型回答。",
                size = 21f,
                color = Color.argb(185, 255, 255, 255),
            ).apply {
                setPadding(0, dp(10), 0, dp(28))
            },
        )

        statusView =
            text(
                value = "",
                size = 19f,
                color = Color.argb(220, 255, 255, 255),
            ).apply {
                setPadding(dp(26), dp(23), dp(26), dp(23))
                background = cardBackground()
                setLineSpacing(dp(5).toFloat(), 1f)
            }
        column.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        conflictView =
            text(
                value = "",
                size = 18f,
                color = Color.rgb(255, 179, 64),
            ).apply {
                setPadding(dp(4), dp(14), dp(4), 0)
            }
        column.addView(conflictView)

        column.addView(
            sectionTitle("大模型接口").apply {
                setPadding(0, dp(28), 0, dp(12))
            },
        )
        baseUrlInput =
            input(
                hint = "OpenAI-compatible Base URL，例如 http://192.168.1.10:3425/v1",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                action = EditorInfo.IME_ACTION_NEXT,
            )
        column.addView(baseUrlInput, fieldParams())
        apiKeyInput =
            input(
                hint = "API Key（加密保存在本机）",
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                action = EditorInfo.IME_ACTION_DONE,
            )
        column.addView(apiKeyInput, fieldParams())
        column.addView(
            text(
                value =
                    "模型顺序固定为 group/free → auto/deepseek-v4.1-flash；" +
                        "首个模型失败会自动切换。密钥不会写入安装包或日志。",
                size = 16f,
                color = Color.argb(135, 255, 255, 255),
            ).apply {
                setPadding(dp(4), dp(2), dp(4), dp(16))
            },
        )

        val actionRow1 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
        actionRow1.addView(
            action("保存配置", primary = true) { saveConfiguration() },
            actionParams(),
        )
        actionRow1.addView(
            action("允许麦克风") { requestMicrophone() },
            actionParams(),
        )
        column.addView(actionRow1)

        val actionRow2 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(14), 0, 0)
            }
        actionRow2.addView(
            action("开启语音助手") { enableAccessibility() },
            actionParams(),
        )
        actionRow2.addView(
            action("无障碍设置") {
                VoiceHelperAccess.openSettings(this)
            },
            actionParams(),
        )
        actionRow2.addView(
            action("返回") { finish() },
            actionParams(),
        )
        column.addView(actionRow2)

        column.addView(
            text(
                value =
                    "设备要求：坚果 S621 / Android 13，安装包必须以 32 位 armeabi-v7a 运行。",
                size = 15f,
                color = Color.argb(110, 255, 255, 255),
            ).apply {
                setPadding(dp(4), dp(28), dp(4), dp(8))
            },
        )
        return root
    }

    private fun loadConfig() {
        val config =
            try {
                configStore.get()
            } catch (_: Exception) {
                Toast.makeText(this, "安全配置存储不可用", Toast.LENGTH_SHORT).show()
                return
            }
        baseUrlInput.setText(config.baseUrl)
        apiKeyInput.setText(config.apiKey)
    }

    private fun saveConfiguration() {
        val baseUrl = baseUrlInput.text?.toString().orEmpty()
        val apiKey = apiKeyInput.text?.toString().orEmpty()
        try {
            configStore.save(baseUrl, apiKey)
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            refreshStatus()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "配置保存失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "麦克风权限已允许", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    private fun enableAccessibility() {
        when (VoiceHelperAccess.requestEnable(this)) {
            VoiceHelperAccess.EnableRequest.ALREADY_ENABLED ->
                Toast.makeText(this, "全局语音助手已开启", Toast.LENGTH_SHORT).show()
            VoiceHelperAccess.EnableRequest.VENDOR_REQUESTED ->
                Toast.makeText(this, "已请求系统开启，请稍候检查状态", Toast.LENGTH_SHORT).show()
            VoiceHelperAccess.EnableRequest.SETTINGS_OPENED -> Unit
            VoiceHelperAccess.EnableRequest.UNAVAILABLE ->
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val microphoneGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val serviceEnabled = VoiceHelperAccess.isEnabled(this)
        val vendorReady = VoiceHelperAccess.canUseVendorEngine(this)
        val configured =
            try {
                configStore.isConfigured()
            } catch (_: Exception) {
                false
            }
        statusView.text =
            buildString {
                append(statusLine("麦克风", microphoneGranted))
                append('\n')
                append(statusLine("无障碍服务", serviceEnabled))
                append('\n')
                append(statusLine("原厂 AIUI", vendorReady))
                append('\n')
                append(statusLine("大模型配置", configured))
            }
        conflictView.text =
            if (VoiceHelperAccess.conflictingDictationEnabled(this)) {
                "检测到 Video Pro 的听写服务也处于开启状态。两个服务不要同时启用，否则会重复消费语音键。"
            } else {
                ""
            }
    }

    private fun statusLine(
        label: String,
        ready: Boolean,
    ): String = "${if (ready) "●" else "○"}  $label  ${if (ready) "就绪" else "未就绪"}"

    private fun input(
        hint: String,
        inputType: Int,
        action: Int,
    ): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            imeOptions = action
            setSingleLine(true)
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(105, 255, 255, 255))
            setPadding(dp(22), dp(15), dp(22), dp(15))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(24, 255, 255, 255))
                    setStroke(dp(1), Color.argb(60, 255, 255, 255))
                }
        }

    private fun action(
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        Color.rgb(12, 10, 16),
                        if (primary) Color.WHITE else Color.argb(235, 255, 255, 255),
                    ),
                ),
            )
            background = buttonBackground(primary)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { onClick() }
            isFocusable = true
            isFocusableInTouchMode = true
        }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun sectionTitle(value: String): TextView =
        text(value, 22f, Color.rgb(220, 105, 255), bold = true)

    private fun fieldParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }

    private fun actionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            rightMargin = dp(12)
            topMargin = dp(6)
            bottomMargin = dp(6)
        }

    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(28, 255, 255, 255))
            setStroke(dp(1), Color.argb(50, 213, 107, 255))
        }

    private fun buttonBackground(primary: Boolean): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(Color.WHITE)
                },
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(if (primary) ACCENT else Color.argb(28, 255, 255, 255))
                    if (!primary) setStroke(dp(1), Color.argb(65, 255, 255, 255))
                },
            )
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_MIC = 609
        val ACCENT: Int = Color.rgb(196, 79, 255)
    }
}
