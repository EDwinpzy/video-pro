package app.jellystack.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VendorIatTranscriptTest {
    @Test
    fun appendsInSequenceAndKeepsFinalText() {
        val transcript = VendorIatTranscript()

        assertEquals("今天", transcript.accept(packet("sn" to 0, "w" to "今天"))?.text)
        assertEquals("今天天气怎么样", transcript.accept(packet("sn" to 1, "w" to "天气怎么样"))?.text)
        val final = transcript.accept("""{"text":{"sn":2,"ls":true}}""")

        assertTrue(final?.isFinal == true)
        assertEquals("今天天气怎么样", final?.text)
    }

    @Test
    fun sortsOutOfOrderSegments() {
        val transcript = VendorIatTranscript()

        transcript.accept(packet("sn" to 1, "w" to "B"))

        assertEquals("AB", transcript.accept(packet("sn" to 0, "w" to "A"))?.text)
    }

    @Test
    fun replacesAnEarlierRange() {
        val transcript = VendorIatTranscript()
        transcript.accept(packet("sn" to 0, "w" to "今天天汽"))

        val result =
            transcript.accept(
                """{"text":{"sn":1,"pgs":"rpl","rg":[0,0],"ws":[{"cw":[{"w":"今天天气"}]}]}}""",
            )

        assertFalse(result?.isFinal == true)
        assertEquals("今天天气", result?.text)
    }

    @Test
    fun ignoresMalformedPackets() {
        val transcript = VendorIatTranscript()

        assertNull(transcript.accept("not-json"))
        assertNull(transcript.accept("""{"text":{"sn":0}}"""))
    }

    private fun packet(vararg values: Pair<String, Any>): String {
        val sequence = values.first { it.first == "sn" }.second
        val word = values.first { it.first == "w" }.second
        return """{"text":{"sn":$sequence,"ws":[{"cw":[{"w":"$word"}]}]}}"""
    }
}
