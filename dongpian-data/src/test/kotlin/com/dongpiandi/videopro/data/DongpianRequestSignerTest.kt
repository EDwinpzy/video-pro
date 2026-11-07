package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.transport.DongpianRequestSigner
import kotlin.test.Test
import kotlin.test.assertEquals

class DongpianRequestSignerTest {
    @Test
    fun matchesTheFixedHmacContractIncludingQueryString() {
        val signature =
            DongpianRequestSigner.sign(
                key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                method = "GET",
                pathAndQuery = "/v1/browse/catalog?q=%E6%98%9F%E9%99%85&page=1&limit=20",
                timestamp = "1700000000000",
                nonce = "00112233445566778899aabbccddeeff",
            )

        assertEquals(
            "6735f49801d3742dc6e9df689ce88bcb87baf277bd36eb0eeeced19ba37981ea",
            signature,
        )
    }

    @Test
    fun accountScopesAreStableHashes() {
        assertEquals(
            "fcdec6df4d44dbc637c7c5b58efface52a7f8a88535423430255be0bb89bedd8",
            DongpianRequestSigner.sha256Hex("user-123"),
        )
    }
}
