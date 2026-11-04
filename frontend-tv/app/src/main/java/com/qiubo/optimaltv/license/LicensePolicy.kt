package com.qiubo.optimaltv.license

import java.security.MessageDigest

/** 无 Android 依赖的授权规则，供 TV/手机端共用并做 JVM 边界测试。 */
object LicensePolicy {
    const val OFFLINE_GRACE_MS = 24L * 60L * 60L * 1_000L

    /** 同一安装身份稳定、不同包名隔离；服务端只接收完整 SHA-256，不接收原始设备信息。 */
    fun deviceId(androidId: String, packageName: String, model: String): String {
        val seed = "$androidId|$packageName|$model"
        return MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun withinOfflineGrace(lastVerifySuccessAt: Long, now: Long): Boolean =
        lastVerifySuccessAt > 0L && now >= lastVerifySuccessAt &&
            now - lastVerifySuccessAt < OFFLINE_GRACE_MS

    fun accessAllowed(expireAt: Long?, lastVerifySuccessAt: Long, now: Long): Boolean =
        withinOfflineGrace(lastVerifySuccessAt, now) && (expireAt == null || now < expireAt)
}
