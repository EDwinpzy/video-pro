package com.qiubo.optimaltv.auth

import com.qiubo.optimaltv.BuildConfig
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The public host is fronted by a rotating CDN. Some router DNS servers briefly
 * return a node that is unreachable from the TV network, while domestic DoH
 * consistently returns a reachable node. Resolve through DoH first, retain the
 * system answer as a fallback, and never cache a failed lookup.
 */
internal object DongpianDns : Dns {
    private const val AUTH_HOST = "ai.dongpian20.com"
    private const val DOH_URL =
        "https://223.5.5.5/resolve?name=$AUTH_HOST&type=A"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val dohClient =
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var cached: CachedAddresses? = null

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(AUTH_HOST, ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cached?.takeIf { now < it.expiresAt }?.let { return it.addresses }

        val systemAddresses =
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val dohAddresses = resolveThroughDoh()
        val merged =
            (dohAddresses + systemAddresses)
                .distinctBy { it.hostAddress }
        if (merged.isEmpty()) throw UnknownHostException(hostname)

        cached =
            CachedAddresses(
                addresses = merged,
                expiresAt = now + CACHE_TTL_MS,
            )
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "DongpianAuthDns",
                "resolved source=${if (dohAddresses.isEmpty()) "system" else "doh"} " +
                    "addresses=${merged.size}",
            )
        }
        return merged
    }

    private fun resolveThroughDoh(): List<InetAddress> {
        val request =
            Request.Builder()
                .url(DOH_URL)
                .header("Accept", "application/dns-json")
                .get()
                .build()
        return try {
            dohClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val addresses =
                    if (response.isSuccessful) {
                        parseDohIpv4(AUTH_HOST, body)
                    } else {
                        emptyList()
                    }
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "DongpianAuthDns",
                        "doh status=${response.code} bytes=${body.length} " +
                            "addresses=${addresses.size}",
                    )
                }
                addresses.map { InetAddress.getByName(it) }
            }
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w(
                    "DongpianAuthDns",
                    "doh failure=${failure.javaClass.simpleName}",
                )
            }
            emptyList()
        }
    }

    private data class CachedAddresses(
        val addresses: List<InetAddress>,
        val expiresAt: Long,
    )
}

internal fun parseDohIpv4(
    hostname: String,
    body: String,
): List<String> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    if (root.optInt("Status", -1) != 0) return emptyList()
    val answers = root.optJSONArray("Answer") ?: return emptyList()
    val names = mutableSetOf(hostname.lowercase())
    repeat(8) {
        val before = names.size
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            if (answer.optInt("type") != 5) continue
            val name = answer.optString("name").removeSuffix(".").lowercase()
            if (name !in names) continue
            val target = answer.optString("data").removeSuffix(".").lowercase()
            if (target.isNotBlank()) names += target
        }
        if (names.size == before) return@repeat
    }
    val ipv4Pattern = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    return buildList {
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            if (answer.optInt("type") != 1) continue
            val name = answer.optString("name").removeSuffix(".").lowercase()
            if (name !in names) continue
            val address = answer.optString("data").trim()
            if (!ipv4Pattern.matches(address)) continue
            val octets = address.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) continue
            add(address)
        }
    }.distinct()
}
