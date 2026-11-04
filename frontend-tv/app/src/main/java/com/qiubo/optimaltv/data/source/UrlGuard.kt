package com.qiubo.optimaltv.data.source

import java.net.InetAddress
import java.net.URI

/**
 * SSRF 校验器（v1.13 电视直播 2026-09-01）：
 *
 * 语义对齐内置后端 proxy.py 的 _blocked_internal_url / _assert_public_http_url（v1.11 SSRF
 * 加固），首次移植到 Kotlin 客户端侧——电视页拉取 m3u 列表与 IPTV 频道起播前都必须过检。
 *
 * 两层防线：
 * 1. [isBlocked]（字符串级，同步免网络）：仅 http/https；拒绝 localhost、::1、IPv4-mapped
 *    IPv6、纯数字简写（0/127）、字面 IPv4 落 127/8、0/8、10/8、172.16/12、192.168/16、
 *    169.254/16（环回/私网/链路本地/保留段）。覆盖字面 IP 直写与等价回环绕过。
 * 2. [assertPublicResolved]（解析级，DNS 查询）：域名解析后全部 A/AAAA 记录均落私网
 *    即拒绝——覆盖「公网域名被 DNS 指向内网」的投放（对齐 proxy.py 二次校验；
 *    TOCTOU/CDN 混合解析按宽松处理：任一公网记录即放行，与后端一致）。
 */
object UrlGuard {

    /** 校验结果：blocked=true 时 reason 给出拦截原因（日志取证用） */
    data class Verdict(val blocked: Boolean, val reason: String? = null) {
        companion object {
            internal fun block(reason: String) = Verdict(true, reason)
            internal val PASS = Verdict(false, null)
        }
    }

    /** 字符串级校验（无网络开销）：电视页所有出站 URL 的第一道闸 */
    fun check(url: String): Verdict {
        val pr = runCatching { URI(url) }.getOrNull()
            ?: return Verdict.block("malformed url")
        if (pr.scheme != "http" && pr.scheme != "https") {
            return Verdict.block("scheme ${pr.scheme} not allowed")
        }
        var h = (pr.host ?: "").trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return Verdict.block("empty host")
        if (h == "localhost") return Verdict.block("localhost")

        // IPv6 规范化：URI.getHost 已去 []，这里再剥 %scope；::1 等价形式展开判定
        if (h.contains(':')) {
            if ("%" in h) h = h.substringBefore('%')
            if (isLoopbackIpv6(h)) return Verdict.block("ipv6 loopback $h")
            // IPv4-mapped IPv6（::ffff:a.b.c.d / ::ffff:hex:hex）→ 取内嵌 IPv4 再判
            val mapped = ipv4Mapped(h)
            if (mapped != null) {
                if (mapped.isEmpty()) return Verdict.block("undecodable ipv4-mapped $h")
                h = mapped
            } else if (h.contains(':')) {
                // 非 mapped 的普通 IPv6 公网地址：不做段判定（本仓库源均为 IPv4/域名）
                return Verdict.PASS
            }
        }

        // 纯数字简写：0 / 127 等单段 → 等价 0.0.0.0 / 127.0.0.0 网段，一律拦截
        if (h.isNotEmpty() && h.all { it.isDigit() }) return Verdict.block("numeric shorthand $h")

        if ("." in h) {
            val parts = h.split(".")
            val allNumeric = parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
            // P2 修复（2026-09-04）：两/三段数字简写（127.1 → 127.0.0.1、10.1 → 10.0.0.1）
            // 旧版只拦「恰好 4 段」，Java InetAddress 会把简写展开成环回/私网——
            // 被投毒的 m3u 线路可借此绕过两层防线直连 127.0.0.1:8090 本地服务
            if (allNumeric && parts.size != 4) return Verdict.block("numeric shorthand $h")
            if (parts.size == 4 && allNumeric && parts.all { it.toInt() in 0..255 }) {
                val a = parts[0].toInt(); val b = parts[1].toInt()
                val blockedSeg = when {
                    a == 127 || a == 0 -> "loopback/reserved $a/8"
                    a == 10 -> "private 10/8"
                    a == 172 && b in 16..31 -> "private 172.16/12"
                    a == 192 && b == 168 -> "private 192.168/16"
                    a == 169 && b == 254 -> "link-local 169.254/16"
                    else -> null
                }
                if (blockedSeg != null) return Verdict.block(blockedSeg)
            }
            // 合法域名（含非四段形态）放行，交解析级校验
        }
        return Verdict.PASS
    }

    fun isBlocked(url: String): Boolean = check(url).blocked

    /**
     * 解析级校验（会做 DNS 查询，仅可在 IO 线程调用）：
     * 先过字符串级，再对域名解析全部 IP——均为环回/私网/链路本地/保留地址则拒绝。
     * 解析失败（NXDOMAIN 等）同样拒绝（起播必然失败，且避免被用作探测信道）。
     */
    fun assertPublicResolved(url: String): Verdict {
        val base = check(url)
        if (base.blocked) return base
        val host = runCatching { URI(url).host ?: "" }.getOrDefault("")
        // 字面 IP 已过字符串级检（四段全数字；简写形态已被 check() 拦截）
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' }) return Verdict.PASS
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: return Verdict.block("dns resolve failed: $host")
        val allBad = addrs.all { isBadIp(it.address) }
        return if (allBad) Verdict.block("resolved to internal: ${addrs.joinToString { it.hostAddress }}")
        else Verdict.PASS
    }

    /** IP 字节组的私网/环回/保留段判定（InetAddress.address 为网络序 4/16 字节） */
    private fun isBadIp(b: ByteArray): Boolean {
        val v4 = if (b.size == 4) b else {
            // ::ffff:a.b.c.d 映射形式（16 字节前 10 个 0 + 2 个 ff）
            if (b.size == 16 && b.take(10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) {
                b.copyOfRange(12, 16)
            } else return false // 普通 IPv6 公网：放行（与字符串级策略一致）
        }
        val a = v4[0].toInt() and 0xff; val b2 = v4[1].toInt() and 0xff
        return when {
            a == 127 || a == 0 || a == 10 -> true
            a == 172 && b2 in 16..31 -> true
            a == 192 && b2 == 168 -> true
            a == 169 && b2 == 254 -> true
            else -> false
        }
    }

    /** ::1 及其等价展开形式 */
    private fun isLoopbackIpv6(h: String): Boolean {
        val t = h.lowercase()
        if (t == "::1" || t == "0:0:0:0:0:0:0:1") return true
        // "::" 纯未指定地址也属保留
        if (t == "::" || t == "0:0:0:0:0:0:0:0") return true
        return false
    }

    /** IPv4-mapped IPv6 → 内嵌 IPv4 点分形式；非 mapped 返回 null；无法解码返回 ""（保守拦截） */
    private fun ipv4Mapped(h: String): String? {
        val t = h.lowercase()
        val m = Regex("^(?:::)?(?:0*:)*0*ffff:([0-9a-f.:]+)$").find(t) ?: return null
        val inner = m.groupValues[1]
        if ("." in inner) return inner
        val hx = inner.split(":")
        return try {
            when (hx.size) {
                2 -> {
                    val a = hx[0].toInt(16); val b = hx[1].toInt(16)
                    "${(a shr 8) and 255}.${a and 255}.${(b shr 8) and 255}.${b and 255}"
                }
                4 -> hx.joinToString(".") { x -> x.toInt(16).toString() }
                else -> "" // 真实 CDN 不会用 mapped 地址出流：保守拦截
            }
        } catch (e: NumberFormatException) {
            ""
        }
    }
}
