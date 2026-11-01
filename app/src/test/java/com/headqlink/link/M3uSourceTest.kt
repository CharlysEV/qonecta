package com.headqlink.link

import com.andrerinas.openheadunit.R
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Listas de TV y radio (foro #299 y #290): una página web da un mensaje claro y no canales basura, las páginas conocidas
 * se cambian por su lista, gzip/BOM/Latin-1, usuario y contraseña (Xtream o HTTP Basic) cifrados y nunca en el log ni en
 * la pantalla, y los textos nuevos en todos los idiomas. Todo con datos inventados y, lo de red, en el bucle local.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class M3uSourceTest {
    private val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"Noticias\",Canal 1\nhttps://a.example/1.m3u8\n#EXTINF:-1,Canal 2\nhttps://b.example/2.m3u8\n"
    private val html = "\n  <!DOCTYPE html>\n<html lang=\"en\"><head><title>GitHub - iptv-org/iptv</title></head>" +
        "<body><pre>#EXTM3U</pre></body></html>"

    /** El tipo de M3uSource.ListError que lanza block. */
    private fun kind(block: () -> Unit): Int {
        try {
            block()
        } catch (e: M3uSource.ListError) {
            return e.kind
        }
        fail("debía fallar")
        return -1
    }

    @Test
    fun webPagesAreNotLists() {
        assertEquals(M3uSource.HTML, M3uSource.check(html, null))
        assertEquals(M3uSource.HTML, M3uSource.check("\uFEFF<html><body>x</body></html>", "text/html"))
        assertEquals(M3uSource.HTML, M3uSource.check("Hola\nmundo", "text/html; charset=utf-8"))
        assertEquals(M3uSource.NOT_LIST, M3uSource.check("Hola\nmundo", "text/plain"))
        // Que se hable de #EXTM3U no lo hace una lista (un README).
        assertEquals(M3uSource.NOT_LIST, M3uSource.check("Empieza la lista con #EXTM3U y sigue con #EXTINF", null))
        assertEquals(M3uSource.NOT_LIST, M3uSource.check("http://a.example/1.mp3\nesto no es una URL", null))
        // Listas de verdad, también servidas como text/html (servidores mal configurados).
        assertEquals(M3uSource.OK, M3uSource.check(m3u, "text/html"))
        assertEquals(M3uSource.OK, M3uSource.check("#extm3u\n#extinf:-1,a\nhttp://a.example/1", null))
        assertEquals(M3uSource.OK, M3uSource.check("http://radio.example/1.mp3\r\n# comentario\r\nhttps://radio.example/2.aac\r\n", null))
        assertEquals(M3uSource.OK, M3uSource.check("{\"countries\":[]}", "application/json"))
        assertEquals(M3uSource.OK, M3uSource.check("  ", null))
    }

    @Test
    fun aSavedWebPageGivesAClearMessageNotGarbage() {
        Str.init(RuntimeEnvironment.getApplication())
        assertEquals(M3uSource.ListError.WEB_PAGE, kind { IptvScreen.read(html.byteInputStream()) })
        assertEquals(M3uSource.ListError.NOT_LIST, kind { IptvScreen.read("uno\ndos\ntres".byteInputStream()) })
        // Con usuario, una página es que el servidor no lo ha aceptado (así contestan los Xtream).
        assertEquals(M3uSource.ListError.LOGIN_REJECTED,
            kind { M3uSource.text(html.toByteArray(), "text/html", M3uSource.Login("usr", "clave")) })
        val e = M3uSource.ListError.of(M3uSource.ListError.WEB_PAGE)
        assertTrue(e.text(R.string.hql_tv_read_failed).contains(".m3u"))
        assertEquals("Could not read the list: HTTP 404", M3uSource.ListError.http(404).text(R.string.hql_tv_read_failed))
    }

    @Test
    fun bomGzipAndLatin1() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "#EXTM3U\n#EXTINF:-1,Canal Ñ\nhttps://a.example/1.m3u8\n".toByteArray()
        // Con BOM, la cabecera no sale como un canal más.
        assertEquals(listOf("Canal Ñ"), IptvScreen.read(bom.inputStream()).map { it.name })

        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(m3u.toByteArray()) } }.toByteArray()
        assertEquals(listOf("Canal 1", "Canal 2"), IptvScreen.read(gz.inputStream()).map { it.name })

        val latin = "#EXTM3U\n#EXTINF:-1,Televisión Ñ\nhttps://a.example/1.m3u8\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Televisión Ñ", IptvScreen.read(latin.inputStream())[0].name)

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + m3u.toByteArray(Charsets.UTF_16LE)
        assertEquals(2, IptvScreen.read(utf16.inputStream()).size)

        // Un gzip cortado (el principio de una lista, al comprobarla): lo que haya, sin error.
        val big = ByteArrayOutputStream().also { o ->
            GZIPOutputStream(o).use { g ->
                g.write("#EXTM3U\n".toByteArray())
                repeat(3000) { i -> g.write("#EXTINF:-1,Canal $i\nhttps://a.example/$i.m3u8\n".toByteArray()) }
            }
        }.toByteArray()
        assertTrue(M3uSource.decode(big.copyOf(big.size / 2)).startsWith("#EXTM3U\n#EXTINF:-1,Canal 0"))
        // Como mucho max bytes, cortado por la última línea entera.
        val cut = M3uSource.readUpTo(m3u.byteInputStream(), 40)
        assertEquals("#EXTM3U\n", String(cut))
    }

    @Test
    fun knownPagesBecomeTheirLists() {
        val tv = "https://iptv-org.github.io/iptv/countries/de.m3u"
        assertEquals(tv, M3uSource.convert("https://github.com/iptv-org/iptv", "de"))
        assertEquals(tv, M3uSource.convert("https://github.com/iptv-org/iptv/", "DE"))
        assertEquals(tv, M3uSource.convert("https://www.github.com/iptv-org/iptv/tree/master/streams", "de"))
        assertEquals("https://iptv-org.github.io/iptv/countries/es.m3u", M3uSource.convert("https://iptv-org.github.io/iptv/", "es"))
        assertEquals("https://iptv-org.github.io/iptv/countries/uk.m3u", M3uSource.convert("https://github.com/iptv-org/iptv", "gb"))
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", M3uSource.convert("https://iptv-org.github.io/iptv", ""))
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", M3uSource.convert("https://github.com/iptv-org/iptv", "419"))
        // Las listas de verdad, tal cual (la de jojo1968 en el foro, #305).
        assertEquals(tv, M3uSource.convert(tv, "es"))
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", M3uSource.convert("https://iptv-org.github.io/iptv/index.m3u", "de"))
        // iprd (radio): lo que publica su web, site_data/by_country/<cc>.m3u y all_stations.m3u.
        assertEquals("https://iprd-org.github.io/iprd/site_data/by_country/de.m3u", M3uSource.convert("https://iprd-org.github.io/iprd", "de"))
        assertEquals("https://iprd-org.github.io/iprd/site_data/by_country/fr.m3u", M3uSource.convert("https://github.com/iprd-org/iprd", "fr"))
        assertEquals("https://iprd-org.github.io/iprd/site_data/all_stations.m3u",
            M3uSource.convert("https://iprd-org.github.io/iprd/data_files/", ""))
        // Un archivo en GitHub («blob»): en crudo.
        assertEquals("https://raw.githubusercontent.com/alguien/listas/main/tv/es.m3u",
            M3uSource.convert("https://github.com/alguien/listas/blob/main/tv/es.m3u", "de"))
        assertEquals("https://raw.githubusercontent.com/iptv-org/iptv/master/streams/de.m3u",
            M3uSource.convert("https://github.com/iptv-org/iptv/blob/master/streams/de.m3u?plain=1", "es"))
        // Lo demás, sin tocar.
        assertEquals("https://ejemplo.org/lista.m3u", M3uSource.convert("https://ejemplo.org/lista.m3u", "de"))
        assertEquals("https://github.com/alguien/listas", M3uSource.convert("https://github.com/alguien/listas", "de"))
        assertEquals("content://doc/1", M3uSource.convert("content://doc/1", "de"))
        // Un país sin lista (404): la de todos.
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", M3uSource.fallback("https://iptv-org.github.io/iptv/countries/xx.m3u"))
        assertEquals("https://iprd-org.github.io/iprd/site_data/all_stations.m3u",
            M3uSource.fallback("https://iprd-org.github.io/iprd/site_data/by_country/xx.m3u"))
        assertNull(M3uSource.fallback("https://ejemplo.org/lista.m3u"))
        // El país: el de la SIM y, si no hay, el de la región.
        assertEquals("es", M3uSource.pickCountry("ES", "DE"))
        assertEquals("de", M3uSource.pickCountry("", "DE"))
        assertEquals("", M3uSource.pickCountry(null, "419"))
    }

    @Test
    fun xtreamServersGetTheirListWithTheLogin() {
        val login = M3uSource.Login("usr", "p@ss word")
        val list = "http://tv.example.com:8080/get.php?username=usr&password=p%40ss+word&type=m3u_plus&output=ts"
        assertEquals(list, M3uSource.xtreamUrl("http://tv.example.com:8080", login))
        assertEquals(list, M3uSource.xtreamUrl("http://tv.example.com:8080/", login))
        assertEquals(list, M3uSource.xtreamUrl("http://tv.example.com:8080/player_api.php", login))
        assertEquals("https://tv.example.com/get.php?username=usr&password=p%40ss+word&type=m3u&output=m3u8",
            M3uSource.xtreamUrl("https://tv.example.com/get.php?type=m3u&output=m3u8", login))
        assertNull(M3uSource.xtreamUrl("https://nas.example.com/listas/tv.m3u", login))
        // Si no es Xtream, HTTP Basic (el ejemplo del RFC 7617).
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", M3uSource.basicAuth(M3uSource.Login("Aladdin", "open sesame")))
        val basic = M3uSource.request("https://nas.example.com/listas/tv.m3u", login)
        assertEquals("https://nas.example.com/listas/tv.m3u", basic[0])
        assertTrue(basic[1].startsWith("Basic "))
        assertArrayEquals(arrayOf(list, null), M3uSource.request("http://tv.example.com:8080", login))
        // Sin usuario, como siempre.
        assertArrayEquals(arrayOf("http://tv.example.com:8080", null), M3uSource.request("http://tv.example.com:8080", M3uSource.Login.NONE))
        // Un Xtream que contesta con nada: no ha aceptado el usuario (o la cuenta ha caducado). Los demás, «sin canales».
        assertEquals(M3uSource.ListError.LOGIN_REJECTED, kind { M3uSource.requireNotEmpty(" \n", login, "http://tv.example.com:8080") })
        M3uSource.requireNotEmpty("", login, "https://nas.example.com/listas/tv.m3u")
        M3uSource.requireNotEmpty("", M3uSource.Login.NONE, "http://tv.example.com:8080")
        M3uSource.requireNotEmpty(m3u, login, "http://tv.example.com:8080")
    }

    @Test
    fun pastedLoginsComeOutOfTheUrl() {
        val x = M3uSource.splitLogin("http://tv.example.com:8080/get.php?username=usr&password=s%26cr+t&type=m3u_plus&output=ts")
        assertEquals("http://tv.example.com:8080/get.php?type=m3u_plus&output=ts", x.url)
        assertEquals("usr", x.login.user)
        assertEquals("s&cr t", x.login.pass)
        // Al pedirla, se vuelven a poner (get.php).
        assertEquals("http://tv.example.com:8080/get.php?username=usr&password=s%26cr+t&type=m3u_plus&output=ts",
            M3uSource.xtreamUrl(x.url, x.login))
        val b = M3uSource.splitLogin("https://usr:se+cr%40t@nas.example.com/tv.m3u")
        assertEquals("https://nas.example.com/tv.m3u", b.url)
        assertEquals("usr", b.login.user)
        assertEquals("se+cr@t", b.login.pass)
        val none = M3uSource.splitLogin("https://ejemplo.org/tv.m3u?token=1")
        assertEquals("https://ejemplo.org/tv.m3u?token=1", none.url)
        assertTrue(none.login.isEmpty)
        // Otra web con el usuario en la consulta: se queda (su servidor lo espera ahí), tapado en pantalla.
        assertTrue(M3uSource.splitLogin("https://ejemplo.org/lista.php?user=a&pass=b").login.isEmpty)
        assertEquals("https://ejemplo.org/lista.php?user=***&pass=***", M3uSource.mask("https://ejemplo.org/lista.php?user=a&pass=b"))
    }

    @Test
    fun credentialsNeverReachTheLogOrTheScreen() {
        val m = M3uSource.mask("IPTV: GET http://tv.example.com/get.php?username=usr5&password=secreto5&type=m3u_plus")
        assertEquals("IPTV: GET http://tv.example.com/get.php?username=***&password=***&type=m3u_plus", m)
        assertEquals("https://***@nas.example.com/tv.m3u", M3uSource.mask("https://usr5:secreto5@nas.example.com/tv.m3u"))
        assertEquals("http://tv.example.com:8080/live/***/***/123.ts", M3uSource.mask("http://tv.example.com:8080/live/usr5/secreto5/123.ts"))
        // Con el usuario de la lista, también la ruta de Xtream sin /live/.
        assertEquals("http://tv.example.com:8080/***/***/123",
            M3uSource.mask("http://tv.example.com:8080/usr6/clave6/123", M3uSource.Login("usr6", "clave6")))
        // En cada línea del log (Redact): los de las listas usadas, tapados dentro de las URLs; lo demás, igual.
        M3uSource.remember(M3uSource.Login("usuario7x", "clave7x"))
        assertEquals("radio: Emisora · http://radio.example.com:8000/***/***/55",
            Redact.line("radio: Emisora · http://radio.example.com:8000/usuario7x/clave7x/55"))
        assertEquals("IPTV: lista 1 de 2", Redact.line("IPTV: lista 1 de 2"))
        // El error que se ve en el coche, sin la URL con el usuario.
        val e = M3uSource.ListError.of(java.net.MalformedURLException("no protocol: tv.example.com/get.php?username=usr8&password=secreto8"),
            M3uSource.Login("usr8", "secreto8"))
        assertFalse(e.message, e.message!!.contains("secreto8") || e.message!!.contains("usr8"))
        assertFalse(e.toString().contains("secreto8"))
    }

    /** AES-GCM con una clave en memoria: hace de Keystore en el PC (como en CarCloudTest). */
    private class MemBox : CarCloudStore.Box {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return byteArrayOf(12) + iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, 12))
            return c.doFinal(sealed, 13, sealed.size - 13)
        }

        override fun destroy() {}
    }

    @Test
    fun loginsAreStoredEncrypted() {
        val f = File(Files.createTempDirectory("m3ulogins").toFile(), "logins.bin")
        val box = MemBox()
        val st = M3uSource.Logins(f, box)
        val id = M3uSource.Logins.newId()
        st.put(id, M3uSource.Login("usr_prueba", "clave_prueba_9"))
        val raw = String(f.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("usr_prueba") || raw.contains("clave_prueba_9"))
        assertEquals("clave_prueba_9", st.get(id).pass)
        assertEquals(setOf(id), st.ids())
        // Otra vez, con el archivo ya hecho: se sustituye.
        st.put(id, M3uSource.Login("usr_prueba", "clave_prueba_10"))
        assertEquals("clave_prueba_10", M3uSource.Logins(f, box).get(id).pass)
        // Con otra clave (los ajustes en otro móvil): sin usuario, sin romperse.
        assertTrue(M3uSource.Logins(f, MemBox()).get(id).isEmpty)
        st.remove(id)
        assertTrue(st.get(id).isEmpty)
        assertFalse(f.exists())
        assertTrue(st.get("").isEmpty)
    }

    @Test
    fun listsKeepTheirLoginIdAndTheConvertedUrl() {
        val l = Config.m3uListsFrom("[[\"A\",\"https://a.example/x.m3u\",\"id1\"],[\"\",\"https://github.com/iptv-org/iptv\"]]", "")
        assertEquals("id1", Config.m3uLoginId(l[0]))
        assertEquals("", Config.m3uLoginId(l[1]))
        assertEquals("github.com", l[1][0])
        // Sin usuario, como antes ([nombre, url]); con él, también su id (y nada más: ni usuario ni contraseña).
        val json = JSONArray(Config.m3uListsJson(l))
        assertEquals(3, json.getJSONArray(0).length())
        assertEquals(2, json.getJSONArray(1).length())
        assertEquals("id1", Config.m3uListsFrom(json.toString(), "")[0][2])
        // La página, por su lista: el nombre automático cambia; el puesto a mano y el usuario se quedan.
        val conv = Config.withM3uSource(l, "https://github.com/iptv-org/iptv", "https://iptv-org.github.io/iptv/countries/de.m3u")
        assertEquals("iptv-org.github.io", conv[1][0])
        assertEquals("https://iptv-org.github.io/iptv/countries/de.m3u", conv[1][1])
        assertEquals("A", conv[0][0])
        val named = Config.withM3uSource(arrayOf(arrayOf("Mi TV", "https://github.com/iptv-org/iptv", "id2")),
            "https://github.com/iptv-org/iptv", "https://iptv-org.github.io/iptv/index.m3u")
        assertEquals("Mi TV", named[0][0])
        assertEquals("id2", named[0][2])
        // El nombre automático nunca es el usuario de la URL.
        assertEquals("nas.example.com", Config.m3uName("https://usr:clave@nas.example.com/tv.m3u"))
    }

    @Test
    fun favoritesAreSavedWithoutTheLogin() {
        val login = M3uSource.Login("usr9", "clave9")
        val url = "http://tv.example.com:8080/usr9/clave9/123"
        val hidden = M3uSource.hideLogin(url, login)
        assertEquals("http://tv.example.com:8080/{u}/{p}/123", hidden)
        assertEquals(url, M3uSource.showLogin(hidden, login))
        assertEquals(url, M3uSource.hideLogin(url, M3uSource.Login.NONE))
        // Un usuario corto, o metido en la contraseña, vuelve igual.
        val odd = M3uSource.Login("p", "ap")
        val u2 = "http://tv.example.com/p/ap/1.ts"
        assertEquals(u2, M3uSource.showLogin(M3uSource.hideLogin(u2, odd), odd))
        // Los guardados antes con el usuario dentro: ya sin él, con el id de su lista.
        val favs = JSONArray().put(Favorites.item("Canal", url, "", "", listOf(url, "$url.m3u8")))
            .put(Favorites.item("Otro", "https://b.example/1.m3u8", "", "", null))
        assertEquals(1, M3uSource.hideInFavorites(favs, login, "id9"))
        assertEquals(hidden, favs.getJSONObject(0).getString("url"))
        assertEquals("id9", favs.getJSONObject(0).getString("login"))
        assertEquals("$hidden.m3u8", favs.getJSONObject(0).getJSONArray("urls").getString(1))
        assertEquals("https://b.example/1.m3u8", favs.getJSONObject(1).getString("url"))
        assertEquals(0, M3uSource.hideInFavorites(favs, login, "id9"))
    }

    /** Un servidor HTTP mínimo en el bucle local: ruta y cabecera Authorization → (código, cabeceras, cuerpo). */
    private class TinyHttp(addr: InetAddress, private val route: (String, String) -> Triple<Int, Map<String, String>, ByteArray>) :
        AutoCloseable {
        private val ss = ServerSocket(0, 8, addr)
        val port: Int get() = ss.localPort
        /** La cabecera Authorization de cada petición ("" si no la traía). */
        val auths = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (!ss.isClosed) {
                    val s = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    s.use { sock ->
                        val r = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val first = r.readLine() ?: return@use
                        var auth = ""
                        while (true) {
                            val h = r.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("Authorization:", ignoreCase = true)) auth = h.substringAfter(':').trim()
                        }
                        auths.add(auth)
                        val (code, headers, body) = route(first.split(" ")[1], auth)
                        val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        head.append("\r\n")
                        sock.getOutputStream().apply {
                            write(head.toString().toByteArray(Charsets.ISO_8859_1))
                            write(body)
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = ss.close()
    }

    @Test
    fun downloadsFollowRedirectsAndSendTheLoginOnlyToItsServer() {
        val ctx = RuntimeEnvironment.getApplication()
        val login = M3uSource.Login("usr_local", "clave_local")
        val auth = M3uSource.basicAuth(login)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(m3u.toByteArray()) } }.toByteArray()
        val other = java.util.concurrent.atomic.AtomicInteger()
        TinyHttp(InetAddress.getByName("127.0.0.1")) { path, a ->
            when (path) {
                "/salto" -> Triple(302, mapOf("Location" to "/lista"), ByteArray(0))
                "/lista" -> if (a == auth) Triple(200, mapOf("Content-Type" to "audio/x-mpegurl"), m3u.toByteArray())
                else Triple(401, emptyMap(), ByteArray(0))
                "/web" -> Triple(200, mapOf("Content-Type" to "text/html; charset=utf-8"), html.toByteArray())
                "/gz" -> Triple(200, mapOf("Content-Type" to "application/octet-stream"), gz)
                "/fuera" -> Triple(302, mapOf("Location" to "http://127.0.0.2:${other.get()}/otra"), ByteArray(0))
                else -> Triple(404, emptyMap(), ByteArray(0))
            }
        }.use { server ->
            val base = "http://127.0.0.1:${server.port}"
            // Redirección (a mano) en el mismo servidor: con su usuario (HTTP Basic).
            val f = M3uSource.download(ctx, "$base/salto", login, M3uSource.MAX_BYTES)
            assertEquals("$base/salto", f.url)
            assertEquals(2, IptvScreen.readText(M3uSource.text(f.body, f.contentType, login), null).size)
            assertEquals(M3uSource.ListError.NEEDS_LOGIN, kind { M3uSource.download(ctx, "$base/lista", M3uSource.Login.NONE, M3uSource.MAX_BYTES) })
            assertEquals(M3uSource.ListError.LOGIN_REJECTED,
                kind { M3uSource.download(ctx, "$base/lista", M3uSource.Login("usr_local", "mala"), M3uSource.MAX_BYTES) })
            assertEquals(M3uSource.ListError.HTTP, kind { M3uSource.download(ctx, "$base/nada", M3uSource.Login.NONE, M3uSource.MAX_BYTES) })
            // Una página web servida como text/html, y una lista en gzip sin avisar.
            val web = M3uSource.download(ctx, "$base/web", M3uSource.Login.NONE, M3uSource.MAX_BYTES)
            assertEquals(M3uSource.ListError.WEB_PAGE, kind { M3uSource.text(web.body, web.contentType, M3uSource.Login.NONE) })
            val z = M3uSource.download(ctx, "$base/gz", M3uSource.Login.NONE, M3uSource.MAX_BYTES)
            assertEquals(2, IptvScreen.readText(M3uSource.text(z.body, z.contentType, M3uSource.Login.NONE), null).size)

            // Si la redirección lleva a otro servidor, el usuario no va con ella.
            val second = try {
                TinyHttp(InetAddress.getByName("127.0.0.2")) { _, _ -> Triple(200, mapOf("Content-Type" to "audio/x-mpegurl"), m3u.toByteArray()) }
            } catch (e: Exception) {
                null
            }
            Assume.assumeTrue("sin 127.0.0.2 en este sistema", second != null)
            second!!.use { s2 ->
                other.set(s2.port)
                M3uSource.download(ctx, "$base/fuera", login, M3uSource.MAX_BYTES)
                assertEquals(listOf(""), s2.auths.toList())
            }
        }
    }

    @Test
    fun newTextsInEveryLanguage() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val langs = listOf("values", "values-es", "values-pt-rBR", "values-pt-rPT", "values-it", "values-fr", "values-de", "values-nl",
            "values-zh-rCN")
        val spec = Regex("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
        fun strings(dir: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/hql_strings_iptv.xml"))
            val n = doc.documentElement.getElementsByTagName("string")
            return (0 until n.length).map { n.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
        }
        val en = strings("values")
        assertEquals(11, en.size)
        for (d in langs) {
            val m = strings(d)
            assertEquals("$d: claves", en.keys.sorted(), m.keys.sorted())
            for ((k, v) in en) {
                assertEquals("$d/$k", spec.findAll(v).map { it.value }.toList(), spec.findAll(m.getValue(k)).map { it.value }.toList())
                assertTrue("$d/$k vacío", m.getValue(k).isNotBlank())
            }
        }
        Str.init(RuntimeEnvironment.getApplication())
        assertEquals("List converted: https://a.example/x.m3u", Str.get(R.string.hql_list_converted, "https://a.example/x.m3u"))
    }
}
