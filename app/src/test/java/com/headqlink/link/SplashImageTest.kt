package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File

/** La imagen del coche para la pantalla de arranque (28-10): se guarda desde el 3D y se vuelve a leer; lo raro se ignora. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class SplashImageTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun savedImageComesBack() {
        File(ctx.filesDir, "car-splash.png").delete()
        assertNull(SplashView.loadCarImage(ctx))
        val bmp = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        SplashView.saveCarImage(ctx, dataUrl)
        // Se guarda en su hilo: se espera a que aparezca.
        val f = File(ctx.filesDir, "car-splash.png")
        var tries = 0
        while (!f.exists() && tries++ < 50) Thread.sleep(20)
        val back = SplashView.loadCarImage(ctx)
        assertNotNull(back)
        assertEquals(8, back!!.width)
        assertEquals(4, back.height)
    }

    @Test
    fun oddInputIsIgnored() {
        File(ctx.filesDir, "car-splash.png").delete()
        SplashView.saveCarImage(ctx, null)
        SplashView.saveCarImage(ctx, "")
        SplashView.saveCarImage(ctx, "data:image/jpeg;base64,AAAA")
        SplashView.saveCarImage(ctx, "no es una url")
        Thread.sleep(100)
        assertNull(SplashView.loadCarImage(ctx))
    }
}
