package com.headqlink.link

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/**
 * La pantalla completa de los juegos se queda puesta para los siguientes (salir de ella en un juego es el camino para
 * volver a la consola y no la quita): se apaga y se enciende con «Abrir a pantalla completa» en la lista de la consola.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class GameFullscreenListTest {
    private val ctx = RuntimeEnvironment.getApplication().also { Str.init(it) }

    private val host = object : CarScreen.Host {
        override fun context() = ctx
        override fun setContent(v: View) {}
        override fun width() = 1600
        override fun height() = 882
        override fun post(r: Runnable) = r.run()
        override fun setFullscreen(on: Boolean) {}
    }

    private fun find(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun theConsoleListSwitchesFullScreenForTheNextGames() {
        // Como tras tocar «Pantalla completa» en un juego y volver a la lista.
        Config(ctx).putBool(Config.GAME_FULLSCREEN, true)
        for (console in listOf(GameConsole.NES, GameConsole.PSX)) {
            val list = NesScreen(host, {}, console).browser()
            val toggle = find(list, Str.get(R.string.hql_game_full_open))
            assertNotNull(console.title, toggle)
            assertTrue(Config(ctx).gameFullscreen())
            toggle!!.performClick()
            assertFalse(Config(ctx).gameFullscreen())
            toggle.performClick()
            assertTrue(Config(ctx).gameFullscreen())
        }
    }
}
