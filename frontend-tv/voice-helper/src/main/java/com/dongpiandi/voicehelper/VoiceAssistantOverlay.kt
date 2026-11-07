package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * Helper-owned assistant surface. It is an accessibility overlay, so it never steals focus from
 * the app below and does not require SYSTEM_ALERT_WINDOW.
 */
internal class VoiceAssistantOverlay(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var pulse: VoicePulseView? = null
    private var stateLabel: TextView? = null
    private var titleView: TextView? = null
    private var transcriptView: TextView? = null
    private var questionView: TextView? = null
    private var answerView: TextView? = null
    private var footerView: TextView? = null

    val isShowing: Boolean
        get() = root != null

    fun showListening() {
        show()
        stateLabel?.text = "LISTENING / 聆听中"
        titleView?.text = "请开始说话"
        transcriptView?.text = "按住遥控器语音键，说完后松开"
        questionView?.text = ""
        answerView?.text = ""
        footerView?.text = "原厂 AIUI 识别"
        setAccent(LISTENING_ACCENT)
        startPulse()
    }

    fun updateTranscript(text: String) {
        if (root == null) showListening()
        val value = text.trim()
        transcriptView?.text = value.ifEmpty { "正在识别…" }
        titleView?.text = "正在听"
    }

    fun showThinking(transcript: String) {
        show()
        stateLabel?.text = "THINKING / 思考中"
        titleView?.text = "正在组织回答"
        transcriptView?.text = ""
        questionView?.text = transcript.trim()
        answerView?.text = ""
        footerView?.text = "原厂识别 · 自定义大模型"
        setAccent(THINKING_ACCENT)
        stopPulse()
    }

    fun showAnswer(
        question: String,
        answer: String,
        model: String,
        usedWebSearch: Boolean,
    ) {
        show()
        stateLabel?.text = "ANSWER / 回答"
        titleView?.text = "回答"
        transcriptView?.text = ""
        questionView?.text = question.trim()
        answerView?.text = answer.trim().take(MAX_ANSWER_CHARS)
        footerView?.text =
            buildString {
                append("原厂 TTS")
                if (usedWebSearch) append(" · 已联网")
                if (model.isNotBlank()) append(" · ").append(model)
            }
        setAccent(ANSWER_ACCENT)
        stopPulse()
    }

    fun showError(message: String) {
        show()
        stateLabel?.text = "RETRY / 未完成"
        titleView?.text = "这次没有听清"
        transcriptView?.text = ""
        questionView?.text = ""
        answerView?.text = message.trim()
        footerView?.text = "再次按住语音键可重试"
        setAccent(ERROR_ACCENT)
        stopPulse()
    }

    fun dismiss() {
        stopPulse()
        val view = root ?: return
        root = null
        runCatching { windowManager?.removeView(view) }
    }

    fun close() {
        dismiss()
    }

    private fun show() {
        if (root != null) return
        val manager = windowManager ?: return
        val displayWidth = service.resources.displayMetrics.widthPixels
        val cardWidth =
            minOf((displayWidth * 0.82f).toInt(), dp(920))
                .coerceAtLeast(minOf(displayWidth - dp(48), dp(480)))
        val card = buildCard()
        val params =
            WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(72)
            }
        runCatching { manager.addView(card, params) }
            .onSuccess {
                root = card
                card.alpha = 0f
                card.translationY = dp(18).toFloat()
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start()
            }
    }

    private fun buildCard(): View =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(22))
            elevation = dp(18).toFloat()
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.argb(245, 7, 14, 22),
                        Color.argb(245, 14, 26, 38),
                        Color.argb(245, 6, 12, 19),
                    ),
                ).apply {
                    cornerRadius = dp(26).toFloat()
                    setStroke(dp(1), Color.argb(145, 100, 215, 255))
                }

            val header =
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            pulse =
                VoicePulseView(service).also { view ->
                    header.addView(
                        view,
                        LinearLayout.LayoutParams(dp(58), dp(30)).apply {
                            rightMargin = dp(14)
                        },
                    )
                }
            stateLabel =
                label(
                    text = "LISTENING / 聆听中",
                    size = 14f,
                    color = LISTENING_ACCENT,
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD),
                ).apply {
                    letterSpacing = 0.12f
                }
            header.addView(
                stateLabel,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                header,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            titleView =
                label(
                    text = "请开始说话",
                    size = 29f,
                    color = Color.WHITE,
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD),
                ).apply {
                    setPadding(0, dp(12), 0, 0)
                }
            addView(titleView)

            transcriptView =
                label(
                    text = "",
                    size = 24f,
                    color = Color.argb(235, 255, 255, 255),
                ).apply {
                    setPadding(0, dp(10), 0, dp(2))
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(dp(4).toFloat(), 1f)
                }
            addView(transcriptView)

            questionView =
                label(
                    text = "",
                    size = 19f,
                    color = Color.argb(175, 199, 229, 241),
                ).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(12), 0, 0)
                }
            addView(questionView)

            answerView =
                label(
                    text = "",
                    size = 21f,
                    color = Color.argb(242, 255, 255, 255),
                ).apply {
                    setPadding(0, dp(12), 0, 0)
                    maxLines = 9
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(dp(4).toFloat(), 1f)
                }
            addView(answerView)

            footerView =
                label(
                    text = "原厂 AIUI 识别",
                    size = 15f,
                    color = Color.argb(125, 255, 255, 255),
                ).apply {
                    setPadding(0, dp(17), 0, 0)
                }
            addView(footerView)
        }

    private fun setAccent(color: Int) {
        val stroke =
            Color.argb(
                180,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        (root?.background as? GradientDrawable)?.setStroke(dp(1), stroke)
        stateLabel?.setTextColor(color)
        pulse?.setColor(color)
    }

    private fun startPulse() {
        pulse?.start()
    }

    private fun stopPulse() {
        pulse?.stop()
    }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        typeface: Typeface? = Typeface.create("sans-serif", Typeface.NORMAL),
    ): TextView =
        TextView(service).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            this.typeface = typeface
            includeFontPadding = false
        }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private class VoicePulseView(
        service: AccessibilityService,
    ) : View(service) {
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = LISTENING_ACCENT
            }
        private var animator: ValueAnimator? = null
        private var phase = 0f

        fun setColor(color: Int) {
            paint.color = color
            invalidate()
        }

        fun start() {
            if (animator?.isRunning == true) return
            animator =
                ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        fun stop() {
            animator?.cancel()
            animator = null
            phase = 0f
            invalidate()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bars = 5
            val gap = width * 0.055f
            val barWidth = (width - gap * (bars - 1)) / bars
            for (index in 0 until bars) {
                val wave = 0.34f + 0.66f * ((sin(phase + index * 0.85f) + 1f) / 2f)
                val center = index * (barWidth + gap) + barWidth / 2f
                val halfHeight = height * 0.44f * wave
                canvas.drawRoundRect(
                    center - barWidth / 2f,
                    height / 2f - halfHeight,
                    center + barWidth / 2f,
                    height / 2f + halfHeight,
                    barWidth / 2f,
                    barWidth / 2f,
                    paint,
                )
            }
        }
    }

    private companion object {
        const val MAX_ANSWER_CHARS = 700
        val LISTENING_ACCENT: Int = Color.rgb(100, 222, 255)
        val THINKING_ACCENT: Int = Color.rgb(255, 192, 92)
        val ANSWER_ACCENT: Int = Color.rgb(130, 235, 183)
        val ERROR_ACCENT: Int = Color.rgb(255, 132, 116)
    }
}
