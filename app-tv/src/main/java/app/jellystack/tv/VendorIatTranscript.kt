package app.jellystack.tv

import java.util.TreeMap
import org.json.JSONObject

/** Collects iFlytek AIUI IAT packets, including WPGS replacement ranges. */
internal class VendorIatTranscript {
    data class Result(
        val text: String,
        val isFinal: Boolean,
    )

    private val segments = TreeMap<Int, String>()

    fun accept(json: String): Result? =
        runCatching {
            val text = JSONObject(json).optJSONObject("text") ?: return null
            val finished = text.optBoolean("ls", false)
            val words = text.optJSONArray("ws")
            if (finished && (words == null || words.length() == 0)) {
                return Result(segments.values.joinToString(""), true)
            }
            if (words == null) return null
            val sequence = (text.opt("sn") as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
            val packet =
                buildString {
                    for (wordIndex in 0 until words.length()) {
                        val candidates = words.getJSONObject(wordIndex).getJSONArray("cw")
                        val word = candidates.getJSONObject(0).opt("w") as? String ?: return null
                        append(word)
                    }
                }
            val replacement =
                when (text.optString("pgs", "")) {
                    "", "apd" -> null
                    "rpl" -> {
                        val range = text.optJSONArray("rg") ?: return null
                        if (range.length() != 2) return null
                        val first = (range.opt(0) as? Number)?.toInt() ?: return null
                        val last = (range.opt(1) as? Number)?.toInt() ?: return null
                        if (first < 0 || last < first) return null
                        first..last
                    }
                    else -> return null
                }
            replacement?.let { range -> segments.keys.removeAll { it in range } }
            if (packet.isNotEmpty() || !finished) segments[sequence] = packet
            Result(segments.values.joinToString(""), finished)
        }.getOrNull()

    fun clear() {
        segments.clear()
    }
}
