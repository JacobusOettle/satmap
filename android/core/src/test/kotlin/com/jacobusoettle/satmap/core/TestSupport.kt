package com.jacobusoettle.satmap.core

import org.junit.Assert.fail
import java.io.File
import java.util.UUID

// Shared by the ported tests (CoreTests.swift's private helpers, and XCTest's comparisons that JUnit 4 lacks).

/** The contents of a file in Tests/SatMapCoreTests/Fixtures. */
fun fixture(name: String): ByteArray = File(requireProperty("satmap.fixtures"), name).readBytes()

/** A fresh, empty directory in the system's temporary directory. */
fun tempDir(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "satmap-tests-" + UUID.randomUUID())
    dir.mkdirs()
    return dir
}

/** A file the app ships, in SatMap/Resources. */
fun resource(name: String): File = File(requireProperty("satmap.resources"), name)

private fun requireProperty(name: String): String =
    System.getProperty(name) ?: error("System property $name is not set (core/build.gradle.kts sets it for the tests)")

/** XCTAssertGreaterThan. */
fun <T : Comparable<T>> assertGreaterThan(a: T, b: T, message: String = "") {
    if (a <= b) fail(describe(message, "$a is not greater than $b"))
}

/** XCTAssertGreaterThanOrEqual. */
fun <T : Comparable<T>> assertGreaterThanOrEqual(a: T, b: T, message: String = "") {
    if (a < b) fail(describe(message, "$a is less than $b"))
}

/** XCTAssertLessThan. */
fun <T : Comparable<T>> assertLessThan(a: T, b: T, message: String = "") {
    if (a >= b) fail(describe(message, "$a is not less than $b"))
}

/** XCTAssertLessThanOrEqual. */
fun <T : Comparable<T>> assertLessThanOrEqual(a: T, b: T, message: String = "") {
    if (a > b) fail(describe(message, "$a is greater than $b"))
}

private fun describe(message: String, what: String) = if (message.isEmpty()) what else "$what: $message"
