package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The factory voice window remains the listening UI. Its closed-source implementation has no API
 * for injecting a custom answer, so the LLM response is presented in a deliberately similar,
 * accessibility-owned card.
 */
internal class VoiceAnswerOverlay(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var queryView: TextView? = null
    private var answerView: TextView? = null
    private var statusView: TextView? = null

    private val autoDismiss =
        Runnable {
            dismiss()
        }

    fun showThinking(query: String) {
        show(
            query = query,
            answer = "正在思考…",
            status = "语音助手",
        )
    }

    fun showAnswer(
        query: String,
        answer: String,
        model: String,
    ) {
        show(
            query = query,
            answer = answer,
            status = "语音助手 · $model",
        )
    }

    fun showError(query: String, message: String) {
        show(
            query = query,
            answer = message,
            status = "语音助手 · 连接失败",
        )
    }

    fun dismiss() {
        main.removeCallbacks(autoDismiss)
        val view = root ?: return
        root = null
        queryView = null
        answerView = null
        statusView = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun show(
        query: String,
        answer: String,
        status: String,
    ) {
        dismiss()
        val manager = windowManager ?: return
        val view = buildView()
        queryView?.text = query.takeIf(String::isNotBlank) ?: "正在聆听…"
        answerView?.text = answer
        statusView?.text = status
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                horizontalMargin = 0.08f
                verticalMargin = 0.08f
            }
        runCatching { manager.addView(view, params) }
            .onSuccess {
                root = view
                main.postDelayed(autoDismiss, DISMISS_AFTER_MS)
            }
    }

    private fun buildView(): View {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val card =
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(36), dp(28), dp(36), dp(30))
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(28).toFloat()
                        setColor(Color.argb(244, 12, 11, 17))
                        setStroke(dp(1), Color.rgb(118, 72, 184))
                    }
                elevation = dp(18).toFloat()
            }

        statusView =
            TextView(service).apply {
                setTextColor(Color.rgb(220, 105, 255))
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
        card.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        queryView =
            TextView(service).apply {
                setTextColor(Color.argb(190, 255, 255, 255))
                textSize = 20f
                maxLines = 2
                setPadding(0, dp(10), 0, dp(14))
            }
        card.addView(
            queryView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        answerView =
            TextView(service).apply {
                setTextColor(Color.WHITE)
                textSize = 27f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 7
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(4).toFloat(), 1f)
            }
        card.addView(
            answerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val hint =
            TextView(service).apply {
                text = "按返回键关闭"
                setTextColor(Color.argb(130, 255, 255, 255))
                textSize = 15f
                setPadding(0, dp(18), 0, 0)
            }
        card.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private companion object {
        const val DISMISS_AFTER_MS = 120_000L
    }
}
