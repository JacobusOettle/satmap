package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The word filter (docs/groups/SPEC.md §8.12): the spec's table and the shared `wordFilter` vectors, as in the
 *  iPhone core. */
class WordFilterTests {
    private val testList = "badword\nworse*\n*nasty*\n"

    @Test
    fun testSpecTable() {
        val filter = WordFilter(testList)
        val cases = listOf(
            Triple("Badword", false, "•••"),
            Triple("b4dw0rd!", false, "•••!"),
            Triple("worsening", false, "•••"),
            Triple("supernastything", false, "•••"),
            Triple("n.a.s.t.y", false, "•••"),
            Triple("bad word", true, null),
            Triple("Scunthorpe", true, null),
            Triple("Kudu at the bridge", true, null),
        )
        for ((text, allows, masked) in cases) {
            assertEquals(text, allows, filter.allows(text))
            assertEquals(text, masked ?: text, filter.masked(text))
        }
    }

    @Test
    fun testVectors() {
        val v = GroupVectors.load()
        val filter = WordFilter(v.wordList)
        assertEquals(16, v.wordCases.size)
        for (c in v.wordCases) {
            assertEquals(c.text, c.allows, filter.allows(c.text))
            assertEquals(c.text, c.masked, filter.masked(c.text))
        }
    }

    /** SatMap/Resources/GroupWords.txt (written by the iPhone core part; the Android app ships it as an asset): it
     *  catches the words it lists in their usual disguises, and leaves the demo's texts and ordinary game-drive words
     *  alone. The same checks as the iPhone core's test; skipped until the list is in the tree. */
    @Test
    fun testShippedList() {
        val file = resource("GroupWords.txt")
        assumeTrue("GroupWords.txt is not in the tree yet", file.exists())
        val filter = WordFilter(file.readText(Charsets.UTF_8))
        for (text in listOf("Fucking hell", "what the f u c k", "FOK", "f0kken", "jou ma se poes", "Sipho is a kaffir", "k a f f i r",
                            "shithead", "Msunu", "kwerekwere", "Ｆｕｃｋ")) {
            assertFalse(text, filter.allows(text))
        }
        assertEquals("Sipho is a •••!", filter.masked("Sipho is a bitch!"))
        for (text in listOf("Kruger trip", "Drone team", "Weekend ride", "Braai club", "Kudu", "Three bulls", "Loose gravel on the bend",
                            "Two adults near the water hole", "Rhino", "Sam", "Thandi", "Pieter", "Lerato", "Sipho", "Ayesha", "Nomsa",
                            "Bongani", "Lindiwe", "Johan", "Zanele", "Scunthorpe", "Cock ostrich at the dam", "Dolerite dyke",
                            "Fire retardant strip", "Hoerskool reunion", "Fokus op die pad", "Shitake mushrooms", "Pakistan",
                            "Spicy food", "Cocktails at sunset", "Watergat by die hek", "Kakebeen", "Dickens", "Push it up the hill",
                            "Hyenas snigger")) {
            assertTrue(text, filter.allows(text))
        }
    }

    @Test
    fun testListParsing() {
        // Comments, blank lines, spaces, tabs and CR around entries; "*" and "**" alone are exact entries.
        val filter = WordFilter("# comment\r\n  badword \t\r\n\n\t*nasty*\r\n#worse*\nworse*\r\n*\n")
        assertFalse(filter.allows("badword"))
        assertFalse(filter.allows("supernasty"))
        assertFalse(filter.allows("worsen"))
        assertTrue(filter.allows("comment"))
        assertTrue(filter.allows("bad"))
        assertTrue(WordFilter("").allows("anything at all"))
        assertEquals("anything", WordFilter("").masked("anything"))
        // Overlapping matches merge; text around them stays.
        val two = WordFilter("kak\nkak*\n")
        assertEquals("••• •••!", two.masked("Kakkerlak kak!"))
        // Characters outside the BMP before a match keep their place.
        assertEquals("🦏 •••", WordFilter("kudu\n").masked("🦏 Kudu"))
    }
}
