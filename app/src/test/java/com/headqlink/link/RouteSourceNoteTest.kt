package com.headqlink.link

import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** La nota de la pestaña Ruta: la de Google Maps, distinta de la suya, o propia (17-10: 146 km previstos y 164 hechos). */
class RouteSourceNoteTest {
    @Test
    fun whereTheRouteComesFrom() {
        val p = RoutePlanner.Plan()
        assertEquals(R.string.hql_route_source_own, RoutePlanner.sourceNote(p))
        p.googleMismatch = true
        assertEquals(R.string.hql_route_source_differs, RoutePlanner.sourceNote(p))
        p.googleMismatch = false
        p.googleMatched = true
        assertEquals(R.string.hql_route_source_google, RoutePlanner.sourceNote(p))
    }
}
