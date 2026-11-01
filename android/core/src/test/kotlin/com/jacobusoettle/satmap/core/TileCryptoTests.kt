package com.jacobusoettle.satmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.concurrent.thread

/** Offline tiles are stored sealed (`TileCrypto`), the same bytes on Android and on the iPhone: the Swift tests
 *  (Tests/SatMapCoreTests/TileCryptoTests.swift) are the same, with the same values. */
class TileCryptoTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    private val tile = TileID(z = 17, x = 72427, y = 79652)

    @Test
    fun testSealAndOpen() {
        val data = ByteArray(1000) { (it % 253).toByte() }
        val sealed = TileCrypto.seal(data, tile)
        assertTrue(TileCrypto.isSealed(sealed))
        assertArrayEquals(byteArrayOf(0x53, 0x4D, 0x54, 0x01), sealed.copyOf(4))
        assertEquals(4 + 12 + data.size + 16, sealed.size)
        assertFalse(contains(sealed, data.copyOf(16)))
        assertArrayEquals(data, TileCrypto.open(sealed, tile))
        // A new nonce each time.
        assertFalse(TileCrypto.seal(data, tile).copyOfRange(4, 16).contentEquals(sealed.copyOfRange(4, 16)))
        assertArrayEquals(ByteArray(0), TileCrypto.open(TileCrypto.seal(ByteArray(0), tile), tile))
    }

    @Test
    fun testOpensOnlyInItsOwnPlace() {
        val sealed = TileCrypto.seal("not really a JPEG".toByteArray(), tile)
        for (other in listOf(TileID(z = 17, x = 72428, y = 79652), TileID(z = 17, x = 72427, y = 79653), TileID(z = 18, x = 72427, y = 79652))) {
            assertThrows("$other", TileCrypto.Failure::class.java) { TileCrypto.open(sealed, other) }
        }
        // A changed byte (nonce, ciphertext, tag) or a cut end is refused, and a plain tile isn't sealed.
        for (i in listOf(4, 16, sealed.size - 1)) {
            val changed = sealed.copyOf()
            changed[i] = (changed[i].toInt() xor 1).toByte()
            assertThrows("byte $i", TileCrypto.Failure::class.java) { TileCrypto.open(changed, tile) }
        }
        assertThrows(TileCrypto.Failure::class.java) { TileCrypto.open(sealed.copyOf(sealed.size - 1), tile) }
        assertThrows(TileCrypto.Failure::class.java) { TileCrypto.open(sealed.copyOf(31), tile) }
        assertThrows(TileCrypto.Failure::class.java) { TileCrypto.open("not really a JPEG".toByteArray(), tile) }
    }

    @Test
    fun testImagesAreNotSealed() {
        for (header in listOf(listOf(0xFF, 0xD8, 0xFF, 0xE0), listOf(0x89, 0x50, 0x4E, 0x47), "RIFF".map { it.code }, "GIF8".map { it.code },
                              listOf(0x53, 0x4D, 0x54, 0x02), listOf(0x53, 0x4D, 0x54))) {
            assertFalse("$header", TileCrypto.isSealed(ByteArray(header.size) { header[it].toByte() } + ByteArray(40)))
        }
        assertFalse(TileCrypto.isSealed(BackupFixture.terrainData))
        assertFalse(TileCrypto.isSealed(ByteArray(0)))
    }

    @Test
    fun testSameBytesAsTheIPhone() {
        // With a fixed nonce (the app never uses one twice): the iPhone test expects the same bytes, and so does
        // another AES-GCM implementation (Python's cryptography) given the key.
        val nonce = ByteArray(12) { it.toByte() }
        assertArrayEquals("satmap/17/72427/79652".toByteArray(), TileCrypto.associatedData(tile))
        assertEquals("534d5401000102030405060708090a0be88d56074b197cc14e7c0dc734305e2abf1b79206f97fd09dc2be8420ccc6abd09",
                     hex(TileCrypto.seal("not really a JPEG".toByteArray(), tile, nonce)))
        assertEquals("534d5401000102030405060708090a0bc30a8e200b9281f3891810cd15308c84",
                     hex(TileCrypto.seal(ByteArray(0), tile, nonce)))
        val iPhone = bytesFromHex("534d5401000102030405060708090a0be88d56074b197cc14e7c0dc734305e2abf1b79206f97fd09dc2be8420ccc6abd09")
        assertArrayEquals("not really a JPEG".toByteArray(), TileCrypto.open(iPhone, tile))
    }

    // In the tile store

    @Test
    fun testStoresTilesSealedAndReadsThemPlain() {
        val store = MBTiles(File(tempDir(), "t.mbtiles"), create = true)
        try {
            assertTrue(store.isEncrypted) // a new file
            val image = "not really a JPEG".toByteArray()
            assertTrue(store.put(tile, data = image))
            val stored = store.storedTile(tile)!!
            assertTrue(TileCrypto.isSealed(stored))
            assertArrayEquals(image, TileCrypto.open(stored, tile))
            assertArrayEquals(image, store.tile(tile))
            // Delivered sealed (as a tile server may): stored as it comes.
            val next = TileID(z = 17, x = 72428, y = 79652)
            val sealed = TileCrypto.seal(image, next)
            assertTrue(store.put(next, data = sealed))
            assertArrayEquals(sealed, store.storedTile(next))
            assertArrayEquals(image, store.tile(next))
            // A tile sealed for another place doesn't open: it is missing.
            val moved = TileID(z = 17, x = 72429, y = 79652)
            assertTrue(store.put(moved, data = sealed))
            assertNull(store.tile(moved))
            assertNull(store.tile(moved))
            assertTrue(store.flush())
        } finally {
            store.close()
        }
    }

    @Test
    fun testPlainTilesFromBeforeAreReadAsTheyAre() {
        val url = File(tempDir(), "old.mbtiles")
        val tiles = plainTiles(3)
        writePlainStore(url, tiles)
        // A download opening the file to go on doesn't mark it: it has plain tiles. What it adds is sealed.
        val store = MBTiles(url, create = true)
        try {
            assertFalse(store.isEncrypted)
            for ((t, data) in tiles) {
                assertArrayEquals(data, store.storedTile(t))
                assertArrayEquals(data, store.tile(t))
            }
            val added = TileID(z = 18, x = 999, y = 2000)
            assertTrue(store.put(added, data = "added".toByteArray()))
            assertTrue(TileCrypto.isSealed(store.storedTile(added)!!))
            assertArrayEquals("added".toByteArray(), store.tile(added))
            assertArrayEquals(tiles[1].second, store.tile(tiles[1].first))
            assertFalse(store.isEncrypted)
        } finally {
            store.close()
        }
        // An old file without tiles has nothing plain: marked when a download opens it.
        val empty = File(tempDir(), "empty.mbtiles")
        writePlainStore(empty, emptyList())
        MBTiles(empty, create = false).use { assertFalse(it.isEncrypted) }
        MBTiles(empty, create = true).use { assertTrue(it.isEncrypted) }
    }

    @Test
    fun testEncryptsPlainTilesInPlaceAndResumes() {
        val url = File(tempDir(), "old.mbtiles")
        val tiles = plainTiles(300)
        writePlainStore(url, tiles)
        val store = MBTiles(url, create = false)
        // A download writing meanwhile, through a connection of its own.
        val download = MBTiles(url, create = true)
        try {
            assertFalse(store.isEncrypted)

            // Stopped after two batches: those are sealed, the rest still plain, and every tile reads. The file says how
            // far it got.
            val reports = ArrayList<List<Long>>()
            assertFalse(store.encryptPlainTiles(batchSize = 64) { checked, total ->
                reports.add(listOf(checked, total))
                reports.size < 2
            })
            assertEquals(listOf(listOf(64L, 300L), listOf(128L, 300L)), reports)
            assertEquals(128, tiles.count { TileCrypto.isSealed(store.storedTile(it.first) ?: ByteArray(0)) })
            assertFalse(store.isEncrypted)
            assertEquals("128", store.metadata(MBTiles.encryptAfterKey))
            assertEquals("128", store.metadata(MBTiles.encryptCheckedKey))
            for ((t, data) in tiles) assertArrayEquals("$t", data, store.tile(t))

            // The download replaces a plain tile and adds one.
            val added = TileID(z = 18, x = 999, y = 2000)
            assertTrue(download.put(tiles[200].first, data = "new".toByteArray()))
            assertTrue(download.put(added, data = "added".toByteArray()))
            assertTrue(download.flush())

            // Run again on a store of its own (as after the app started again), it carries on where it stopped: the
            // rows checked before aren't read again (the replaced tile was added after them, sealed).
            MBTiles(url, create = false).use { again ->
                reports.clear()
                assertTrue(again.encryptPlainTiles(batchSize = 64) { checked, total ->
                    reports.add(listOf(checked, total))
                    true
                })
                assertEquals(listOf(listOf(192L, 301L), listOf(256L, 301L), listOf(301L, 301L)), reports)
                assertTrue(again.isEncrypted)
                assertNull(again.metadata(MBTiles.encryptAfterKey))
                assertNull(again.metadata(MBTiles.encryptCheckedKey))
            }
            assertTrue(store.isEncrypted)
            assertEquals(301L, store.tileCount())
            for ((i, pair) in tiles.withIndex()) {
                val (t, data) = pair
                assertTrue("$t", TileCrypto.isSealed(store.storedTile(t) ?: ByteArray(0)))
                assertArrayEquals("$t", if (i == 200) "new".toByteArray() else data, store.tile(t))
            }
            assertArrayEquals("added".toByteArray(), store.tile(added))
            // Done: at once, without checking the tiles again.
            assertTrue(store.encryptPlainTiles { _, _ ->
                fail("checked again")
                true
            })
        } finally {
            download.close()
            store.close()
        }
        MBTiles(url, create = false).use { assertTrue(it.isEncrypted) }
    }

    @Test
    fun testNoPlainPictureIsLeftInTheFile() {
        // Small tiles, and large ones that spill onto overflow pages: the space each took is overwritten, and the
        // sealed pages are folded into the database file, while the map's store stays open.
        val url = File(tempDir(), "old.mbtiles")
        val tiles = (0 until 40).map { i ->
            TileID(z = 16, x = 500 + i, y = 700) to "PLAIN PICTURE $i ".repeat(if (i % 2 == 0) 20 else 600).toByteArray()
        }
        writePlainStore(url, tiles)
        val picture = "PLAIN PICTURE".toByteArray()
        val wal = File(url.path + "-wal")
        assertTrue(contains(url.readBytes(), picture))
        val map = MBTiles(url, create = false)
        val store = MBTiles(url, create = false)
        try {
            assertArrayEquals(tiles[0].second, map.tile(tiles[0].first))

            // A reader in the middle of a read keeps the checkpoint from finishing: it stops soon, the tiles sealed and
            // the file marked, but the plain pages still in the database file.
            DriverManager.getConnection("jdbc:sqlite:${url.path}").use { reader ->
                reader.autoCommit = false
                reader.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM tiles").use { it.next() } }
                val start = System.nanoTime()
                assertFalse(store.encryptPlainTiles(batchSize = 16))
                assertTrue(System.nanoTime() - start < 5_000_000_000L)
                assertTrue(store.isEncrypted)
                assertTrue(contains(url.readBytes(), picture))
                reader.commit()
            }

            // Run again, only the checkpoint is left. Then neither the file nor its log holds a plain picture.
            assertTrue(store.encryptPlainTiles(batchSize = 16) { _, _ ->
                fail("checked again")
                true
            })
            assertFalse(contains(url.readBytes(), picture))
            assertFalse(wal.exists() && contains(wal.readBytes(), picture))
            for ((t, data) in tiles) assertArrayEquals(data, map.tile(t))
        } finally {
            store.close()
            map.close()
        }
    }

    @Test
    fun testADownloadKeepingTheFileBusyDoesntHoldUpTheMap() {
        val url = File(tempDir(), "old.mbtiles")
        val tiles = plainTiles(300)
        writePlainStore(url, tiles)
        // Run on the map's store while a download, through a connection of its own, keeps its transaction open (as it
        // does for 256 tiles at a time).
        val store = MBTiles(url, create = false)
        val download = MBTiles(url, create = true)
        try {
            assertTrue(download.put(TileID(z = 18, x = 999, y = 2000), data = "added".toByteArray()))

            var encrypted: Boolean? = null
            val start = System.nanoTime()
            val worker = thread { encrypted = store.encryptPlainTiles(batchSize = 64) }
            // Meanwhile the map's reads wait for it no longer than about a batch.
            var longestRead = 0L
            var i = 0
            while (worker.isAlive) {
                val readStart = System.nanoTime()
                assertArrayEquals(tiles[i % tiles.size].second, store.tile(tiles[i % tiles.size].first))
                longestRead = maxOf(longestRead, System.nanoTime() - readStart)
                i += 1
            }
            worker.join()
            // It stops soon, to run again later.
            assertEquals(false, encrypted)
            assertTrue(System.nanoTime() - start < 5_000_000_000L)
            assertTrue("${longestRead / 1_000_000} ms", longestRead < 1_000_000_000L)
            assertFalse(store.isEncrypted)

            // Once the download has committed, it runs.
            assertTrue(download.flush())
            assertTrue(store.encryptPlainTiles())
            assertTrue(store.isEncrypted)
            for ((t, data) in tiles) assertArrayEquals("$t", data, store.tile(t))
        } finally {
            download.close()
            store.close()
        }
    }

    // Helpers

    /** Tiles of a few hundred bytes to a few kilobytes, each different. */
    private fun plainTiles(count: Int): List<Pair<TileID, ByteArray>> = (0 until count).map { i ->
        TileID(z = 18, x = 1000 + i, y = 2000) to "plain tile $i ".repeat(20 + i % 7 * 50).toByteArray()
    }

    /** A tile file as the app wrote it before tiles were encrypted: the tiles stored plain, the file not marked.
     *  Written with `secure_delete`, so it holds no scraps of what SQLite moved while writing it (which
     *  `encryptPlainTiles` doesn't clear). */
    private fun writePlainStore(url: File, tiles: List<Pair<TileID, ByteArray>>) {
        MBTiles(url, create = true).close()
        DriverManager.getConnection("jdbc:sqlite:${url.path}").use { c ->
            c.createStatement().use { it.execute("PRAGMA secure_delete=ON") }
            c.autoCommit = false
            c.createStatement().use { it.executeUpdate("DELETE FROM metadata WHERE name = '${MBTiles.encryptedKey}'") }
            c.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { s ->
                for ((t, data) in tiles) {
                    s.setInt(1, t.z)
                    s.setInt(2, t.x)
                    s.setInt(3, (1 shl t.z) - 1 - t.y)
                    s.setBytes(4, data)
                    s.executeUpdate()
                }
            }
            c.commit()
        }
    }
}
