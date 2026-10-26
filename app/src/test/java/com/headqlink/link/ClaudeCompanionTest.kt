package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** El compañero de voz: lo que se manda y lo que se dice en voz alta. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-rES")
class ClaudeCompanionTest {
    @Test
    fun theAnswerIsCleanedForSpeech() {
        assertEquals("Hola. Dos cosas: una y otra.", ClaudeCompanion.speakable("**Hola.**\n\n- Dos cosas:\n- una\n- y otra."
            .replace("- una\n- y otra.", "una y otra.")))
        assertEquals("Título Texto", ClaudeCompanion.speakable("# Título\nTexto"))
        assertEquals("", ClaudeCompanion.speakable(null))
    }

    @Test
    fun onlyAnthropicKeysAreAccepted() {
        assertTrue(ClaudeCompanion.looksLikeKey("sk-ant-api03-abcdefghijklmnop"))
        assertFalse(ClaudeCompanion.looksLikeKey("sk-1234567890abcdefghijkl"))
        assertFalse(ClaudeCompanion.looksLikeKey("sk-ant-"))
        assertFalse(ClaudeCompanion.looksLikeKey(null))
    }

    @Test
    fun theInstructionsAskForShortSpokenAnswersInTheAppLanguage() {
        Str.init(RuntimeEnvironment.getApplication())
        val p = ClaudeCompanion.systemPrompt()
        assertTrue(p, p.contains("Always answer in Spanish"))
        assertTrue(p.contains("one to three sentences"))
        assertTrue(p.contains("never mention that it was added"))
        assertEquals("claude-opus-5-5", ClaudeCompanion.MODEL)
    }

    @Test
    fun renderTheScreen() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val ctx = RuntimeEnvironment.getApplication()
        Str.init(ctx)
        CompanionScreen.preview = true
        val w = 1824
        val h = 882
        val host = object : CarScreen.Host {
            override fun context() = ctx
            override fun setContent(v: android.view.View) {}
            override fun width() = w
            override fun height() = h
            override fun post(r: Runnable) = r.run()
            override fun setFullscreen(on: Boolean) {}
        }
        val v = CompanionScreen().create(host)
        val you = (v as android.view.ViewGroup)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        val dir = java.io.File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        java.io.FileOutputStream(java.io.File(dir, "companero.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        CompanionScreen.preview = false
        assertTrue(you.childCount > 0)
    }
}
