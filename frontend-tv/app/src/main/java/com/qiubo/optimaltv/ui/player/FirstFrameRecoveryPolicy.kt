package com.qiubo.optimaltv.ui.player

/**
 * 点播首帧等待策略。
 *
 * 等待和引擎恢复始终保留用户选择的线路。Media3 最多在同一线路上回退到 VLC；
 * 引擎回退后仍无画面时只 fresh 重试同一线路，不自动跳往其它片源。
 */
object FirstFrameRecoveryPolicy {

    const val NORMAL_MS = 3_000L
    const val PREP_BACKUP_MS = 5_000L
    const val SWITCH_MS = 7_000L
    /** After an engine fallback still produces no frame, retry the selected source afresh. */
    const val RETRY_MS = 15_000L
    /** A healthy but slow prepare may legitimately take several seconds; retry only after this. */
    const val PREPARE_RETRY_MS = 20_000L
    /** 首帧等待的处置档位。 */
    enum class Action {
        /** 继续等，不做任何切换。 */
        WAIT,

        /** 进入慢速等待阶段，不打断当前播放。 */
        PREP_BACKUP,

        /** 只换播放引擎，保留当前线路。 */
        ENGINE_FALLBACK,

        /** 引擎已回退仍无帧：保留当前线路重新解析并起播。 */
        RETRY_SOURCE,

        /** 已经出过帧，后续阶段不再需要本阶梯。 */
        DONE,
    }

    /**
     * 按「已等待毫秒数」决定动作。
     *
     * @param elapsedMs 从 prepare 到现在的毫秒数。
     * @param hasBackup 是否存在其它线路；只影响是否记录慢速等待阶段，不自动切换线路。
     */
    fun action(
        elapsedMs: Long,
        hasBackup: Boolean = true,
        onMedia3: Boolean = true,
        vlcAlreadyTried: Boolean = false,
    ): Action = when {
        elapsedMs < 0L -> Action.WAIT
        elapsedMs < NORMAL_MS -> Action.WAIT
        elapsedMs < PREP_BACKUP_MS -> if (hasBackup) Action.PREP_BACKUP else Action.WAIT
        elapsedMs < SWITCH_MS -> Action.PREP_BACKUP
        onMedia3 && !vlcAlreadyTried -> Action.ENGINE_FALLBACK
        elapsedMs < RETRY_MS -> Action.WAIT
        else -> Action.RETRY_SOURCE
    }

    /** While the engine has never reached READY, allow a slow prepare before retrying it. */
    fun beforeReadyAction(elapsedMs: Long): Action =
        if (elapsedMs >= PREPARE_RETRY_MS) Action.RETRY_SOURCE else Action.WAIT

    /** VOD replays also stay on the selected source and use the same no-frame retry policy. */
    fun footballAction(
        elapsedMs: Long,
        hasBackup: Boolean,
        onMedia3: Boolean,
        vlcAlreadyTried: Boolean,
    ): Action = action(elapsedMs, hasBackup, onMedia3, vlcAlreadyTried)

    /**
     * 快速起播判定：若已有可用线路引用（resolver preferred / last_good / 已解析首选），
     * 立即 prepare，不等全部线路到齐。
     *
     * 这是「点播放秒进」的关键——旧版要等所有线路 match 完才允许 prepare；
     * 现在只要手里有一条能用的线路就先播，剩余线路在后台补齐供用户换线。
     */
    fun shouldStartImmediately(hasPreferredOrLastGood: Boolean, linesReady: Int): Boolean =
        hasPreferredOrLastGood || linesReady > 0

}
