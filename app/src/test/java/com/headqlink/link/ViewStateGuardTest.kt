package com.headqlink.link

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** El estado de las vistas solo se restaura si lo guardó este proceso (10-11: cierre al recrear HomeActivity). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ViewStateGuardTest {
    @Test
    fun onlyTheSameProcessKeepsTheViews() {
        assertFalse(ViewStateGuard.otherProcess(1234, 1234))
        assertTrue(ViewStateGuard.otherProcess(1234, 5678))
        // Sin la marca (guardado por una versión anterior): otro proceso.
        assertTrue(ViewStateGuard.otherProcess(-1, 5678))
    }

    @Test
    fun aStateFromAnotherProcessLosesOnlyTheViews() {
        val b = Bundle()
        b.putBundle(ViewStateGuard.VIEWS, Bundle())
        b.putBoolean("mine", true)
        b.putInt(ViewStateGuard.PID, -42)
        ViewStateGuard.strip(b)
        assertNull(b.getBundle(ViewStateGuard.VIEWS))
        assertTrue(b.getBoolean("mine"))
    }

    @Test
    fun aStateFromThisProcessIsKept() {
        val b = Bundle()
        b.putBundle(ViewStateGuard.VIEWS, Bundle())
        ViewStateGuard.mark(b)
        ViewStateGuard.strip(b)
        assertNotNull(b.getBundle(ViewStateGuard.VIEWS))
        assertNull(ViewStateGuard.strip(null))
    }
}
