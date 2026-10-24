package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** Bloqueo de anuncios por dominio: la lista corta, la grande (hashes ordenados), los subdominios y lo que nunca se bloquea. */
class AdBlockTest {
    private fun list(text: String) = AdBlock.parse(BufferedReader(StringReader(text)))

    @Test
    fun theBigListIsParsedSortedAndWithoutRepeats() {
        val l = list("""
            # Title: HaGeZi's Pro mini
            ads.example.com
            *.tracker.net
            ads.example.com   # repetido
            Metrics.Example.ORG

            nodot
            con espacio.com
        """.trimIndent())
        assertEquals(3, l.size)
        assertTrue(l.toList() == l.sorted())
        assertTrue(java.util.Arrays.binarySearch(l, AdBlock.hash("metrics.example.org")) >= 0)
    }

    @Test
    fun aDomainBlocksItsSubdomainsButNotItsParent() {
        val l = list("ads.example.com\ntracker.net")
        assertTrue(AdBlock.isAd("ads.example.com", l))
        assertTrue(AdBlock.isAd("eu.ads.example.com", l))
        assertTrue(AdBlock.isAd("x.y.tracker.net", l))
        assertTrue(AdBlock.isAd("TRACKER.NET.", l))
        assertFalse(AdBlock.isAd("example.com", l))
        assertFalse(AdBlock.isAd("www.example.com", l))
        assertFalse(AdBlock.isAd("nottracker.net", l))
    }

    @Test
    fun youTubeVideosAndGoogleAreNeverBlockedEvenIfAListSaysSo() {
        val l = list("youtube.com\ngooglevideo.com\ngoogle.com\ntwitch.tv")
        for (h in listOf("www.youtube.com", "m.youtube.com", "rr3---sn-abc.googlevideo.com", "www.google.com", "www.twitch.tv")) {
            assertFalse(h, AdBlock.isAd(h, l))
        }
        // Lo de publicidad de Google sí, aunque sea de Google.
        assertTrue(AdBlock.isAd("googleads.g.doubleclick.net", l))
        assertTrue(AdBlock.isAd("pagead2.googlesyndication.com", null))
    }

    @Test
    fun withoutTheBigListTheShortOneStillWorks() {
        assertTrue(AdBlock.isAd("static.criteo.net", null))
        assertFalse(AdBlock.isAd("www.rtve.es", null))
        assertFalse(AdBlock.isAd(null, null))
        assertFalse(AdBlock.isAd("", LongArray(0)))
    }

    @Test
    fun theHashIsStable() {
        // FNV-1a de 64 bits: si cambiara, la lista guardada en los móviles dejaría de valer.
        assertEquals(-0x340d631b7bdddcdbL, AdBlock.hash(""))
        assertEquals(AdBlock.hash("ads.example.com"), AdBlock.hash("ads.example.com"))
        assertTrue(AdBlock.hash("a.com") != AdBlock.hash("b.com"))
    }

    /** Con -Ppreview, el guion tal cual a app/build/preview/adblock.js (para pasarlo por un analizador de JavaScript). */
    @Test
    fun writeScript() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = java.io.File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        java.io.File(dir, "adblock.js").writeText(AdBlockJs.SCRIPT)
    }

    @Test
    fun theYouTubeScriptCarriesItsThreeParts() {
        val s = AdBlockJs.SCRIPT
        assertTrue(s.contains("adPlacements") && s.contains("playerAds"))
        assertTrue(s.contains("ytInitialPlayerResponse"))
        assertTrue(s.contains(".ytp-ad-skip-button"))
        assertTrue(s.contains("ytm-promoted-sparkles-web-renderer"))
        assertTrue(s.contains("ins.adsbygoogle"))
    }
}
