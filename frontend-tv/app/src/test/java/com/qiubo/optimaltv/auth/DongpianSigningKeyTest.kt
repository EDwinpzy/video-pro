package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DongpianSigningKeyTest {
    private val signingKey = (0 until 64).joinToString("") { (it % 16).toString(16) }

    @Test
    fun signingKeyDoesNotDependOnTheMinifiedVariableName() {
        val runtime =
            """const Bo="$signingKey".trim()??"";const Ea="x-ai-movie-signature";"""

        assertEquals(signingKey, DongpianAuthClient.extractSigningKey(runtime))
    }

    @Test
    fun legacyVariableNameStillWorks() {
        val runtime = """const Jo = "$signingKey";"""

        assertEquals(signingKey, DongpianAuthClient.extractSigningKey(runtime))
    }

    @Test
    fun keyNearestTheSignatureHeaderWins() {
        val decoy = (0 until 64).joinToString("") { (15 - it % 16).toString(16) }
        val runtime =
            """const x="$decoy";const y="$signingKey";const Ea="x-ai-movie-signature";"""

        assertEquals(signingKey, DongpianAuthClient.extractSigningKey(runtime))
    }

    @Test
    fun nonSigningHashesAreIgnored() {
        val runtime = """const asset="0123456789abcdef";const Ea="x-ai-movie-signature";"""

        assertNull(DongpianAuthClient.extractSigningKey(runtime))
    }
}
