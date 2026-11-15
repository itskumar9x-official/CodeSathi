package com.inyogeshwar.codesathi.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for exported secrets (Phase 7, Step B3 —
 * ported from AgentMT's `BackupCrypto.kt`, the user's own code, with
 * AgentMT's exact parameters and a CodeSathi magic string).
 *
 * Format: `MAGIC` ("CSS1", CodeSathi Secrets 1) + 16-byte salt +
 * 12-byte IV + AES-256-GCM ciphertext, with the magic as additional
 * authenticated data. Key: PBKDF2-HMAC-SHA256, 210,000 iterations,
 * 256-bit key. A wrong passphrase (or a changed file) fails the GCM
 * tag check and throws [WrongPassphrase] — never a half-decrypted
 * secret.
 *
 * The passphrase is NEVER stored anywhere: the user types it at
 * export and again at import, and it lives only in memory for that
 * one operation.
 */
object BackupCrypto {
    private val MAGIC = "CSS1".toByteArray()
    private const val ITER = 210_000

    private fun key(pass: CharArray, salt: ByteArray) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pass, salt, ITER, 256)).encoded,
            "AES"
        )

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        }
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** Throws [WrongPassphrase] when the passphrase is wrong or the data was changed. */
    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        if (blob.size < MAGIC.size + 28 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a CodeSathi secrets file")
        }
        var o = MAGIC.size
        val salt = blob.copyOfRange(o, o + 16); o += 16
        val iv = blob.copyOfRange(o, o + 12); o += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        }
        c.updateAAD(MAGIC)
        return try {
            c.doFinal(blob, o, blob.size - o)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    class WrongPassphrase : Exception("Wrong passphrase")
}
