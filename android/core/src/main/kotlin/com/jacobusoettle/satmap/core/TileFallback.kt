package com.jacobusoettle.satmap.core

import kotlin.math.max
import kotlin.math.min

/** Stand-ins for a tile that hasn't loaded yet, so the map never goes black. */
object TileFallback {
    /** The nearest ancestor, at most `maxLevels` up, that `has` says is available. */
    fun ancestor(t: TileID, maxLevels: Int, has: (TileID) -> Boolean): TileID? {
        if (t.z <= 0 || maxLevels <= 0) return null
        for (k in 1..min(maxLevels, t.z)) {
            val a = t.ancestor(levels = k)
            if (has(a)) return a
        }
        return null
    }
}

/** A thread-safe cache that keeps the most recently used values (lookups are O(1); when full, the least recently used
 *  quarter is dropped at once). */
class RecentCache<Key : Any, Value : Any>(capacity: Int) {
    private var capacity: Int = max(1, capacity)
    private var values = HashMap<Key, Entry<Value>>()
    private var clock = 0L
    private val lock = Any()

    private class Entry<Value>(val value: Value, val used: Long)

    val count: Int get() = synchronized(lock) { values.size }

    fun value(key: Key): Value? = synchronized(lock) {
        val entry = values[key] ?: return null
        clock += 1
        values[key] = Entry(entry.value, clock)
        entry.value
    }

    /** The value without marking it as used. */
    fun peek(key: Key): Value? = synchronized(lock) { values[key]?.value }

    fun contains(key: Key): Boolean = synchronized(lock) { values[key] != null }

    /** Marks a value as just used (so it is kept longer) and says whether there is one. */
    fun touch(key: Key): Boolean = synchronized(lock) {
        val entry = values[key] ?: return false
        clock += 1
        values[key] = Entry(entry.value, clock)
        true
    }

    fun set(value: Value, key: Key) {
        synchronized(lock) {
            clock += 1
            values[key] = Entry(value, clock)
            if (values.size > capacity) trimLocked(capacity * 3 / 4)
        }
    }

    fun remove(key: Key) {
        synchronized(lock) { values.remove(key) }
    }

    fun remove(shouldRemove: (Key, Value) -> Boolean) {
        synchronized(lock) {
            values = values.filterTo(HashMap()) { !shouldRemove(it.key, it.value.value) }
        }
    }

    /** Holds up to this many from now on (the least recently used go at once if there are more). */
    fun resize(capacity: Int) {
        synchronized(lock) {
            val c = max(1, capacity)
            if (c == this.capacity) return
            this.capacity = c
            trimLocked(c)
        }
    }

    /** Keeps only the `count` most recently used values. */
    fun trim(count: Int) {
        synchronized(lock) { trimLocked(count) }
    }

    private fun trimLocked(count: Int) {
        if (values.size <= count) return
        val keep = values.entries.sortedByDescending { it.value.used }.take(max(0, count))
        values = keep.associateTo(HashMap()) { it.key to it.value }
    }
}
