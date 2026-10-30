package com.headqlink.link;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Los cargadores de los registros oficiales, juntos: España (DgtChargers, la DGT), Alemania (BnetzaChargers, la
 * Bundesnetzagentur) y Suiza (SwissChargers, la Oficina Federal de Energía, con el estado en directo). Cada uno solo
 * tiene puntos de su país, así que en una cuadrícula hay de uno (o, en la frontera, de varios); donde no hay ninguno
 * siguen valiendo los de OpenStreetMap. Qué registro se pide lo decide por dónde pasa la ruta, no el idioma del móvil.
 */
final class OfficialChargers {
    private OfficialChargers() {
    }

    /** Carga del móvil los que haya guardados (la primera vez). */
    static void load(Context ctx) {
        DgtChargers.sites(ctx);
        BnetzaChargers.sites(ctx);
        SwissChargers.sites(ctx);
    }

    /** Sube cuando cambia cualquiera, o los precios ad hoc de Alemania (para rehacer la lista de cargadores de la ruta). */
    static int version() {
        return ((DgtChargers.version * 31 + BnetzaChargers.version) * 31 + SwissChargers.version) * 31 + AdHocPrices.version();
    }

    /** Los puntos oficiales de una cuadrícula (ChargerCache.tileOf), o null si no hay ninguno. */
    static List<DgtChargers.Site> inTile(String tile) {
        return join(join(DgtChargers.inTile(tile), BnetzaChargers.inTile(tile)), SwissChargers.inTile(tile));
    }

    private static List<DgtChargers.Site> join(List<DgtChargers.Site> a, List<DgtChargers.Site> b) {
        if (b == null) return a;
        if (a == null) return b;
        List<DgtChargers.Site> both = new ArrayList<>(a);
        both.addAll(b);
        return both;
    }

    /** De qué registro son los datos de una cuadrícula: «dgt», «de», «ch» o null (OpenStreetMap). */
    static String sourceOf(String tile) {
        if (DgtChargers.inTile(tile) != null) return "dgt";
        if (BnetzaChargers.inTile(tile) != null) return "de";
        if (SwissChargers.inTile(tile) != null) return "ch";
        return null;
    }
}
