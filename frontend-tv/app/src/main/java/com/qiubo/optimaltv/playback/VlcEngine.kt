package com.qiubo.optimaltv.playback

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.qiubo.optimaltv.OtvLog
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

internal object VlcFirstFramePolicy {
    /** Vout proves a video-output pipeline exists; only displayedPictures proves a visible frame. */
    fun isDisplayed(voutEstablished: Boolean, displayedPictures: Int): Boolean =
        voutEstablished && displayedPictures > 0
}

internal object VlcPauseStatePolicy {
    /** An early user pause can arrive without a prior Playing callback. */
    fun stateAfterPause(current: EnginePlayState): EnginePlayState =
        if (current == EnginePlayState.BUFFERING) EnginePlayState.READY else current
}

/**
 * libVLC exposes dedicated HTTP options instead of arbitrary Media3 request headers. Preserve
 * the authentication and anti-hotlink fields supported by libVLC, and prefer the explicit user
 * agent over a duplicate header entry.
 */
internal object VlcRequestOptionsPolicy {
    fun requestOptions(
        headers: Map<String, String>,
        userAgent: String,
    ): List<String> {
        fun header(name: String): String? =
            headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
                ?.value
                ?.takeIf(String::isNotBlank)

        return listOfNotNull(
            userAgent.takeIf(String::isNotBlank)
                ?.let { ":http-user-agent=$it" }
                ?: header("User-Agent")?.let { ":http-user-agent=$it" },
            header("Referer")?.let { ":http-referrer=$it" },
            header("Cookie")?.let { ":http-cookies=$it" },
        )
    }
}

/**
 * libVLC 兜底引擎（v1.8 2026-08-30，直播解码失败三段回退的最后一棒）：
 *
 * 背景：MuMu/部分盒子的 MediaCodec 生态里「硬解」OMX.qcom.video.decoder.avc 内部
 * 实际包的是上古 SoftAVCDec 软件内核，遇到 AVC High 压段（8x8 变换 + CABAC，
 * bb 原版足球直播2 的 CDN 压片特征）直接 Fatal Error 0xffffffff；软解
 * OMX.google.h264.decoder 是同一内核同样必挂——MediaCodec 路线全线无解。
 * VLC 自带完整 ffmpeg 解码器，且按媒体关闭其 mediacodec 硬解（否则又会踩回
 * 同一批坏 codec），因此在本引擎里永远走自带 avcodec 软解，任何设备都能出画面。
 *
 * 引擎定位：仅直播 DECODING_FAILED 后自动切入（PlayerViewModel 回退链），
 * 正常设备硬解顺畅时不会用到，不给用户当默认引擎选。
 */
class VlcEngine(context: Context) : MediaEngine {

    override val engineId = "vlc"
    override val displayName = "libVLC 兜底"

    private val appCtx: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** VLC 的 stop/release 是阻塞调用，必须在工作线程串行执行（事件线程/主线程调用会死锁） */
    private val vlcOps = Executors.newSingleThreadExecutor { r -> Thread(r, "otv-vlc-ops").apply { isDaemon = true } }
    private val released = AtomicBoolean(false)
    private val _ui = MutableStateFlow(EngineUiState())
    override val uiState: StateFlow<EngineUiState> = _ui.asStateFlow()

    /** v1.17 IPTV 大缓冲档：network-caching 1.5s → 6s（远端源抖动平滑） */
    @Volatile private var largeBuffer = false

