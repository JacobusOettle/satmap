package com.jacobusoettle.satmap.groups

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.jacobusoettle.satmap.core.GroupSecret
import com.jacobusoettle.satmap.core.GroupSecretStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The memberships (member passes and group keys) on this phone only (docs/groups/SPEC.md §8.3): the JSON array of
 *  secrets, sealed with an Android Keystore key (alias `satmap.groups.v1`: AES-256, GCM, no padding, randomised, no
 *  user authentication), in `noBackupFilesDir/groups/secrets.bin` as the version byte 0x01, a 12-byte IV, then the
 *  ciphertext and its tag, written whole with `AtomicFile`. Neither the file (in `no_backup`, also excluded by both
 *  backup rule files) nor the key (the Keystore's never leave the phone) goes to backups or other devices. iOS keeps
 *  one Keychain item per group instead (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), an allowed difference
 *  (§9.9).
 *
 *  `loadAll` throws when the file is there but can't be read now (a Keystore error, a damaged file): the hub then
 *  deletes nothing and reads again later (§8.3). No file is no memberships. `key` gives the sealing key; tests pass a
 *  software key, as Robolectric has no AndroidKeyStore. Thread-safe. */
class KeystoreSecrets(
    private val file: File,
    private val key: () -> SecretKey = ::keystoreKey,
) : GroupSecretStore {
    private val atomic = AtomicFile(file)
    private val lock = Any()

    override fun loadAll(): List<GroupSecret> = synchronized(lock) { read() }

    override fun save(secret: GroupSecret) = synchronized(lock) {
        write(read().filter { it.groupId != secret.groupId } + secret)
    }

    override fun delete(groupId: String) = synchronized(lock) {
        val all = try {
            read()
        } catch (e: Exception) {
            // Can't read them now: nothing is deleted (the next save rewrites the file from what it holds).
            return@synchronized
        }
        if (all.none { it.groupId == groupId }) return@synchronized
        write(all.filter { it.groupId != groupId })
    }

    private fun read(): List<GroupSecret> {
        val bytes = try {
            atomic.readFully()
        } catch (e: FileNotFoundException) {
            return emptyList()
        }
        return GroupSecret.decodeAll(open(bytes, key()))
    }

    private fun write(secrets: List<GroupSecret>) {
        val sealed = seal(GroupSecret.encodeAll(secrets), key())
        file.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(sealed)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    companion object {
        const val ALIAS = "satmap.groups.v1"
        const val VERSION: Byte = 0x01
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        /** `noBackupFilesDir/groups/secrets.bin`. */
        fun file(noBackup: File) = File(File(noBackup, "groups"), "secrets.bin")

        /** The Keystore key, made the first time it is needed. */
        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }

        /** 0x01 ‖ IV (12, chosen by the cipher) ‖ AES-GCM ciphertext and tag. */
        fun seal(plain: ByteArray, key: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            check(iv.size == IV_SIZE) { "unexpected IV size" }
            return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
        }

        /** `seal`'s bytes opened; throws when they aren't (another version, too short, a wrong key or tag). */
        fun open(sealed: ByteArray, key: SecretKey): ByteArray {
            if (sealed.size < 1 + IV_SIZE + TAG_BITS / 8 || sealed[0] != VERSION) throw IOException("Not a secrets file")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_SIZE))
            return cipher.doFinal(sealed, 1 + IV_SIZE, sealed.size - 1 - IV_SIZE)
        }
    }
}
