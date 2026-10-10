package dev.tomato.hongguotv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Google TV Streamer cancels every injected gesture (no touchscreen or faketouch feature), while the touch
 * emulator used for testing accepts them, so a gesture path would pass every emulator check and do nothing on
 * the real device. Everything must go through node actions.
 */
class NoTouchInjectionTest {
    @Test fun mainSourcesNeverInjectTouches() {
        val forbidden = listOf("dispatchGesture", "GestureDescription", "canPerformGestures")
        val offenders = File("src/main").walk().filter { it.isFile }
            .flatMap { f -> forbidden.filter { it in f.readText() }.map { "${f.path}: $it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