    // VLC player/libVLC 所有 JNI 操作都在 vlcOps 串行执行；volatile 只用于轻量的存活检查。
    @Volatile private var libvlc: LibVLC? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentHeaders: Map<String, String> = emptyMap()
    @Volatile private var currentUserAgent: String = ""
    @Volatile private var currentMimeType: String = ""
    @Volatile private var playRequested = true
    @Volatile private var requestedSpeed = 1f
    @Volatile private var voutEstablished = false
    @Volatile private var firstFrameProbeJob: Job? = null
    /** Media.Stats.displayedPictures sampled on vlcOps; safe for the main-thread health monitor. */
    @Volatile private var displayedPicturesTotal = 0
    @Volatile private var displayedPicturesKnown = false
    /** Rejects queued work and listeners from a preparation that has already been superseded. */
    private val mediaGeneration = AtomicInteger(0)
    /** Serializes generation increments with final state commits after native/JNI reads. */
    private val generationLock = Any()
    /** Player generation is updated in the same VLC op that swaps media. */
    @Volatile private var playerGeneration = 0
    @Volatile private var ticker: Job? = null
    @Volatile private var surfaceReady = false
    @Volatile private var pendingPlayerForSurface: MediaPlayer? = null
    /** vout 只允许 attach 一次（2026-08-30 实测教训：直播结束追边重开时重复 attachViews
     *  抛 IllegalStateException「Can't set view when already attached」→ 重开失败卡 BUFFERING） */
    private var voutAttached = false
    @Volatile private var activeVout: IVLCVout? = null
    @Volatile private var surfaceCallback: IVLCVout.Callback? = null
    /** Last native window size is keyed by Vout so a late callback from a released player can't
     * suppress the initial size update for its replacement. */
    private val voutWindowSizeLock = Any()
    private var lastSizedVout: IVLCVout? = null
    private var lastVoutWindowSize = 0L

    /** Queue every native-player operation on one worker. Once release starts, queued playback
     * work is skipped; a callback arriving after shutdown is harmless. */
    private fun enqueueVlcOp(operation: () -> Unit) {
        if (released.get()) return
        try {
            vlcOps.execute {
                if (!released.get()) operation()
            }
        } catch (_: RejectedExecutionException) {
            if (!released.get()) OtvLog.w("vlc operation ignored: worker already shut down")
        }
    }

    private fun commitIfGeneration(generation: Int, block: () -> Unit): Boolean =
        synchronized(generationLock) {
            if (released.get() || generation != mediaGeneration.get()) {
                false
            } else {
                block()
                true
            }
        }

