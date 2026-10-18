package com.headqlink.link

import android.app.Application
import com.andrerinas.openheadunit.R
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Avisos de radar en recorridos reales, simulados segundo a segundo en el PC (18-10). Con HQL_RADAR_DIR = una carpeta
 * con route_N.json (OSRM: geometría, nodos de OpenStreetMap, velocidad por tramo y tipo de vía) y cams_N.json (la
 * respuesta de OpenStreetMap a la consulta de radares de la app). Sin ella, no hace nada.
 *
 * Lo que debería avisar: los radares que están en un nodo de la ruta y valen para nuestro sentido (o no se sabe para
 * cuál). Lo que avisa: el código de la app (RoadInfo.parseCameras, aheadOnRoad y RadarVoice) con la posición, el rumbo y
 * la velocidad de cada segundo, con algo de ruido del GPS. El informe va a OUT.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadarSimTest {
    private class Route(val name: String, val lat: DoubleArray, val lon: DoubleArray, val nodes: LongArray, val speed: DoubleArray,
                        val motorway: BooleanArray)

    private fun load(f: File): Route {
        val j = JSONObject(f.readText())
        val r = j.getJSONObject("route")
        val c = r.getJSONObject("geometry").getJSONArray("coordinates")
        val n = c.length()
        val lat = DoubleArray(n) { c.getJSONArray(it).getDouble(1) }
        val lon = DoubleArray(n) { c.getJSONArray(it).getDouble(0) }
        val leg = r.getJSONArray("legs").getJSONObject(0)
        val ann = leg.getJSONObject("annotation")
        val nn = ann.getJSONArray("nodes")
        val nodes = LongArray(nn.length()) { nn.getLong(it) }
        val sp = ann.getJSONArray("speed")
        val speed = DoubleArray(sp.length()) { sp.getDouble(it) }
        // Tipo de vía por tramo: los pasos de OSRM, en orden, con su geometría; «motorway» en sus cruces = calzada de un sentido.
        val motorway = BooleanArray(n)
        val steps = leg.getJSONArray("steps")
        var at = 0
        for (s in 0 until steps.length()) {
            val st = steps.getJSONObject(s)
            val cnt = st.getJSONObject("geometry").getJSONArray("coordinates").length()
            var mw = false
            val xs = st.getJSONArray("intersections")
            for (i in 0 until xs.length()) {
                val cl = xs.getJSONObject(i).optJSONArray("classes") ?: continue
                for (k in 0 until cl.length()) if (cl.getString(k) == "motorway") mw = true
            }
            for (i in at until minOf(n, at + cnt)) motorway[i] = mw
            at += maxOf(0, cnt - 1)
        }
        return Route(j.getString("name"), lat, lon, nodes, speed, motorway)
    }

    private class Place(val seg: Int, val dist: Double, val side: Double)

    /** Dónde queda un punto respecto al trazado: el tramo más cercano, la distancia a él (m) y el lado (+ derecha). */
    private fun place(r: Route, la: Double, lo: Double): Place {
        var best = Place(0, Double.MAX_VALUE, 0.0)
        val kx = 111_320.0 * cos(Math.toRadians(la))
        val ky = 111_320.0
        for (i in 0 until r.lat.size - 1) {
            val ax = (r.lon[i] - lo) * kx
            val ay = (r.lat[i] - la) * ky
            val bx = (r.lon[i + 1] - lo) * kx
            val by = (r.lat[i + 1] - la) * ky
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val tt = if (l2 > 0) maxOf(0.0, minOf(1.0, -(ax * dx + ay * dy) / l2)) else 0.0
            val px = ax + tt * dx
            val py = ay + tt * dy
            val d = Math.sqrt(px * px + py * py)
            if (d < best.dist) {
                // Lado: el punto (en el origen) respecto al sentido a→b; cruz > 0 = a la izquierda.
                val cross = dx * (-ay) - dy * (-ax)
                best = Place(i, d, if (cross > 0) -d else d)
            }
        }
        return best
    }

    private fun noise(t: Int, amp: Double) = amp * sin(t * 1.7) * cos(t * 0.37)

    private class Alert(val t: Int, val cam: Long, val m: Double, val over: Boolean, val kmh: Double)

    /** Recorre la ruta a su velocidad × factor, un punto por segundo; devuelve los avisos. */
    private fun drive(r: Route, cams: List<DoubleArray>, factor: Double, overBy: Double = 0.0, withRoute: Boolean = false,
                      roads: RoadAhead? = null): List<Alert> {
        val voice = RadarVoice()
        // Con ruta: el trazado con detalle, como lo guarda el planificador (un punto cada 25 m como poco).
        val trace = if (!withRoute) null else {
            val la = mutableListOf(r.lat[0])
            val lo = mutableListOf(r.lon[0])
            val km = mutableListOf(0.0)
            var acc = 0.0
            var since = 0.0
            for (i in 1 until r.lat.size) {
                val d = RoadInfo.dist(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i])
                acc += d
                since += d
                if (since >= RoutePlanner.TRACE_STEP_M || i == r.lat.size - 1) {
                    la.add(r.lat[i]); lo.add(r.lon[i]); km.add(acc / 1000); since = 0.0
                }
            }
            RoadInfo.Trace(la.toDoubleArray(), lo.toDoubleArray(), km.toDoubleArray())
        }
        val out = mutableListOf<Alert>()
        var seg = 0
        var into = 0.0
        var t = 0
        while (seg < r.lat.size - 1) {
            val segLen = RoadInfo.dist(r.lat[seg], r.lon[seg], r.lat[seg + 1], r.lon[seg + 1])
            val v = maxOf(8.0, r.speed.getOrElse(seg) { 15.0 }) * factor
            val brg = RoadInfo.bearing(r.lat[seg], r.lon[seg], r.lat[seg + 1], r.lon[seg + 1])
            val f = if (segLen > 0) into / segLen else 0.0
            var lat = r.lat[seg] + (r.lat[seg + 1] - r.lat[seg]) * f
            var lon = r.lon[seg] + (r.lon[seg + 1] - r.lon[seg]) * f
            // Ruido del GPS: ±8 m de lado y ±3° de rumbo.
            val side = noise(t, 8.0)
            val rr = Math.toRadians(brg + 90)
            lat += side * cos(rr) / 111_320.0
            lon += side * sin(rr) / (111_320.0 * cos(Math.toRadians(lat)))
            val heading = (brg + noise(t + 3, 3.0) + 360) % 360
            var kmh = v * 3.6
            // Sin ruta y con las carreteras de alrededor: por la carretera por la que se va (RoadInfo.traceOrRoad).
            val road = if (trace == null && roads != null) RoadInfo.roadTrace(roads, lat, lon, heading) else null
            val got = RoadInfo.nearestAhead(cams, lat, lon, heading, r.motorway[seg], trace ?: road)
            // Depuración: con RADAR_DEBUG = id de un radar, por dónde va el trazado de la carretera cerca de él.
            val dbg = System.getenv("RADAR_DEBUG")?.toLongOrNull()
            if (dbg != null && roads != null) {
                val c = cams.firstOrNull { it[4].toLong() == dbg }
                if (c != null) {
                    val d = RoadInfo.dist(lat, lon, c[0], c[1])
                    if (d in 30.0..900.0 && t % 3 == 0) {
                        val line = if (road == null) "sin trazado (por el rumbo)" else {
                            var dc = Double.MAX_VALUE
                            for (i in 0 until road.lat.size - 1) dc = minOf(dc, RoadInfo.toSegment(road, i, c[0], c[1])[0])
                            var off = 0.0
                            for (i in road.lat.indices) off = maxOf(off, place(r, road.lat[i], road.lon[i]).dist)
                            String.format("trazado %.2f km, el radar a %.0f m de él, el trazado se aparta hasta %.0f m de la ruta", road.km.last(), dc, off)
                        }
                        System.getenv("OUT")?.let { File(it + ".dbg").appendText(String.format("t=%d a %.0f m del radar: %s\n", t, d, line)) }
                    }
                }
            }
            val best = got?.get(0) ?: -1.0
            val bestCam = got?.let { cams[it[1].toInt()] }
            if (overBy > 0 && bestCam != null && bestCam[2] > 0) kmh = bestCam[2] + overBy
            val al = voice.onTick(best, bestCam?.get(2)?.toInt() ?: -1, bestCam?.get(0) ?: 0.0, bestCam?.get(1) ?: 0.0, kmh)
            if (al != null && bestCam != null) out.add(Alert(t, bestCam[4].toLong(), best, al.text == R.string.hql_voice_over_limit, kmh))
            // Siguiente segundo.
            var left = v
            while (left > 0 && seg < r.lat.size - 1) {
                val len = RoadInfo.dist(r.lat[seg], r.lon[seg], r.lat[seg + 1], r.lon[seg + 1])
                if (into + left < len) {
                    into += left
                    left = 0.0
                } else {
                    left -= len - into
                    into = 0.0
                    seg++
                }
            }
            t++
        }
        return out
    }

    @Test
    fun realRoutes() {
        val dir = System.getenv("HQL_RADAR_DIR")
        assumeTrue(dir != null && File(dir).isDirectory)
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        val rep = StringBuilder()
        var oursAll = 0
        var saidAll = 0
        var wrongAll = 0
        for (k in 0..20) {
            val rf = File(dir, "route_$k.json")
            val cf = File(dir, "cams_$k.json")
            if (!rf.exists() || !cf.exists()) continue
            val r = load(rf)
            val cams = RoadInfo.parseCameras(JSONObject(cf.readText()).getJSONArray("elements"), IntArray(2))
            val index = HashMap<Long, Int>()
            r.nodes.forEachIndexed { i, id -> index.putIfAbsent(id, i) }
            // Lo que debería avisar: el radar está en la ruta (en uno de sus nodos o a menos de 20 m del trazado; en autovía,
            // no a la izquierda: eso es la calzada de enfrente) y vale para nuestro sentido (o no se sabe para cuál).
            val ours = mutableListOf<DoubleArray>()
            val opposite = mutableListOf<DoubleArray>()
            val near = mutableListOf<DoubleArray>()
            val where = HashMap<Long, Int>()
            for (c in cams) {
                val g = place(r, c[0], c[1])
                val i = index[c[4].toLong()] ?: g.seg
                val j = minOf(i, r.lat.size - 2)
                val brg = RoadInfo.bearing(r.lat[j], r.lon[j], r.lat[j + 1], r.lon[j + 1])
                val dirOk = c[3].isNaN() || abs(((c[3] - brg) + 540) % 360 - 180) <= 60
                val onRoad = index.containsKey(c[4].toLong()) || g.dist <= 20
                val leftCarriageway = r.motorway[j] && g.side < -10 && g.dist <= 60
                where[c[4].toLong()] = i
                when {
                    onRoad && dirOk && !leftCarriageway -> ours.add(c)
                    (onRoad || leftCarriageway) && (!dirOk || leftCarriageway) -> opposite.add(c)
                    g.dist <= 60 -> near.add(c)
                }
            }
            ours.sortBy { where[it[4].toLong()] }
            val withRoute = System.getenv("RADAR_ROUTE") == "1"
            val rdf = File(dir, "roads_$k.json")
            val roads = if (System.getenv("RADAR_ROADS") == "1") {
                if (!rdf.exists()) continue
                RoadAhead(RoadAhead.parse(JSONObject(rdf.readText()).getJSONArray("elements")))
            } else null
            val alerts = drive(r, cams, 1.0, withRoute = withRoute, roads = roads)
            val said = alerts.filter { !it.over }.associateBy { it.cam }
            val oursIds = ours.map { it[4].toLong() }.toSet()
            rep.append("\n== ${r.name}: ${cams.size} radares en la zona, ${ours.size} en la ruta y nuestro sentido, ${opposite.size} del otro sentido, ${near.size} dudosos (a 20–60 m)\n")
            for (c in ours) {
                val a = said[c[4].toLong()]
                rep.append(String.format("  radar %d (límite %s, sentido %s): %s\n", c[4].toLong(),
                    if (c[2] > 0) c[2].toInt().toString() else "?", if (c[3].isNaN()) "?" else c[3].toInt().toString() + "°",
                    if (a != null) String.format("aviso a %.0f m", a.m) else "SIN AVISO"))
            }
            val nearIds = near.map { it[4].toLong() }.toSet()
            val wrong = said.values.filter { it.cam !in oursIds }
            for (a in wrong) {
                val c = cams.first { it[4].toLong() == a.cam }
                val g = place(r, c[0], c[1])
                val why = when {
                    opposite.any { it[4].toLong() == a.cam } -> "del otro sentido"
                    a.cam in nearIds -> "dudoso"
                    else -> "ajeno"
                }
                rep.append(String.format("  %s: radar %d a %.0f m, %s (a %.0f m del trazado, %s, sentido %s)\n",
                    if (why == "dudoso") "aviso dudoso" else "AVISO DE MÁS", a.cam, a.m, why, g.dist,
                    if (g.side >= 0) "a la derecha" else "a la izquierda", if (c[3].isNaN()) "?" else c[3].toInt().toString() + "°"))
            }
            // Pasando a 15 km/h por encima del límite de cada radar: el «vas a…» de los que tienen límite.
            val fast = drive(r, cams, 1.0, overBy = 15.0, withRoute = withRoute, roads = roads).filter { it.over && it.cam in oursIds }.map { it.cam }.toSet()
            rep.append(String.format("  a 15 km/h por encima del límite: «vas a…» en %d de %d radares con límite\n", fast.size,
                ours.count { it[2] > 0 }))
            wrongAll += wrong.count { it.cam !in nearIds }
            oursAll += ours.size
            saidAll += ours.count { said.containsKey(it[4].toLong()) }
        }
        rep.insert(0, "Radares en la ruta y nuestro sentido: $oursAll; avisados: $saidAll; avisos de más (otro sentido o ajenos): $wrongAll\n")
        System.getenv("OUT")?.let { File(it).writeText(rep.toString()) }
    }
}
