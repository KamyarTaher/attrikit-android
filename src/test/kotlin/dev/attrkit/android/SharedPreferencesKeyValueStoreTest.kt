package dev.attrkit.android

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedPreferencesKeyValueStoreTest {
    @Test
    fun aWriteIsOneCommitAndNeverAnApply() {
        val prefs = FakeSharedPreferences(mapOf("gone" to "x"))
        val store = SharedPreferencesKeyValueStore(prefs)

        store.write(mapOf("a" to "1", "b" to "2", "gone" to null))

        // One commit carries the whole map. apply() flushes asynchronously, which would let a
        // crash land between a queue transition and its retry marker.
        assertEquals(1, prefs.commitCount)
        assertEquals(0, prefs.applyCount)
        assertEquals("1", store.get("a"))
        assertEquals("2", store.get("b"))
        assertNull(store.get("gone"))
    }

    @Test
    fun aRefusedCommitSurfacesAndLeavesTheOldStateInPlace() {
        val prefs = FakeSharedPreferences(mapOf("a" to "old"))
        prefs.commitResult = false
        val store = SharedPreferencesKeyValueStore(prefs)

        assertThrows(IOException::class.java) { store.write(mapOf("a" to "new", "b" to "x")) }

        assertEquals("old", store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun anEmptyWriteTouchesNothing() {
        val prefs = FakeSharedPreferences()
        SharedPreferencesKeyValueStore(prefs).write(emptyMap())
        assertEquals(0, prefs.commitCount + prefs.applyCount)
    }

    @Test
    fun aValueOfAnotherTypeReadsAsAbsent() {
        val store = SharedPreferencesKeyValueStore(FakeSharedPreferences(mapOf("n" to 5)))
        assertNull(store.get("n"))
        assertNull(store.get("missing"))
    }
}
