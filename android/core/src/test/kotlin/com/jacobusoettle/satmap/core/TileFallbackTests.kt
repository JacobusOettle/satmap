package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileFallbackTests {
    @Test
    fun testFallbackUsesNearestAncestor() {
        val t = TileID(z = 18, x = 140_000, y = 150_000)
        val parent = t.ancestor(levels = 1)
        val far = t.ancestor(levels = 5)
        assertEquals(parent, TileFallback.ancestor(t, maxLevels = 10) { it == far || it == parent })
        assertEquals(far, TileFallback.ancestor(t, maxLevels = 10) { it == far })
        assertNull(TileFallback.ancestor(t, maxLevels = 4) { it == far })
        assertNull(TileFallback.ancestor(TileID(z = 0, x = 0, y = 0), maxLevels = 4) { true })
    }

    @Test
    fun testRecentCacheKeepsWhatWasUsedLast() {
        val cache = RecentCache<Int, String>(capacity = 4)
        for (i in 0 until 4) cache.set("$i", i)
        cache.value(0) // used again: kept
        cache.set("4", 4) // over capacity: down to 3, the least recently used go
        assertEquals(3, cache.count)
        assertNotNull(cache.value(0))
        assertNotNull(cache.value(4))
        assertNull(cache.value(1))
        cache.remove { key, _ -> key == 4 }
        assertFalse(cache.contains(4))
        cache.set("5", 5)
        cache.remove(5)
        assertFalse(cache.contains(5))
        cache.trim(1)
        assertEquals(1, cache.count)
    }

    @Test
    fun testRecentCachePeeksAndResizes() {
        // Peeking doesn't count as use; a smaller capacity drops the least recently used at once.
        val cache = RecentCache<Int, String>(capacity = 10)
        for (i in 0 until 6) cache.set("$i", i)
        assertEquals("0", cache.peek(0))
        cache.resize(3)
        assertEquals(3, cache.count)
        assertNull(cache.peek(0))
        assertEquals("5", cache.peek(5))
        cache.resize(6)
        for (i in 6 until 9) cache.set("$i", i)
        assertEquals(6, cache.count)
    }

    @Test
    fun testTouchedValuesOutlastNewerOnes() {
        // The coarse tiles around the screen are touched at every stop: many newer tiles don't push them out, and
        // checking for them (contains) doesn't count as use.
        val cache = RecentCache<Int, String>(capacity = 8)
        cache.set("coarse", 0)
        cache.set("idle", 1)
        for (i in 2 until 40) {
            cache.set("$i", i)
            assertTrue(cache.touch(0))
            cache.contains(1)
        }
        assertEquals("coarse", cache.value(0))
        assertNull(cache.value(1))
        assertFalse(cache.touch(1))
    }
}
