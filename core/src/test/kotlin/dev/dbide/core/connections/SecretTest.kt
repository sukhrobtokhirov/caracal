package dev.dbide.core.connections

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SecretTest {
    @Test
    fun `does not print its own contents`() {
        assertFalse(Secret("hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `clearing wipes the characters behind it`() {
        val secret = Secret("hunter2")

        secret.clear()

        assertFalse(secret.expose().contains("hunter2"))
        assertTrue(secret.expose().isBlank())
    }

    @Test
    fun `an empty secret knows it is empty`() {
        assertTrue(Secret("").isEmpty())
        assertTrue(Secret.EMPTY.isEmpty())
        assertFalse(Secret("x").isEmpty())
    }

    @Test
    fun `encodes and decodes without an intermediate string`() {
        val bytes = Secret("sürprïse-密码-🔐").toBytes()

        assertEquals("sürprïse-密码-🔐", secretOfBytes(bytes).expose())
    }
}
