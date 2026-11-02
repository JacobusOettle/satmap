package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Text cleaning (§2.5), initials (§9.5) and "ago" times (§9.3): the shared vectors, as in the iPhone core. */
class GroupTextTests {
    private val v = GroupVectors.load()

    @Test
    fun testCleaningVectors() {
        assertEquals(30, v.groupText.size)
        for (c in v.groupText) {
            val codes = c.input.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) }
            assertEquals("${c.field} $codes", c.output, GroupText.clean(c.input, GroupTextField.valueOf(c.field)))
        }
    }

    @Test
    fun testCleaningDetails() {
        // NFC: a decomposed é counts as one code point.
        assertEquals("Zoë", GroupText.clean("Zoë", GroupTextField.displayName))
        assertEquals(3, GroupText.codePoints(GroupText.clean("Zoë", GroupTextField.displayName)!!))
        assertEquals(30, GroupText.codePoints(GroupText.clean("é".repeat(30), GroupTextField.displayName)!!))
        // The byte limit: 30 four-byte emoji are 120 bytes, the display name's maximum.
        assertNotNull(GroupText.clean("🦏".repeat(30), GroupTextField.displayName))
        assertNull(GroupText.clean("Kudu\u007F", GroupTextField.animal))
        assertNull(GroupText.clean("a b", GroupTextField.note))
        assertEquals("a\nb", GroupText.clean("a\nb", GroupTextField.reportComment))
        assertNull(GroupText.clean("a\nb", GroupTextField.displayName))
        assertEquals("", GroupText.clean("", GroupTextField.reportComment))
        assertNull(GroupText.clean("", GroupTextField.groupName))
        assertEquals("​", GroupText.clean("​", GroupTextField.groupName))
        assertEquals(5, GroupText.codePoints("Zoë 🦏"))
        // Kotlin's trim() would also strip U+001C–U+001F; those are control characters, so the text is invalid.
        assertNull(GroupText.clean("\u001CKruger", GroupTextField.groupName))
        // Half of a surrogate pair (impossible in a Swift string) is invalid.
        assertNull(GroupText.clean("Kruger\uD83E", GroupTextField.groupName))
        assertNull(GroupText.clean("\uDD8FKruger", GroupTextField.groupName))
    }

    @Test
    fun testInitialsVectors() {
        assertEquals(14, v.initials.size)
        for ((name, initials) in v.initials) {
            assertEquals(name, initials, GroupText.initials(name))
        }
        assertEquals("?", GroupText.initials(""))
        assertEquals("SJ", GroupText.initials("Sam　Jones"))
        assertEquals("1O", GroupText.initials("1️⃣ one"))
        assertEquals("🇿", GroupText.initials("🇿"))
    }

    @Test
    fun testAgoVectors() {
        assertEquals(12, v.ago.size)
        for ((seconds, text) in v.ago) {
            assertEquals("$seconds", text, GroupFormat.ago(seconds))
        }
    }
}
