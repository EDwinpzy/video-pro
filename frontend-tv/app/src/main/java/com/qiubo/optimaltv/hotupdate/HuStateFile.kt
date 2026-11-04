package com.qiubo.optimaltv.hotupdate

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v1.20（2026-09-03）：后端热更状态文件通道（filesDir/hu_state.json）。
 *
 * 内嵌后端拆到 :backend 进程后，EmbeddedBackend 的启动回调（applied/assets/failed/
 * probeFails）发生在后端进程 —— androidx DataStore 非多进程安全，不能两边写同一
 * hotupdateStore。改为：:backend 只写本文件（原子 tmp+rename），主进程在
 * HotUpdateManager.init / 后端重建完成时读取，镜像进 DataStore 并上报云端。
 *
 * 字段：active=已生效热更版本（0=assets 原生）、poison=拉黑版本（坏包隔离）、
 * detail=失败原因、probeFails=健康探测连续失败计数（后端自读自写，两击隔离）。
 */
object HuStateFile {

    private fun file(ctx: Context): File = File(ctx.filesDir, "hu_state.json")

    fun read(ctx: Context): JSONObject? = runCatching {
        val f = file(ctx)
        if (!f.isFile) null else JSONObject(f.readText())
    }.getOrNull()

    private fun write(ctx: Context, jo: JSONObject) {
        runCatching {
            val f = file(ctx)
            val tmp = File(f.parentFile, "hu_state.json.tmp")
            tmp.writeText(jo.toString())
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        }
    }

    /** 后端以热更包 v=code 成功启动（每后端进程启动时都会写） */
    fun writeActive(ctx: Context, code: Int) {
        write(ctx, read(ctx)?.put("active", code)?.put("probeFails", 0)
            ?: JSONObject().put("active", code).put("probeFails", 0))
    }

    /** 后端回退 assets 原生（无热更） */
    fun writeAssets(ctx: Context) {
        write(ctx, read(ctx)?.put("active", 0) ?: JSONObject().put("active", 0))
    }

    /** 热更包启动失败 → 拉黑该版本（防强制循环砖机） */
    fun writeFailed(ctx: Context, code: Int, detail: String) {
        write(ctx, (read(ctx) ?: JSONObject())
            .put("active", 0).put("poison", code).put("detail", detail.take(120)))
    }

    /** 健康探测失败计数 +1，返回新值（后端进程同步调用，非主线程） */
    fun bumpProbeFails(ctx: Context): Int {
        val jo = read(ctx) ?: JSONObject()
        val n = jo.optInt("probeFails", 0) + 1
        write(ctx, jo.put("probeFails", n))
        return n
    }
}