    /**
     * Vendors such as the S621 can leave a TextureView video black on the physical output while
     * screencap still sees composited frames. A SurfaceView gets its own compositor layer, so the
     * picture no longer depends on the Compose window submitting another GPU frame.
     */
    private val videoSurface = object : SurfaceView(appCtx), SurfaceHolder.Callback {
        init {
            // 渲染层不参与焦点（遥控焦点全部留给 Compose 控制层，与 Media3 引擎一致）
            isFocusable = false
            isFocusableInTouchMode = false
            holder.addCallback(this)
            setZOrderMediaOverlay(false)
            setZOrderOnTop(false)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            tryAttachPendingVout()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            activeVout?.let { updateVoutWindowSize(it, width, height) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            activeVout?.let { updateVoutWindowSize(it, w, h) }
        }
    }

    override val renderView: View = videoSurface

    private fun tryAttachPendingVout() {
        if (!surfaceReady) return
        val pending = pendingPlayerForSurface ?: return
        enqueueVlcOp {
            if (!surfaceReady || voutAttached || player !== pending) {
                pendingPlayerForSurface = null
                return@enqueueVlcOp
            }
            val vout = activeVout
            if (vout == null) {
                pendingPlayerForSurface = null
                return@enqueueVlcOp
            }
            try {
                clearVoutWindowSize(vout)
                vout.attachViews()
                voutAttached = true
                pendingPlayerForSurface = null
                updateVoutWindowSize(vout, videoSurface.width, videoSurface.height)
                OtvLog.i("vlc surface attached")
            } catch (failure: Throwable) {
                OtvLog.e("vlc surface attach failed: ${failure.message}")
            }
        }
    }

    /** VLC needs the actual surface dimensions in physical pixels; it does not infer them from
     * the render view's layout params. The initial surface callback and every later relayout both call
     * this, covering attach-before-measure, density changes, and display-size changes. */
    private fun updateVoutWindowSize(vout: IVLCVout, widthPx: Int, heightPx: Int) {
        val size = VlcRenderSize.fromViewSize(widthPx, heightPx) ?: return
        val packed = size.packed
        synchronized(voutWindowSizeLock) {
            if (activeVout !== vout) return
            if (lastSizedVout === vout && lastVoutWindowSize == packed) return
            lastSizedVout = vout
            lastVoutWindowSize = packed
        }
        enqueueVlcOp {
            if (activeVout === vout && voutAttached) {
                runCatching { vout.setWindowSize(size.widthPx, size.heightPx) }
                    .onFailure {
                        synchronized(voutWindowSizeLock) {
                            if (lastSizedVout === vout && lastVoutWindowSize == packed) {
                                lastSizedVout = null
                                lastVoutWindowSize = 0L
                            }
                        }
                        OtvLog.w("vlc setWindowSize failed: ${it.message}")
                    }
            }
        }
    }

    private fun clearVoutWindowSize(vout: IVLCVout? = null) {
        synchronized(voutWindowSizeLock) {
            if (vout == null || lastSizedVout === vout) {
                lastSizedVout = null
                lastVoutWindowSize = 0L
            }
        }
    }

    override fun setLargeBufferPreferred(on: Boolean) {
        if (released.get()) return
        if (largeBuffer == on) return
        largeBuffer = on
        // LibVLC 参数在实例创建时固定：已建实例则标记重建（下次 prepare 生效）
        if (libvlc != null) {
            firstFrameProbeJob?.cancel()
            firstFrameProbeJob = null
            synchronized(generationLock) { mediaGeneration.incrementAndGet() }
            voutEstablished = false
            displayedPicturesKnown = false
            enqueueVlcOp {
                val oldVout = activeVout
                val oldCallback = surfaceCallback
                if (oldVout != null && oldCallback != null) {
                    runCatching { oldVout.removeCallback(oldCallback) }
                }
                activeVout = null
                surfaceCallback = null
                voutAttached = false
                pendingPlayerForSurface = null
                clearVoutWindowSize()
                val oldPlayer = player
                player = null
                playerGeneration = 0
                oldPlayer?.let { p ->
                    runCatching { p.setEventListener(null) }
                    runCatching { p.stop() }
                    runCatching { p.detachViews() }
                    runCatching { p.release() }
                }
                val oldLibVlc = libvlc
                libvlc = null
                runCatching { oldLibVlc?.release() }
            }
        }
    }

    private fun ensureLibVlc(): LibVLC {
        libvlc?.let { return it }
        val options = arrayListOf(
            // 直播链路（经 127.0.0.1:8090 中继）抖动大：加大网络缓存换稳定；
            // v1.17 IPTV 远端源用 6s 大缓存档；
            // v1.22 足球直播兜底档 1500ms→3000ms：上游 CDN 瞬时抖动直接穿透 1.5s 缓存
            "--network-caching=${if (largeBuffer) 6000 else 3000}",
            // 关掉 VLC 的 mediacodec/iomx 硬解尝试：MuMu 等环境下这些 codec 就是故障源
            "--no-mediacodec-dr",
            "--no-omxil",
            "--no-omxil-dr",
            // 音频不做时间拉伸（变速语义对齐 Media3）
            "--no-audio-time-stretch",
        )
        return LibVLC(appCtx, options).also { libvlc = it }
    }

    private fun buildMedia(
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = "",
    ): Media {
        val m = Media(ensureLibVlc(), android.net.Uri.parse(url))
        // 关键：禁用本媒体硬解 → 强制 VLC 自带 avcodec 软解（回退到本引擎的全部意义）
        m.setHWDecoderEnabled(false, false)
        VlcRequestOptionsPolicy.requestOptions(headers, userAgent).forEach { option ->
            runCatching { m.addOption(option) }
        }
        return m
    }

    /** Must run on vlcOps; Media.getStats() and getMedia() are native operations. */
    private fun refreshDisplayedPictures(p: MediaPlayer, generation: Int): Int? {
        val media = runCatching { p.media }.getOrNull() ?: return null
        val latest = try {
            runCatching { media.stats.displayedPictures }
                .getOrNull()
                ?.takeIf { it >= 0 }
        } finally {
            runCatching { media.release() }
        }
        if (latest == null) {
            commitIfGeneration(generation) { displayedPicturesKnown = false }
            return null
        }
        var committed: Int? = null
        commitIfGeneration(generation) {
            displayedPicturesKnown = true
            if (latest > displayedPicturesTotal) displayedPicturesTotal = latest
            committed = displayedPicturesTotal
        }
        return committed
    }

    /** Must run on vlcOps; Media.getStats() and getMedia() are native operations. */
    private fun confirmDisplayedFirstFrame(p: MediaPlayer, generation: Int) {
        if (generation != mediaGeneration.get() || !voutEstablished) return
        val displayedPictures = refreshDisplayedPictures(p, generation) ?: return
        var transitioned = false
        commitIfGeneration(generation) {
            if (_ui.value.firstFrameRendered) return@commitIfGeneration
            if (!VlcFirstFramePolicy.isDisplayed(voutEstablished, displayedPictures)) {
                return@commitIfGeneration
            }
            _ui.value = _ui.value.copy(firstFrameRendered = true)
            transitioned = true
        }
        if (transitioned) {
            OtvLog.i("vlc first frame displayed (displayedPictures=$displayedPictures)")
        }
    }

    override fun decoderStats(): DecoderStats? {
        val rendered = displayedPicturesTotal
        if (!displayedPicturesKnown || rendered <= 0) return null
        return DecoderStats(
            rendered = rendered,
            skipped = -1,
            dropped = -1,
            droppedInput = -1,
            decoderInits = -1,
            consecutiveDropped = -1,
        )
    }

    private fun startFirstFrameProbe() {
        if (released.get() || !voutEstablished || !playRequested || _ui.value.firstFrameRendered) return
        if (firstFrameProbeJob?.isActive == true) return
        val generation = mediaGeneration.get()
        firstFrameProbeJob = scope.launch {
            while (
                isActive && playRequested && !released.get() &&
                generation == mediaGeneration.get() &&
                _ui.value.state != EnginePlayState.ERROR &&
                _ui.value.state != EnginePlayState.ENDED &&
                !_ui.value.firstFrameRendered
            ) {
                enqueueVlcOp {
                    if (generation == mediaGeneration.get() && generation == playerGeneration) {
                        player?.let { confirmDisplayedFirstFrame(it, generation) }
                    }
                }
                delay(100)
            }
        }
    }

    private fun createEventListener(generation: Int) = MediaPlayer.EventListener { ev ->
        when (ev.type) {
            MediaPlayer.Event.Opening -> {
                if (commitIfGeneration(generation) {
                        _ui.value = _ui.value.copy(state = EnginePlayState.BUFFERING, errorMsg = null)
                    }
                ) {
                    OtvLog.i("vlc opening")
                }
            }
            MediaPlayer.Event.Buffering -> {
                val pct = ev.buffering
                commitIfGeneration(generation) {
                    if (pct >= 100f) {
                        // Buffering 100% 只代表可用，出画面以 Playing/首帧为准
                        if (_ui.value.state != EnginePlayState.READY) {
                            _ui.value = _ui.value.copy(state = EnginePlayState.READY)
                        }
                    } else if (_ui.value.state != EnginePlayState.ERROR) {
                        _ui.value = _ui.value.copy(state = EnginePlayState.BUFFERING)
                    }
                }
            }
            MediaPlayer.Event.Playing -> {
                val committed = commitIfGeneration(generation) {
                    _ui.value = _ui.value.copy(state = EnginePlayState.READY, isPlaying = playRequested)
                }
                if (!committed) return@EventListener
                if (!playRequested) {
                    // Opening is asynchronous: a pause issued immediately after play may have
                    // arrived before VLC was ready. Reapply it when opening actually completes.
                    enqueueVlcOp { if (!playRequested) player?.pause() }
                }
                startFirstFrameProbe()
                OtvLog.i("vlc playing（画面输出中）")
            }
            // Vout only proves that the output pipeline exists; wait for a displayed picture below.
            MediaPlayer.Event.Vout -> {
                if (!commitIfGeneration(generation) { voutEstablished = true }) {
                    return@EventListener
                }
                OtvLog.i("vlc vout established（视频输出建立）")
                startFirstFrameProbe()
            }
            MediaPlayer.Event.Paused ->
                commitIfGeneration(generation) {
                    _ui.value = _ui.value.copy(
                        state = VlcPauseStatePolicy.stateAfterPause(_ui.value.state),
                        isPlaying = false,
                    )
                }
            MediaPlayer.Event.EncounteredError -> {
                firstFrameProbeJob?.cancel()
                firstFrameProbeJob = null
                if (commitIfGeneration(generation) {
                        OtvLog.e("vlc error: EncounteredError url=${currentUrl?.take(90)}")
                        _ui.value = _ui.value.copy(
                            state = EnginePlayState.ERROR,
                            isPlaying = false,
                            errorMsg = "VLC_ERROR: 播放失败（流不可用或格式异常）",
                        )
                    }
                ) return@EventListener
            }
            MediaPlayer.Event.EndReached -> {
                firstFrameProbeJob?.cancel()
                firstFrameProbeJob = null
                if (commitIfGeneration(generation) {
                        OtvLog.w("vlc end reached（直播窗口结束/流被掐）")
                        _ui.value = _ui.value.copy(state = EnginePlayState.ENDED, isPlaying = false)
                    }
                ) return@EventListener
            }
        }
    }

    private fun attachVout(p: MediaPlayer) {
        if (released.get()) return
        if (voutAttached) return
        val vout = p.getVLCVout()
        activeVout = vout
        clearVoutWindowSize()
        vout.setVideoView(videoSurface)
        val callback = object : IVLCVout.Callback {
            override fun onSurfacesCreated(vout: IVLCVout) {
                if (released.get()) return
                updateVoutWindowSize(vout, videoSurface.width, videoSurface.height)
            }

            override fun onSurfacesDestroyed(vout: IVLCVout) {
                if (released.get()) return
                // A recreated surface needs its size sent again even when the view kept the same
                // layout dimensions.
                clearVoutWindowSize(vout)
            }
        }
        surfaceCallback = callback
        vout.addCallback(callback)
        if (surfaceReady) {
            try {
                clearVoutWindowSize(vout)
                vout.attachViews()
                voutAttached = true
                updateVoutWindowSize(vout, videoSurface.width, videoSurface.height)
            } catch (failure: Throwable) {
                pendingPlayerForSurface = p
                OtvLog.w("vlc surface not ready yet: ${failure.message}")
            }
        } else {
            pendingPlayerForSurface = p
        }
    }

    private fun startTicker() {
        if (released.get()) return
        ticker?.cancel()
        ticker = scope.launch {
            var beat = 0
            while (isActive && !released.get()) {
                enqueueVlcOp {
                    if (released.get()) return@enqueueVlcOp
                    val p = player ?: return@enqueueVlcOp
                    val generation = playerGeneration
                    if (generation == 0 || generation != mediaGeneration.get()) return@enqueueVlcOp
                    confirmDisplayedFirstFrame(p, generation)
                    val (time, len) = runCatching { p.time to p.length }
                        .getOrElse {
                            OtvLog.w("vlc position read failed: ${it.message}")
                            return@enqueueVlcOp
                        }
                    commitIfGeneration(generation) {
                        _ui.value = _ui.value.copy(
                            positionMs = time.coerceAtLeast(0),
                            durationMs = if (len > 0) len else 0,
                            // VLC 不暴露正向缓冲；直播下恒报健康水位（续签热切换的保护条件）
                            bufferedAheadMs = if (_ui.value.isPlaying) 15_000L else _ui.value.bufferedAheadMs,
                        )
                    }
                    if (++beat % 20 == 0) {
                        val st = _ui.value
                        OtvLog.i("hb vlc ${st.state} playing=${st.isPlaying} pos=${st.positionMs / 1000}s")
                    }
                }
                delay(500)
            }
        }
    }

    override fun prepare(request: PrepareRequest) {
        if (released.get()) return
        val generation = synchronized(generationLock) { mediaGeneration.incrementAndGet() }
        firstFrameProbeJob?.cancel()
        firstFrameProbeJob = null
        voutEstablished = false
        displayedPicturesTotal = 0
        displayedPicturesKnown = false
        playRequested = request.autoplay
        currentUrl = request.url
        currentHeaders = request.headers.toMap()
        currentUserAgent = request.userAgent
        currentMimeType = request.mimeType
        _ui.value = _ui.value.copy(
            state = EnginePlayState.BUFFERING, positionMs = request.startMs, errorMsg = null,
            isPlaying = request.autoplay,
            firstFrameRendered = false,   // 新媒体：首帧信号复位（首帧看门狗判定基准）
        )
        enqueueVlcOp {
            if (generation != mediaGeneration.get()) return@enqueueVlcOp
            try {
                val p = player ?: createPlayer()
                p.setEventListener(null)
                p.stop()
                if (generation != mediaGeneration.get()) return@enqueueVlcOp
                val media = buildMedia(
                    VlcRelaySession.playbackUrl(request.url),
                    headers = request.headers,
                    userAgent = request.userAgent,
                )
                try {
                    // setMedia() retains its own reference; release the caller's initial one.
                    p.media = media
                } finally {
                    media.release()
                }
                playerGeneration = generation
                p.setEventListener(createEventListener(generation))
                attachVout(p)
                if (requestedSpeed != 1f) p.setRate(requestedSpeed)
                // v1.19 断点续播修复：旧版丢弃 request.startMs——Media3 引擎有 seekTo(startMs)
                // 而 VLC 没有，切 VLC 后点播续播/卡顿原位恢复全部从 0 重播。
                // play() 前 setTime 在起播前定位（等效 Media3 的 seekTo(startPos)）
                if (request.startMs > 0) p.time = request.startMs
                if (released.get() || generation != mediaGeneration.get()) return@enqueueVlcOp
                p.play()
                if (!request.autoplay) {
                    // libVLC has no prepare-only call; start the opening pipeline, then pin it to
                    // the user's paused state before any recovery session is considered ready.
                    p.pause()
                    commitIfGeneration(generation) {
                        _ui.value = _ui.value.copy(isPlaying = false)
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                OtvLog.e("vlc prepare failed: ${failure.message}")
                commitIfGeneration(generation) {
                    _ui.value = _ui.value.copy(
                        state = EnginePlayState.ERROR,
                        isPlaying = false,
                        errorMsg = "VLC_ERROR: 播放初始化失败",
                    )
                }
            }
        }
        startTicker()
    }

    private fun createPlayer(): MediaPlayer =
        MediaPlayer(ensureLibVlc()).also { player = it }

    override fun swapSource(
        url: String,
        autoplay: Boolean,
        headers: Map<String, String>,
        userAgent: String,
        mimeType: String,
    ) {
        // VLC 无真正热切换：换源=重开（冻结约 1s，仅直播续签换签名时发生）。
        // autoplay=false 时重开完成后仍保持暂停，避免覆盖用户意图。
        OtvLog.i("vlc 换源（重开, autoplay=$autoplay）: ${url.take(100)}")
        prepare(
            MediaSwapRequestPolicy.request(
                url = url,
                autoplay = autoplay,
                headers = headers,
                userAgent = userAgent,
                mimeType = mimeType,
            ),
        )
    }

    override fun seekToLiveEdge() {
        // 直播卡顿追边：VLC 对 HLS 直播重开即从直播边缘起播
        OtvLog.w("vlc 追直播边缘（重开当前源）")
        currentUrl?.let { url ->
            prepare(
                PrepareRequest(
                    url = url,
                    startMs = 0L,
                    headers = currentHeaders,
                    userAgent = currentUserAgent,
                    mimeType = currentMimeType,
                    autoplay = playRequested,
                ),
            )
        }
    }

    override fun setSoftwareDecoderPreferred(on: Boolean) {
        // 本引擎恒为软解，无操作（接口兼容）
    }

    override fun play() {
        playRequested = true
        val generation = mediaGeneration.get()
        enqueueVlcOp {
            if (generation != mediaGeneration.get()) return@enqueueVlcOp
            val p = player ?: return@enqueueVlcOp
            runCatching { p.play() }
            // A newer pause may already be queued; never mirror stale play intent.
            commitIfGeneration(generation) {
                _ui.value = _ui.value.copy(isPlaying = playRequested)
            }
        }
        startFirstFrameProbe()
    }

    override fun pause() {
        playRequested = false
        val generation = mediaGeneration.get()
        enqueueVlcOp {
            if (generation != mediaGeneration.get()) return@enqueueVlcOp
            val p = player ?: return@enqueueVlcOp
            runCatching { p.pause() }
            commitIfGeneration(generation) {
                _ui.value = _ui.value.copy(isPlaying = false)
            }
        }
    }

    override fun seekTo(ms: Long) {
        val generation = mediaGeneration.get()
        enqueueVlcOp {
            if (generation != mediaGeneration.get()) return@enqueueVlcOp
            runCatching { player?.setTime(ms.coerceAtLeast(0)) }
        }
    }

    override fun setSpeed(v: Float) {
        if (released.get()) return
        requestedSpeed = v
        val generation = mediaGeneration.get()
        if (player == null) {
            _ui.value = _ui.value.copy(speed = v)
            return
        }
        enqueueVlcOp {
            if (generation != mediaGeneration.get()) return@enqueueVlcOp
            runCatching { player?.setRate(v) }
            commitIfGeneration(generation) {
                _ui.value = _ui.value.copy(speed = v)
            }
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(generationLock) { mediaGeneration.incrementAndGet() }
        playerGeneration = 0
        currentUrl = null
        currentHeaders = emptyMap()
        currentUserAgent = ""
        currentMimeType = ""
        firstFrameProbeJob?.cancel()
        firstFrameProbeJob = null
        voutEstablished = false
        displayedPicturesTotal = 0
        displayedPicturesKnown = false
        ticker?.cancel()
        ticker = null
        scope.cancel()
        try {
            // This is the final task in the serial queue. It runs after any JNI operation already
            // in progress, then closes the per-engine worker so rebuilds do not leak a thread.
            vlcOps.execute {
                try {
                    val oldVout = activeVout
                    val oldCallback = surfaceCallback
                    if (oldVout != null && oldCallback != null) {
                        runCatching { oldVout.removeCallback(oldCallback) }
                    }
                    surfaceCallback = null
                    activeVout = null
                    voutAttached = false
                    pendingPlayerForSurface = null
                    clearVoutWindowSize()

                    val oldPlayer = player
                    player = null
                    playerGeneration = 0
                    oldPlayer?.let { p ->
                        runCatching { p.setEventListener(null) }
                        runCatching { p.stop() }
                        runCatching { p.detachViews() }
                        runCatching { p.release() }
                    }
                    val oldLibVlc = libvlc
                    libvlc = null
                    runCatching { oldLibVlc?.release() }
                } finally {
                    vlcOps.shutdown()
                }
            }
        } catch (_: RejectedExecutionException) {
            // The release task can only be rejected if the executor was already shut down.
            vlcOps.shutdown()
        }
    }
}
