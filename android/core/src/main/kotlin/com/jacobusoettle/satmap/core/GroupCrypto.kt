package com.jacobusoettle.satmap.core

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Group records are sealed on the phones with AES-256-GCM (docs/groups/SPEC.md §3); the server only stores and
 *  forwards them. An envelope is the format version 0x01, a 12-byte random nonce, the ciphertext and the 16-byte tag.
 *  Keys are HKDF-SHA256 subkeys of the 256-bit group key, salted with the group id (§3.3), and the associated data
 *  binds each record to its group, member, window, time or pin (§3.4, `GroupAAD`).
 *
 *  javax.crypto's AES/GCM/NoPadding, as `TileCrypto` uses it, with the key and the AAD as parameters; HKDF is RFC 5869
 *  on `Mac.getInstance("HmacSHA256")`. The iPhone core (GroupCrypto.swift, CryptoKit) seals and opens the same bytes:
 *  the shared vectors check both. */
object GroupCrypto {
    /** The Swift `Failure` cases. */
    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** The envelope has another format version: a newer app's record, skipped quietly. */
        class UnsupportedVersion : Failure("Another record format version")

        /** Too short, or the tag doesn't match: damaged, moved to another group, member, window or time, or sealed
         *  with another key. */
        class Unreadable : Failure("The record doesn't open")

        /** The cipher failed (a bad key or nonce size). */
        class CipherFailed(cause: Throwable? = null) : Failure("The cipher failed", cause)
    }

    const val version: Byte = 0x01
    const val nonceSize = 12
    const val tagSize = 16

    /** Version, nonce and tag around an empty plaintext. */
    const val minEnvelopeSize = 1 + 12 + 16
    const val keySize = 32

    /** HKDF-SHA256 with the group key as input, the group id's 16 raw bytes as salt and
     *  "satmap.group.v1|key|{purpose}" as info; 32 bytes (§3.3). */
    fun subkey(groupKey: ByteArray, groupId: ByteArray, purpose: String): ByteArray {
        val info = ("satmap.group.v1|key|$purpose").toByteArray(Charsets.UTF_8)
        // RFC 5869: PRK = HMAC(salt, IKM); with L = 32 the output is the first block, HMAC(PRK, info ‖ 0x01).
        val prk = hmacSHA256(key = groupId, data = groupKey)
        return hmacSHA256(key = prk, data = info + byteArrayOf(0x01))
    }

    /** The plaintext sealed with a new random nonce. */
    fun seal(plain: ByteArray, key: ByteArray, aad: String): ByteArray = seal(plain, key, aad, GroupRandom.bytes(nonceSize))

    /** `seal` with a given nonce: only for the tests, which check the shared vectors. A nonce must never be used twice
     *  with a key. */
    fun seal(plain: ByteArray, key: ByteArray, aad: String, nonce: ByteArray): ByteArray {
        if (key.size != keySize || nonce.size != nonceSize) throw Failure.CipherFailed()
        val sealed = try {
            cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain) // ciphertext and tag
        } catch (e: GeneralSecurityException) {
            throw Failure.CipherFailed(e)
        }
        return byteArrayOf(version) + nonce + sealed
    }

    /** The plaintext of an envelope. Throws `UnsupportedVersion` for another format version, `Unreadable` if it is
     *  too short or doesn't open with this key and AAD. */
    fun open(envelope: ByteArray, key: ByteArray, aad: String): ByteArray {
        if (envelope.isEmpty()) throw Failure.Unreadable()
        if (envelope[0] != version) throw Failure.UnsupportedVersion()
        if (envelope.size < minEnvelopeSize) throw Failure.Unreadable()
        if (key.size != keySize) throw Failure.CipherFailed()
        return try {
            cipher(Cipher.DECRYPT_MODE, key, envelope.copyOfRange(1, 1 + nonceSize), aad)
                .doFinal(envelope, 1 + nonceSize, envelope.size - 1 - nonceSize) // ciphertext and tag
        } catch (e: AEADBadTagException) {
            throw Failure.Unreadable()
        } catch (e: GeneralSecurityException) {
            throw Failure.Unreadable()
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(tagSize * 8, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }

    private fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/** A group's keys (§3.3): the four group-wide values derived once, each member's trail and live keys on first use.
 *  Kept in memory only. */
class GroupKeys(groupKey: ByteArray, groupId: ByteArray) {
    private val groupKey = groupKey.copyOf()
    private val groupIdBytes = groupId.copyOf()

    /** The group id's 22-character text. */
    val groupId: String = Base64URL.encode(groupId)
    val group: ByteArray = GroupCrypto.subkey(groupKey, groupIdBytes, "group")
    val member: ByteArray = GroupCrypto.subkey(groupKey, groupIdBytes, "member")
    val pin: ByteArray = GroupCrypto.subkey(groupKey, groupIdBytes, "pin")

    /** `subkey("join")`: not an encryption key but the proof sent at create, preview and redeem (SD4). */
    val joinProof: ByteArray = GroupCrypto.subkey(groupKey, groupIdBytes, "join")

    private val cache = HashMap<String, ByteArray>()

    /** The trail key of a member (SD26), by the member's 22-character id. */
    fun trail(memberId: String): ByteArray = key("trail|$memberId")

    /** The live key of a member (SD26). */
    fun live(memberId: String): ByteArray = key("live|$memberId")

    private fun key(purpose: String): ByteArray = synchronized(cache) {
        cache.getOrPut(purpose) { GroupCrypto.subkey(groupKey, groupIdBytes, purpose) }
    }
}

/** The associated data of each record kind (§3.4): fields joined by "|", ids as their 22-character text, integers in
 *  decimal. */
object GroupAAD {
    fun group(groupId: String): String = "satmap.group.v1|group|$groupId"

    fun member(groupId: String, memberId: String): String = "satmap.group.v1|member|$groupId|$memberId"

    fun trail(groupId: String, memberId: String, start: Long): String = "satmap.group.v1|trail|$groupId|$memberId|$start"

    fun live(groupId: String, memberId: String, at: Long): String = "satmap.group.v1|live|$groupId|$memberId|$at"

    /** `expiresAt` is "-" for a pin that doesn't expire. */
    fun pin(groupId: String, pinId: String, memberId: String, createdAt: Long, expiresAt: Long?): String =
        "satmap.group.v1|pin|$groupId|$pinId|$memberId|$createdAt|${expiresAt?.toString() ?: "-"}"
}
