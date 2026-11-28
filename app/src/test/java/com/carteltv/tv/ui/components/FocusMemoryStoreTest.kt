package com.carteltv.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Identity-based focus memory: content keys survive screen disposal; indexes do not. */
class FocusMemoryTest {

    @Test
    fun `remembers and peeks per zone`() {
        FocusMemoryStore.remember("movies", "cat1:42")
        FocusMemoryStore.remember("series", "cat2:7")

        assertEquals("cat1:42", FocusMemoryStore.peek("movies"))
        assertEquals("cat2:7", FocusMemoryStore.peek("series"))
    }

    @Test
    fun `later focus in same zone wins`() {
        FocusMemoryStore.remember("movies", "cat1:42")
        FocusMemoryStore.remember("movies", "cat1:99")

        assertEquals("cat1:99", FocusMemoryStore.peek("movies"))
    }

    @Test
    fun `null key never overwrites`() {
        FocusMemoryStore.remember("movies", "cat1:42")
        FocusMemoryStore.remember("movies", null)

        assertEquals("cat1:42", FocusMemoryStore.peek("movies"))
    }

    @Test
    fun `clear removes only the given zone`() {
        FocusMemoryStore.remember("movies", "cat1:42")
        FocusMemoryStore.remember("series", "cat2:7")
        FocusMemoryStore.clear("movies")

        assertNull(FocusMemoryStore.peek("movies"))
        assertEquals("cat2:7", FocusMemoryStore.peek("series"))
    }

    @Test
    fun `unknown zone peeks null`() {
        assertNull(FocusMemoryStore.peek("never-touched"))
    }
}
