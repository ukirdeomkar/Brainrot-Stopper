package dev.brainrotstopper.feature.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundWindowResolverTest {

    private val appType = ForegroundWindowResolver.TYPE_APPLICATION
    private val imeType = 2 // AccessibilityWindowInfo.TYPE_INPUT_METHOD

    @Test
    fun `a focused keyboard does not replace the active application`() {
        val resolved = ForegroundWindowResolver.resolve(
            listOf(
                ForegroundWindowSnapshot(appType, isActive = true, isFocused = false, "com.google.android.youtube"),
                ForegroundWindowSnapshot(imeType, isActive = false, isFocused = true, "com.google.android.inputmethod.latin"),
            ),
        )

        assertEquals("com.google.android.youtube", resolved)
    }

    @Test
    fun `only application windows are considered`() {
        assertNull(
            ForegroundWindowResolver.resolve(
                listOf(
                    ForegroundWindowSnapshot(imeType, isActive = true, isFocused = true, "com.google.android.inputmethod.latin"),
                    ForegroundWindowSnapshot(3, isActive = false, isFocused = false, "com.android.systemui"),
                ),
            ),
        )
    }

    @Test
    fun `an active application wins over a merely focused one`() {
        val resolved = ForegroundWindowResolver.resolve(
            listOf(
                ForegroundWindowSnapshot(appType, isActive = false, isFocused = true, "com.example.old"),
                ForegroundWindowSnapshot(appType, isActive = true, isFocused = false, "com.example.new"),
            ),
        )

        assertEquals("com.example.new", resolved)
    }

    @Test
    fun `a single application window is used even when no flags are set`() {
        val resolved = ForegroundWindowResolver.resolve(
            listOf(ForegroundWindowSnapshot(appType, isActive = false, isFocused = false, "com.example.app")),
        )

        assertEquals("com.example.app", resolved)
    }

    @Test
    fun `a blank package name is ignored`() {
        assertNull(
            ForegroundWindowResolver.resolve(
                listOf(ForegroundWindowSnapshot(appType, isActive = true, isFocused = true, "  ")),
            ),
        )
    }

    @Test
    fun `an empty window list resolves to null`() {
        assertNull(ForegroundWindowResolver.resolve(emptyList()))
    }
}
