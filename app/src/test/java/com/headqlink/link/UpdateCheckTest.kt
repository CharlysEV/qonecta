package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Aviso de versión nueva: etiquetas de GitHub comparadas número a número, las notas en texto llano y el APK adjunto. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class UpdateCheckTest {
    private val multi = """## Qonecta+ 0.3.4 <!-- lang:es -->

Gracias al foro.

### Nuevo
- **Guía del camino:** texto.
- **Karaoke:** texto.

### Mejorado
- **Cargadores de Alemania:** texto.

## Aviso: notas en otros idiomas · Release notes in other languages

<!-- lang:en -->
#### English

Thanks to the forum.

### New
- **Road guide:** text.

<!-- lang:de -->
#### Deutsch

Danke ans Forum.

### Neu
- **Reiseführer unterwegs:** Text.
- **Karaoke:** Text.

### Verbessert
- **Ladesäulen in Deutschland:** Text.

<!-- lang:zh -->
#### 中文

### 新功能
- **沿途导览：**文字。

### 改进
- **德国充电桩：**文字。

**SHA-256 del APK:** `abc`
"""

    @Test
    fun theNotesComeInThePhonesLanguageWithEnglishAndSpanishAsFallback() {
        val de = UpdateCheck.localized(multi, java.util.Locale.GERMANY)
        assertTrue(de, de.startsWith("## Qonecta+ 0.3.4") && de.contains("### Neu") && !de.contains("### Nuevo") && !de.contains("#### Deutsch"))
        val n = UpdateCheck.sections(de)
        assertEquals(listOf("Reiseführer unterwegs", "Karaoke"), n.added.map { it.title })
        assertEquals("Ladesäulen in Deutschland", n.improved[0].title)
        assertTrue(n.other.contains("Danke ans Forum."))
        // Sin su idioma (polaco), el inglés; los títulos chinos también se reconocen.
        assertTrue(UpdateCheck.localized(multi, java.util.Locale("pl")).contains("### New"))
        assertEquals(1, UpdateCheck.sections(UpdateCheck.localized(multi, java.util.Locale.CHINA)).improved.size)
        // Las notas de antes (sin bloques), tal cual.
        assertEquals("## x\n### Nuevo\n- a", UpdateCheck.localized("## x\n### Nuevo\n- a", java.util.Locale.GERMANY))
    }

    @Test
    fun olderVersionsThatDoNotKnowTheBlocksSeeOnlyTheSpanish() {
        // Lo que hace una versión anterior con las notas enteras: los demás idiomas van bajo «## Aviso…» y se los salta.
        val n = UpdateCheck.sections(multi)
        assertEquals(listOf("Guía del camino", "Karaoke"), n.added.map { it.title })
        assertEquals(1, n.improved.size)
        assertFalse(n.other.contains("Danke") || n.other.contains("Thanks") || n.other.contains("<!--"))
        assertTrue(n.other.contains("Gracias al foro."))
    }

    @Test
    fun releaseJsonGivesTheApkAsset() {
        val json = org.json.JSONObject(
            """{"tag_name":"v0.2.36-qdauto","html_url":"https://example.org/r","body":"## x",
               "assets":[{"name":"notas.txt","browser_download_url":"https://example.org/n"},
                         {"name":"HeadQLink-QDAuto-v0.2.36.apk","browser_download_url":"https://example.org/a.apk"}]}"""
        )
        val r = UpdateCheck.fromJson(json)
        assertEquals("0.2.36", r.version)
        assertEquals("https://example.org/a.apk", r.apkUrl)
        assertTrue(r.hasApk())
        // Sin adjuntos: sin APK (queda el enlace a GitHub).
        assertFalse(UpdateCheck.fromJson(org.json.JSONObject("""{"tag_name":"v0.2.1"}""")).hasApk())
    }

    @Test
    fun notesSplitIntoStarsImprovementsAndFixes() {
        val md = """## Qonecta+ 0.3.0

### Nuevo
- **Informe del mes:** tu mes en cifras.
  - Aviso el día 1.
  - Para compartir.
- **Otra novedad:** con un texto
  partido en dos líneas.

### Mejorado
- **T03:** los textos ya no se pisan.

### Arreglado
- **Modo extendido en el T03:** ya se abre.
- Un arreglo sin título.

## Aviso
- Licencias.

**SHA-256 del APK:** `abc`
"""
        val n = UpdateCheck.sections(md)
        assertTrue(n.structured())
        assertEquals(2, n.added.size)
        assertEquals("Informe del mes", n.added[0].title)
        assertEquals("tu mes en cifras.", n.added[0].body)
        assertEquals(listOf("Aviso el día 1.", "Para compartir."), n.added[0].sub)
        assertEquals("con un texto partido en dos líneas.", n.added[1].body)
        assertEquals(1, n.improved.size)
        assertEquals("T03", n.improved[0].title)
        assertEquals(2, n.fixed.size)
        assertEquals("", n.fixed[1].title)
        assertEquals("Un arreglo sin título.", n.fixed[1].body)
        // Sin la sección «Aviso» ni el SHA.
        assertEquals("", n.other)
        // «Cambiado» cuenta como mejora; unas notas sin apartados se enseñan como texto.
        assertEquals("Fuera el centinela", UpdateCheck.sections("### Cambiado\n- **Fuera el centinela.** Texto.").improved[0].title.trimEnd('.'))
        assertFalse(UpdateCheck.sections("Arreglos varios.\n- uno\n- dos").structured())
    }

    @Test
    fun tagsParseNumberByNumber() {
        assertEquals(listOf(0, 2, 33), UpdateCheck.parse("v0.2.33-qdauto")!!.toList())
        assertEquals(listOf(0, 2, 9), UpdateCheck.parse("0.2.9-qdauto")!!.toList())
        assertEquals(listOf(1, 0, 0), UpdateCheck.parse("v1.0")!!.toList())
        assertNull(UpdateCheck.parse("latest"))
        assertEquals("0.2.33", UpdateCheck.clean("v0.2.33-qdauto"))
    }

    @Test
    fun thirtyIsNewerThanNine() {
        assertTrue(UpdateCheck.newer("v0.2.30-qdauto", "0.2.9-qdauto"))
        assertTrue(UpdateCheck.newer("v0.3.0-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("v0.2.33-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("v0.2.3-qdauto", "0.2.33-qdauto"))
        assertFalse(UpdateCheck.newer("latest", "0.2.33-qdauto"))
    }

    @Test
    fun notesBecomePlainTextWithoutTheLicenceBlock() {
        val md = """
            ## 0.2.33: color fijo del panel lateral

            ### Color del panel lateral (Auto extendido)
            - En **«Ajustes»** del coche:
              - **«Automático»:** como hasta ahora.
            - Se aplica al momento.

            ## Aviso
            Software experimental, **sin garantía**.

            SHA-256 del APK: `abc`
        """.trimIndent()
        val plain = UpdateCheck.plainNotes(md)
        assertEquals("COLOR DEL PANEL LATERAL (AUTO EXTENDIDO)\n• En «Ajustes» del coche:\n    – «Automático»: como hasta ahora.\n• Se aplica al momento.", plain)
        assertFalse(plain.contains("Aviso"))
        assertFalse(plain.contains("SHA"))
    }
}
