package com.qiubo.optimaltv

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.qiubo.optimaltv.hotupdate.HotUpdateManager
import kotlinx.coroutines.launch

/**
 * v1.20（2026-09-03）：内嵌 python 后端宿主进程（android:process=":backend"）。
 *
 * 为什么拆进程：Chaquopy 的 Python VM 无法在进程内重启，热更新后端生效必须换进程；
 * 而杀整个 app 再自动拉起在 Android 10+ 没有可靠通道（startActivity 同包复用旧进程、
 * AlarmManager PendingIntent 被 BAL 拦截，v1.20 两版方案 MuMu 实测均死）。
 * Service 的启停不受后台 Activity 启动限制 —— 热更新后 kill 本进程再 startService，
 * 系统重建 :backend 进程、python 以新后端目录重启，app 主进程全程在线。
 *
 * 本类自身几乎无逻辑：进程创建时 App.onCreate（按进程名分叉）已起 python；
 * 这里只提供「让系统拉起 :backend 进程」的组件与 ACTION_REBOOT 自毁入口。
 * START_NOT_STICKY：普通后台不主动销毁；用户明确退出后不允许系统自动拉起后端。
 */
class BackendService : Service() {

    override fun onCreate() {
        super.onCreate()
        // App.onCreate 已按进程名启动 python；无额外工作
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REBOOT) {
            OtvLog.i("backend: 收到重启指令，kill :backend 进程（热更新生效）")
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHUTDOWN) {
            OtvLog.i("backend: 收到明确退出指令")
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REBOOT = "com.qiubo.optimaltv.backend.REBOOT"
        const val ACTION_SHUTDOWN = "com.qiubo.optimaltv.backend.SHUTDOWN"

        /** P2 修复（2026-09-04）：后台期间 reboot 指令被后台 Activity 启动限制拦下时置位，
         *  回前台 flushPendingReboot 补发——旧版静默吞掉，更新已下载但 :backend 不重启，
         *  UI 却报「已生效」，实际要等下次冷启动 */
        @Volatile private var pendingReboot = false

        /** 主进程调：拉起（或重建）:backend 进程。前台调用，startService 合法 */
        fun ensure(ctx: android.content.Context) {
            runCatching {
                ctx.startService(Intent(ctx, BackendService::class.java))
            }.onFailure { OtvLog.w("backend: startService 失败: ${it.message}") }
        }

        /** 热更新生效：杀 :backend → 稍候重建（新进程读新后端目录） */
        fun reboot(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
            val delivered = runCatching {
                ctx.startService(
                    Intent(ctx, BackendService::class.java).setAction(ACTION_REBOOT))
            }.onFailure { OtvLog.w("backend: reboot 指令下发失败: ${it.message}") }.isSuccess
            if (!delivered) {
                pendingReboot = true
                OtvLog.w("backend: 应用在后台无法下发重启指令，回前台后自动补发")
                return   // 不假报「已生效」
            }
            pendingReboot = false
            scope.launch {
                kotlinx.coroutines.delay(1200)
                ensure(ctx)
                OtvLog.i("backend: :backend 进程已重建，新后端生效")
                HotUpdateManager.onBackendRebooted()
            }
        }

        /** 主进程回前台时调（HotUpdateManager.onForegroundChanged）：补发搁置的 :backend 重启 */
        fun flushPendingReboot(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
            if (!pendingReboot) return
            OtvLog.i("backend: 回前台，补发热更重启指令")
            reboot(ctx, scope)
        }
    }
}
