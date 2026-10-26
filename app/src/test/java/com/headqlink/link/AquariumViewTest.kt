package com.headqlink.link

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/** El acuario de Juegos: los peces se comen la comida y no se salen del agua. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class AquariumViewTest {
    private fun aquarium(): AquariumView {
        val app = RuntimeEnvironment.getApplication()
        Str.init(app)
        val v = AquariumView(app, Random(3))
        v.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1600, 700)
        return v
    }

    private fun tap(v: AquariumView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    @Test
    fun theFishEatTheFoodAndStayInTheWater() {
        val v = aquarium()
        assertEquals(AquariumView.START_FISH, v.fish.size)
        // Comida en un hueco sin peces (si se toca un pez, se asusta en vez de comer).
        var x = 800f
        while (v.fish.any { Math.abs(it.x - x) < it.size && Math.abs(it.y - 200f) < it.size }) x += 37f
        tap(v, x, 200f)
        assertEquals(AquariumView.FOOD_PER_TAP, v.food.size)
        var now = 1_000_000L
        repeat(20 * 30) {
            now += 33
            v.step(0.033f, now)
            for (f in v.fish) assertTrue("pez en y=${f.y}", f.y >= 70f - 1 && f.y <= 546f + 1)
        }
        assertTrue("queda comida: ${v.food.size}", v.food.size < AquariumView.FOOD_PER_TAP)
    }

    @Test
    fun anotherFishUpToTheMaximum() {
        val v = aquarium()
        repeat(20) { v.again() }
        assertEquals(AquariumView.MAX_FISH, v.fish.size)
        v.reset()
        assertEquals(AquariumView.START_FISH, v.fish.size)
    }
}
