package com.headqlink.link

import android.app.Application
import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/**
 * Escalones del codificador al fallar al arrancar (S23 Ultra con Máximo, 3-11: c2.qti.avc.encoder da err 0x80000000 a
 * los ~45 ms y ni un frame): un error sin frames baja al escalón siguiente que cambie algo, se recuerda por móvil y
 * códec, uno tardío no baja y elegir un perfil a mano lo vuelve a probar desde arriba.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class EncoderFallbackTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val s23 = "SM-S918B · c2.qti.avc.encoder"
    private val maximo = "aa/maximo"

    /** Máximo por cable en el C10: baja latencia y relojes, High, 1920 de ancho, 16 Mbit/s de techo; Coche, 8. */
    private val best = EncoderFallback.Wanted(true, "high", 1920, 16_000_000, 8_000_000)

    @Before
    fun clean() {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun anErrorBeforeTheFirstFrameGoesToTheNextStep() {
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.afterFailure(EncoderFallback.AS_IS, best, 0, 45))
        assertEquals(EncoderFallback.MAIN, EncoderFallback.afterFailure(EncoderFallback.NO_BOOST, best, 0, 50))
        assertEquals(EncoderFallback.BASELINE, EncoderFallback.afterFailure(EncoderFallback.MAIN, best, 0, 50))
        assertEquals(EncoderFallback.CAR_SIZE, EncoderFallback.afterFailure(EncoderFallback.BASELINE, best, 0, 50))
        // Ya en el último: no quedan escalones (lo de siempre).
        assertEquals(-1, EncoderFallback.afterFailure(EncoderFallback.CAR_SIZE, best, 0, 50))
        // Unos frames en los primeros segundos también cuentan como «al arrancar».
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.afterFailure(EncoderFallback.AS_IS, best, 12, 400))
    }

    @Test
    fun aLateErrorWithFramesDoesNotStepDown() {
        assertFalse(EncoderFallback.early(900, 30_000))
        assertEquals(-1, EncoderFallback.afterFailure(EncoderFallback.AS_IS, best, 900, 30_000))
        assertEquals(-1, EncoderFallback.afterFailure(EncoderFallback.NO_BOOST, best, 1, EncoderFallback.EARLY_MS))
        // Sin ningún frame, aunque hayan pasado minutos (la puerta estaba cerrada sin sesión): al arrancar.
        assertTrue(EncoderFallback.early(0, 600_000))
    }

    @Test
    fun stepsThatChangeNothingAreSkipped() {
        // Sin baja latencia y en Main: directo a Baseline.
        val main = EncoderFallback.Wanted(false, "main", 1920, 10_000_000, 8_000_000)
        assertEquals(EncoderFallback.BASELINE, EncoderFallback.next(EncoderFallback.AS_IS, main))
        // Muy alto con Baseline (el de Avanzado): sin baja latencia, a tamaño de Coche.
        val veryHigh = EncoderFallback.Wanted(true, "baseline", 1920, 16_000_000, 8_000_000)
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.next(EncoderFallback.AS_IS, veryHigh))
        assertEquals(EncoderFallback.CAR_SIZE, EncoderFallback.next(EncoderFallback.NO_BOOST, veryHigh))
        // Coche a 720p: nada que bajar, se queda como siempre.
        val car = EncoderFallback.Wanted(false, "baseline", 1280, 8_000_000, 8_000_000)
        assertEquals(-1, EncoderFallback.next(EncoderFallback.AS_IS, car))
    }

    @Test
    fun eachStepAddsToTheOnesBefore() {
        fun params() = VideoEncoder.Params().apply {
            width = 1920
            height = 882
            fps = 60
            bitrate = 12_000_000
            profile = "high"
            lowLatency = true
            maxClocks = true
        }
        val p0 = params().also { EncoderFallback.apply(EncoderFallback.AS_IS, it) }
        assertTrue(p0.lowLatency && p0.maxClocks)
        assertEquals("high", p0.profile)
        val p1 = params().also { EncoderFallback.apply(EncoderFallback.NO_BOOST, it) }
        assertFalse(p1.lowLatency || p1.maxClocks)
        assertEquals("high", p1.profile)
        assertEquals("main", params().also { EncoderFallback.apply(EncoderFallback.MAIN, it) }.profile)
        assertEquals("baseline", params().also { EncoderFallback.apply(EncoderFallback.BASELINE, it) }.profile)
        assertEquals("baseline", params().also { EncoderFallback.apply(EncoderFallback.CAR_SIZE, it) }.profile)
        // Tamaño y bitrate de Coche solo en el último: 1920x882 → 1280x588, 12 → 8 Mbit/s.
        assertArrayEquals(intArrayOf(1920, 882), EncoderFallback.size(EncoderFallback.BASELINE, 1920, 882))
        assertArrayEquals(intArrayOf(1280, 588), EncoderFallback.size(EncoderFallback.CAR_SIZE, 1920, 882))
        assertArrayEquals(intArrayOf(1280, 588), EncoderFallback.size(EncoderFallback.CAR_SIZE, 1280, 588))
        assertEquals(12_000_000, EncoderFallback.bitrate(EncoderFallback.BASELINE, 12_000_000, 8_000_000))
        assertEquals(8_000_000, EncoderFallback.bitrate(EncoderFallback.CAR_SIZE, 12_000_000, 8_000_000))
        assertEquals(5_000_000, EncoderFallback.bitrate(EncoderFallback.CAR_SIZE, 5_000_000, 8_000_000))
    }

    @Test
    fun theStepIsRememberedPerPhoneAndEncoder() {
        var mem = EncoderFallback.remember(emptyList(), s23, maximo, EncoderFallback.NO_BOOST)
        mem = EncoderFallback.remember(mem, "SM-F731B · c2.qti.avc.encoder", maximo, EncoderFallback.BASELINE)
        val back = EncoderFallback.decode(EncoderFallback.encode(mem))
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.startStep(back, s23, maximo))
        assertEquals(EncoderFallback.BASELINE, EncoderFallback.startStep(back, "SM-F731B · c2.qti.avc.encoder", maximo))
        // Otro móvil (o el mismo con otro códec): desde arriba.
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(back, "SM-S938B · c2.qti.avc.encoder", maximo))
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(back, "SM-S918B · c2.exynos.h264.encoder", maximo))
        // Bajar otra vez sustituye el del mismo móvil, sin duplicarlo.
        mem = EncoderFallback.remember(back, s23, maximo, EncoderFallback.MAIN)
        assertEquals(2, mem.size)
        assertEquals(EncoderFallback.MAIN, EncoderFallback.startStep(mem, s23, maximo))
        // Basura o vacío: desde arriba, sin romper.
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(EncoderFallback.decode("x\ty\n\tz\tq"), s23, maximo))
        assertTrue(EncoderFallback.decode("").isEmpty())
        // Como mucho KEEP móviles.
        var many = emptyList<EncoderFallback.Entry>()
        for (i in 1..EncoderFallback.KEEP + 2) many = EncoderFallback.remember(many, "M$i · c", maximo, 1)
        assertEquals(EncoderFallback.KEEP, many.size)
        assertEquals(1, EncoderFallback.startStep(many, "M${EncoderFallback.KEEP + 2} · c", maximo))
    }

    @Test
    fun anotherProfileStartsFromTheTop() {
        val mem = EncoderFallback.remember(emptyList(), s23, maximo, EncoderFallback.BASELINE)
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(mem, s23, "aa/muy_alto"))
        // Si con el otro también falla, se aprende para él (y sustituye al de Máximo).
        val again = EncoderFallback.remember(mem, s23, "aa/muy_alto", EncoderFallback.NO_BOOST)
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.startStep(again, s23, "aa/muy_alto"))
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(again, s23, maximo))
    }

    @Test
    fun choosingAProfileByHandRetriesFromTheTopOnce() {
        val cfg = com.headqlink.link.Config(ctx)
        cfg.setEncFallback(EncoderFallback.encode(EncoderFallback.remember(emptyList(), s23, maximo, EncoderFallback.MAIN)))
        assertEquals(EncoderFallback.MAIN, EncoderFallback.startStep(EncoderFallback.decode(cfg.encFallback()), s23, maximo))
        cfg.setVideoProfile(VideoProfile.BEST)
        assertEquals("", cfg.encFallback())
        assertEquals(EncoderFallback.AS_IS, EncoderFallback.startStep(EncoderFallback.decode(cfg.encFallback()), s23, maximo))
        // Falla otra vez: se vuelve a recordar desde el primero.
        val next = EncoderFallback.afterFailure(EncoderFallback.AS_IS, best, 0, 45)
        cfg.setEncFallback(EncoderFallback.encode(EncoderFallback.remember(EncoderFallback.decode(cfg.encFallback()), s23, maximo, next)))
        assertEquals(EncoderFallback.NO_BOOST, EncoderFallback.startStep(EncoderFallback.decode(cfg.encFallback()), s23, maximo))
    }

    @Test
    fun theLogLineSaysWhatFailedAndWhatComesNext() {
        val line = EncoderFallback.failLine(s23, 0, "1920x882@60 12000kbps high VBR · baja latencia",
            "error del códec: android.media.MediaCodec\$CodecException: Error 0x80000000", 0, 45, 1)
        assertTrue(line, line.startsWith("codificador: falló con escalón 0 «tal cual» (1920x882@60"))
        assertTrue(line, line.contains("SM-S918B · c2.qti.avc.encoder"))
        assertTrue(line, line.contains("0x80000000"))
        assertTrue(line, line.contains("sigo con escalón 1 «sin baja latencia del fabricante ni relojes al máximo»"))
        val late = EncoderFallback.failLine(s23, 1, "p", "e", 900, 30_000, -1)
        assertTrue(late, late.endsWith("ya había dado frames: no bajo de escalón"))
        val last = EncoderFallback.failLine(s23, 4, "p", "e", 0, 40, -1)
        assertTrue(last, last.endsWith("no quedan escalones más seguros"))
    }

    @Test
    fun aSilentEncoderAtStartIsCaught() {
        val s = 1_000_000_000L
        // Sin frames 3 s después del primero que se le dio: mudo.
        assertTrue(VideoPipeline.silentAtStart(10 * s, 0, 7 * s))
        assertFalse(VideoPipeline.silentAtStart(10 * s - 1, 0, 7 * s))
        // Con frames, o sin habérsele dado ninguno (puerta cerrada sin sesión): nada.
        assertFalse(VideoPipeline.silentAtStart(100 * s, 1, 7 * s))
        assertFalse(VideoPipeline.silentAtStart(100 * s, 0, 0))
    }

    @Test
    fun theStepIsPartOfThePlan() {
        val car = VideoPlan.Car(1920, 882, 1920, 882, 60, 5_000_000, 4, 1, true)
        val asIs = VideoPlan("aa", "maximo", true, false, false, 1920, 882, 60, car, "x", 0, s23, maximo)
        val safer = VideoPlan("aa", "maximo", true, false, false, 1920, 882, 60, car, "x", 1, s23, maximo)
        assertFalse(asIs == safer)
        assertEquals("escalón del codificador 0→1", safer.differenceFrom(asIs))
        assertTrue(safer.toString(), safer.toString().contains("codificador en escalón 1"))
        // Los planes de siempre (sin escalón) siguen siendo iguales entre sí.
        assertEquals(VideoPlan("aa", "coche", true, false, false, 1280, 588, 60, car, "x"),
            VideoPlan("aa", "coche", true, false, false, 1280, 588, 60, car, "x"))
    }
}
