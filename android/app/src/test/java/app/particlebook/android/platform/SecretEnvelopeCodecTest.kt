package app.particlebook.android.platform

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretEnvelopeCodecTest {

    private val iv = ByteArray(SecretEnvelopeCodec.IV_BYTES) { (it + 1).toByte() }
    private val ciphertext = ByteArray(SecretEnvelopeCodec.TAG_BYTES) { (it + 17).toByte() }

    @Test
    fun envelopeRoundTripsIvAndCiphertext() {
        val encoded = SecretEnvelopeCodec.encode(iv, ciphertext)
        assertTrue(encoded.startsWith("1|"))
        assertEquals(3, encoded.split('|').size)
        val envelope = SecretEnvelopeCodec.decode(encoded)
        assertEquals(1, envelope.version)
        assertArrayEquals(iv, envelope.iv)
        assertArrayEquals(ciphertext, envelope.ciphertext)
    }

    @Test
    fun envelopeNeverContainsPlaintextOrUrlUnsafeSeparator() {
        val encoded = SecretEnvelopeCodec.encode(iv, ciphertext)
        assertFalse(encoded.contains("secret-value"))
        assertFalse(encoded.substringAfter("|").substringBefore("|").contains("|"))
        assertTrue(encoded.substringAfterLast("|").isNotEmpty())
    }

    @Test
    fun base64WithPlusAndSlashStillUsesThreeParts() {
        val bytes = ByteArray(SecretEnvelopeCodec.TAG_BYTES) { 0xFF.toByte() }
        val encoded = SecretEnvelopeCodec.encode(iv, bytes)
        assertEquals(3, encoded.split('|').size)
        assertTrue(encoded.contains("|"))
        assertArrayEquals(bytes, SecretEnvelopeCodec.decode(encoded).ciphertext)
    }

    @Test
    fun encodeRejectsWrongIvLength() {
        val error = assertThrows(SecretStoreException::class.java) {
            SecretEnvelopeCodec.encode(ByteArray(11), ciphertext)
        }
        assertEquals(SecretStoreError.CIPHERTEXT_CORRUPT, error.error)
    }

    @Test
    fun encodeRejectsMissingAuthenticationTag() {
        val error = assertThrows(SecretStoreException::class.java) {
            SecretEnvelopeCodec.encode(iv, ByteArray(15))
        }
        assertEquals(SecretStoreError.CIPHERTEXT_CORRUPT, error.error)
    }

    @Test
    fun decodeRejectsMalformedEnvelopes() {
        val validIv = Base64.getEncoder().encodeToString(iv)
        val validCiphertext = Base64.getEncoder().encodeToString(ciphertext)
        val malformed = listOf(
            "",
            "   ",
            "1|" + validIv,
            "1|" + validIv + "|" + validCiphertext + "|extra",
            "2|" + validIv + "|" + validCiphertext,
            "1|not-base64!|" + validCiphertext,
            "1|" + validIv + "|not-base64!",
            "1|" + Base64.getEncoder().encodeToString(ByteArray(11)) + "|" + validCiphertext,
            "1|" + validIv + "|" + Base64.getEncoder().encodeToString(ByteArray(15)),
        )
        malformed.forEach { encoded ->
            val error = assertThrows(encoded, SecretStoreException::class.java) {
                SecretEnvelopeCodec.decode(encoded)
            }
            assertEquals(encoded, SecretStoreError.CIPHERTEXT_CORRUPT, error.error)
        }
    }
}
