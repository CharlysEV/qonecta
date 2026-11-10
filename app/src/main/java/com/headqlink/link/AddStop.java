package com.headqlink.link;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * «Añadir parada de carga» (foro LPMFF, #369): el conductor fija un cargador que conoce (para comer y cargar a la vez),
 * aunque el plan no lo necesite. Aquí lo puro de la ventana, igual en el móvil (PhoneRoute) y en el coche (RouteTab): la
 * lista de todos los cargadores a lo largo de la ruta con su distancia a ella, y la búsqueda por nombre entre ellos.
 * Si lo que se busca no está entre los cargadores cargados, se busca el sitio (PlaceSearch) y se queda el que cae cerca
 * de la ruta (RoutePlanner.chargerAt). Sin Android.
 */
final class AddStop {
    /** Un sitio buscado cuenta si cae a menos de esto de la ruta (km). */
    static final double PLACE_MAX_OFF_KM = 8;
    /** Cuántos se enseñan como mucho en la lista. */
    static final int MAX_ROWS = 80;

    private AddStop() {
    }

    /** Un cargador de la lista: a qué distancia de la ruta queda (km). */
    static final class Entry {
        final RoutePlanner.Charger charger;
        final double offKm;

        Entry(RoutePlanner.Charger charger, double offKm) {
            this.charger = charger;
            this.offKm = offKm;
        }
    }

    /** Puro: minúsculas y sin tildes, para comparar nombres («Área de servicio» = «area de servicio»). */
    static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Puro: los cargadores de la ruta cuyo nombre, detalle o red contiene todas las palabras de query (todos con query
     * vacía), por km de la ruta. Los de detrás del coche (kmAlong menor que fromKm) no salen. offKm da la distancia a la
     * ruta de cada uno (null: 0).
     */
    static List<Entry> entries(List<RoutePlanner.Charger> all, String query, double fromKm, Offs offs) {
        String[] words = norm(query).isEmpty() ? new String[0] : norm(query).split("\\s+");
        List<Entry> out = new ArrayList<>();
        for (RoutePlanner.Charger c : all) {
            if (c.kmAlong <= fromKm + ChargePlanner.HERE_KM) continue;
            if (words.length > 0) {
                String hay = norm(c.name) + " " + norm(c.detail) + " " + norm(c.network);
                boolean all2 = true;
                for (String w : words) all2 &= hay.contains(w);
                if (!all2) continue;
            }
            out.add(new Entry(c, offs == null ? 0 : offs.of(c)));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.charger.kmAlong, b.charger.kmAlong));
        while (out.size() > MAX_ROWS) out.remove(out.size() - 1);
        return out;
    }

    /** La distancia de un cargador a la ruta (km). */
    interface Offs {
        double of(RoutePlanner.Charger c);
    }

    /**
     * Puro: dónde queda el cargador respecto al plan, para decirlo claro: con cuánto se llega a él viniendo de la parada
     * anterior (o del coche), o NaN si no se sabe. prevKm/prevPct: el último punto del plan antes de él.
     */
    static double arrivalAt(ChargePlanner.Result r, RoutePlanner.Charger c) {
        double prevKm = r.fromKm;
        double prevPct = r.socNow;
        for (ChargePlanner.Stop s : r.stops) {
            if (s.km >= c.kmAlong) break;
            prevKm = s.km;
            prevPct = s.departPct;
        }
        return prevPct - (ChargePlanner.kwhAt(r.km, r.kwhCum, c.kmAlong) - ChargePlanner.kwhAt(r.km, r.kwhCum, prevKm)) / r.capacityKwh * 100;
    }
}
