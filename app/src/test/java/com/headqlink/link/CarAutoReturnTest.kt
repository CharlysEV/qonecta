package com.headqlink.link

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarAutoReturnTest {
    @Test fun reducedRailKeepsEqualGapsAndTheExpandButtonFitsWithAllButtonsAndFooterData() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val ui = CarUi(context, 1920, 882, 320, 200) { }
        val build = CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val hide = CarUi::class.java.getDeclaredMethod("setHidden", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val fit = CarUi::class.java.getDeclaredMethod("fitPanelButtons", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun field(name: String): Any = CarUi::class.java.getDeclaredField(name).apply { isAccessible = true }.get(ui)!!
        val root = build.invoke(ui, context) as View
        try {
            open.invoke(ui, "tv")
            hide.invoke(ui, true)
            // El caso más lleno: hora, temperatura y batería del coche visibles a la vez.
            (field("railTemp") as TextView).apply { text = "22°"; visibility = View.VISIBLE }
            (field("railSoc") as TextView).apply { text = "85 %"; visibility = View.VISIBLE }
            fit.invoke(ui, true, 96)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(882, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 882)
            val panel = field("panel") as ViewGroup
            @Suppress("UNCHECKED_CAST")
            val buttons = field("navButtons") as List<LinearLayout>
            val split = field("splitButton") as View
            val expand = field("expand") as View
            val footer = field("railInfo") as View
            val beforeSplit = buttons[buttons.size - 2]
            val settings = buttons.last()
            // Desde el 28-10 los botones van en una lista que se desplaza (panelScroll); «Ajustes» queda debajo de ella.
            val scroll = field("panelScroll") as View
            assertEquals(6, split.top - beforeSplit.bottom)
            assertEquals(6, settings.top - scroll.bottom)
            assertEquals(6, expand.top - settings.bottom)
            assertEquals(1, (buttons.map { it.height } + split.height).distinct().size)
            assertTrue(expand.bottom <= panel.height - panel.paddingBottom)
            assertTrue(footer.bottom <= panel.height - panel.paddingBottom)
        } finally { ui.stop() }
    }

    /** 28-10: con el panel desplegado, la lista llega hasta «Ajustes» y caben los 8 botones del usuario (antes, 5). */
    @Test fun expandedPanelListReachesSettingsAndShowsAllButtons() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        com.headqlink.link.Config(context).setPanelButtons(listOf("car", "web", "tv", "games", "guide", "companion", "karaoke"))
        val ui = CarUi(context, 1920, 882, 320, 200) { }
        val build = CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val hide = CarUi::class.java.getDeclaredMethod("setHidden", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun field(name: String): Any = CarUi::class.java.getDeclaredField(name).apply { isAccessible = true }.get(ui)!!
        val root = build.invoke(ui, context) as View
        try {
            open.invoke(ui, "settings")
            hide.invoke(ui, false)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(882, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 882)
            @Suppress("UNCHECKED_CAST")
            val buttons = field("navButtons") as List<LinearLayout>
            val settings = buttons.last()
            val scroll = field("panelScroll") as ViewGroup
            val list = scroll.getChildAt(0)
            assertEquals(6, settings.top - scroll.bottom)
            // Auto, los 7 elegidos y Ajustes; los de la lista, todos a la vista sin desplazar.
            assertEquals(9, buttons.size)
            assertTrue("la lista (" + list.height + ") debe caber en lo visible (" + scroll.height + ")", list.height <= scroll.height)
            assertTrue(buttons[buttons.size - 2].bottom <= scroll.height)
        } finally { ui.stop() }
    }

    @Test fun returningFromSectionsImmediatelyGivesAutoTheFullWidthBesideTheRail() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        var aaWidth = 0
        val ui = CarUi(context, 1920, 882, 320, 200, object : CarUi.Listener {
            override fun onAaVisible(visible: Boolean) {}
            override fun onAaRegion(x: Int, w: Int) { aaWidth = w }
        })
        val build = CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        build.invoke(ui, context) as View
        try {
            for (section in listOf("settings", "radio", "tv")) {
                open.invoke(ui, section)
                open.invoke(ui, "aa")
                assertTrue("Auto debe ocupar el espacio junto a la barra al volver de $section: $aaWidth", aaWidth > 1800)
            }
        } finally { ui.stop() }
    }
}
