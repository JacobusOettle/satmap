package com.jacobusoettle.satmap.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Offline map tiles are stored encrypted (`MBTiles.put` seals them, `MBTiles.tile` opens them), so the files can't
 *  simply be copied off the phone and their imagery shown in QGIS or another app.
 *
 *  A sealed tile is "SMT" and format version 1 (bytes 53 4D 54 01), a 12-byte random nonce, the AES-256-GCM
 *  ciphertext and its 16-byte tag. The associated data is the UTF-8 text "satmap/{z}/{x}/{y}" with the tile's XYZ
 *  coordinates (`TileID`'s, not the MBTiles TMS row), so a tile can't be moved to another place. No image format
 *  starts with those four bytes (JPEG FF D8, PNG 89 50, WebP RIFF, GIF GIF8), so a reader tells sealed tiles from
 *  plain ones (stored before tiles were encrypted) by them. The iPhone app (TileCrypto.swift) writes and reads the same
 *  bytes with the same key, so backups move between the two.
 *
 *  javax.crypto's AES/GCM/NoPadding (CryptoKit on the iPhone). */
object TileCrypto {
    /** The Swift `Failure` cases. */
    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** Not a sealed tile: it doesn't start with `magic`. */
        class NotSealed : Failure("Not a sealed tile")

        /** Too short, or the tag doesn't match: the tile was changed or damaged, belongs to another place, or was
         *  sealed with another key. */
        class Unreadable : Failure("The tile doesn't open")

        /** The cipher, or the random nonce, failed. */
        class CipherFailed(cause: Throwable) : Failure("The cipher failed", cause)
    }

    /** "SMT" and the format version, 1 (a new array each time: an array can be changed). */
    val magic: ByteArray get() = byteArrayOf(0x53, 0x4D, 0x54, 0x01)
    const val nonceSize = 12
    const val tagSize = 16

    /** Whether `blob` is in the sealed format (it starts with `magic`); a plain tile is an image. */
    fun isSealed(blob: ByteArray): Boolean =
        blob.size >= 4 && blob[0] == 0x53.toByte() && blob[1] == 0x4D.toByte() && blob[2] == 0x54.toByte() &&
            blob[3] == 0x01.toByte()

    /** The tile's data sealed for its place, with a new random nonce. */
    fun seal(data: ByteArray, tile: TileID): ByteArray = seal(data, tile, ByteArray(nonceSize).also { random.nextBytes(it) })

    /** The plain tile from a sealed one. Throws `Failure` if it isn't sealed (`isSealed`) or doesn't open for this
     *  place. */
    fun open(blob: ByteArray, tile: TileID): ByteArray {
        if (!isSealed(blob)) throw Failure.NotSealed()
        if (blob.size < 4 + nonceSize + tagSize) throw Failure.Unreadable()
        return try {
            cipher(Cipher.DECRYPT_MODE, blob.copyOfRange(4, 4 + nonceSize), tile)
                .doFinal(blob, 4 + nonceSize, blob.size - 4 - nonceSize) // ciphertext and tag
        } catch (e: AEADBadTagException) {
            throw Failure.Unreadable()
        } catch (e: GeneralSecurityException) {
            throw Failure.CipherFailed(e)
        }
    }

    /** `seal` with a given nonce: for the tests, which check that both apps write the same bytes. A nonce must never
     *  be used twice with the key. */
    internal fun seal(data: ByteArray, tile: TileID, nonce: ByteArray): ByteArray {
        require(nonce.size == nonceSize)
        val sealed = try {
            cipher(Cipher.ENCRYPT_MODE, nonce, tile).doFinal(data) // ciphertext and tag
        } catch (e: GeneralSecurityException) {
            throw Failure.CipherFailed(e)
        }
        return magic + nonce + sealed
    }

    /** "satmap/{z}/{x}/{y}", the tile's XYZ coordinates. */
    fun associatedData(tile: TileID): ByteArray = "satmap/${tile.z}/${tile.x}/${tile.y}".toByteArray(Charsets.UTF_8)

    private fun cipher(mode: Int, nonce: ByteArray, tile: TileID): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(tagSize * 8, nonce))
            updateAAD(associatedData(tile))
        }

    private val random = SecureRandom()

    // The key

    /** The app's one 256-bit key, built into the app (here, and the same bytes in TileCrypto.swift on the iPhone) as
     *  two random 32-byte halves XORed together the first time it is needed, so it isn't a plain string in the APK.
     *  It is in the app, not made on each phone, so backups restore on the user's other phones, Android or iPhone.
     *  It stops someone copying the offline files off a phone (or from a backup) and showing the imagery in QGIS or
     *  another app. It doesn't stop someone who takes the app apart and extracts the key. */
    private val key: SecretKeySpec by lazy {
        val mask = bytes(
            0x82, 0x9E, 0x31, 0x88, 0x5F, 0x1A, 0x52, 0xD6, 0x57, 0x5B, 0x1E, 0x90, 0xAF, 0x3E, 0xD9, 0x3F,
            0xD0, 0xB9, 0xEA, 0x3A, 0xC6, 0x88, 0x46, 0x29, 0xA4, 0xA2, 0x29, 0xFE, 0x86, 0x18, 0x71, 0x52,
        )
        val masked = bytes(
            0x58, 0xCD, 0x76, 0x80, 0x0E, 0x86, 0xDA, 0x3F, 0xE0, 0x57, 0x4E, 0x4F, 0x40, 0xD6, 0x9E, 0x17,
            0xE8, 0x04, 0x50, 0x73, 0x98, 0x43, 0xF1, 0x27, 0x6C, 0xBA, 0x08, 0x75, 0xA5, 0x11, 0x0A, 0xE1,
        )
        SecretKeySpec(ByteArray(32) { (mask[it].toInt() xor masked[it].toInt()).toByte() }, "AES")
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
