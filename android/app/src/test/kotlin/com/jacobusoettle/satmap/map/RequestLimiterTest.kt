package com.jacobusoettle.satmap.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `RequestLimiter`: the order waiting callers are served in, the background limit, and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestLimiterTest {
    @Test
    fun waitersGoUrgentThenNormalThenBackgroundNewestFirst() = runTest {
        val limiter = RequestLimiter(1)
        limiter.acquire(RequestPriority.normal) // the one connection, busy
        val order = mutableListOf<String>()
        val priorities = mapOf(
            "b1" to RequestPriority.background,
            "n1" to RequestPriority.normal,
            "u1" to RequestPriority.urgent,
            "n2" to RequestPriority.normal,
            "b2" to RequestPriority.background,
            "u2" to RequestPriority.urgent,
        )
        for ((name, p) in priorities) {
            launch {
                limiter.acquire(p)
                order.add(name)
            }
            runCurrent() // queued in this order
        }
        assertEquals(6, limiter.waitingCount)
        assertTrue(order.isEmpty())
        var held = RequestPriority.normal
        repeat(6) {
            limiter.release(held) // handed on to the next waiter
            runCurrent()
            held = priorities.getValue(order.last())
        }
        assertEquals(listOf("u2", "u1", "n2", "n1", "b2", "b1"), order)
        assertEquals(0, limiter.waitingCount)
    }

    @Test
    fun backgroundRequestsHoldAtMostTwoConnections() = runTest {
        val limiter = RequestLimiter(3)
        var background = 0
        repeat(3) {
            launch {
                limiter.acquire(RequestPriority.background)
                background++
            }
        }
        runCurrent()
        assertEquals(2, background)
        assertEquals(1, limiter.waitingCount)
        // An on-screen request still gets the third.
        var normal = false
        launch {
            limiter.acquire(RequestPriority.normal)
            normal = true
        }
        runCurrent()
        assertTrue(normal)
        // Freed by the on-screen one: still two in the background, so the waiter waits.
        limiter.release(RequestPriority.normal)
        runCurrent()
        assertEquals(2, background)
        assertEquals(1, limiter.waitingCount)
        // One of those done: the waiter takes its place.
        limiter.release(RequestPriority.background)
        runCurrent()
        assertEquals(3, background)
        assertEquals(0, limiter.waitingCount)
        // And the connection freed before is there for an on-screen request at once.
        var another = false
        launch {
            limiter.acquire(RequestPriority.urgent)
            another = true
        }
        runCurrent()
        assertTrue(another)
    }

    @Test
    fun aWaiterCancelledWhileQueuedLeavesTheQueue() = runTest {
        val limiter = RequestLimiter(1)
        limiter.acquire()
        var got = false
        val waiter = launch {
            limiter.acquire()
            got = true
        }
        runCurrent()
        assertEquals(1, limiter.waitingCount)
        waiter.cancel()
        runCurrent()
        assertEquals(0, limiter.waitingCount)
        assertFalse(got)
        // The connection, freed, goes to the next caller at once.
        limiter.release()
        var next = false
        launch {
            limiter.acquire()
            next = true
        }
        runCurrent()
        assertTrue(next)
    }

    @Test
    fun aWaiterCancelledAsItIsGivenAConnectionPassesItOn() = runTest {
        val limiter = RequestLimiter(1)
        limiter.acquire()
        var first = false
        var second = false
        launch {
            limiter.acquire()
            first = true
        }
        runCurrent()
        val newest = launch {
            limiter.acquire()
            second = true
        }
        runCurrent()
        limiter.release() // to the newest, which hasn't run yet …
        newest.cancel() // … and is cancelled before it does
        runCurrent()
        assertFalse(second)
        assertTrue(first)
        assertEquals(0, limiter.waitingCount)
    }
}
