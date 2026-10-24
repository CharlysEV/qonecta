package com.headqlink.link;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Los cargadores de los registros oficiales, juntos: España (DgtChargers, la DGT) y Alemania (BnetzaChargers, la
 * Bundesnetzagentur). Cada uno solo tiene puntos de su país, así que en una cuadrícula hay de uno (o, en la frontera, de
 * los dos); donde no hay ninguno siguen valiendo los de OpenStreetMap. Qué registro se pide lo decide por dónde pasa la
 * ruta, no el idioma del móvil.
 */
final class OfficialChargers {
    private OfficialChargers() {
    }

    /** Carga del móvil los que haya guardados (la primera vez). */
    static void load(Context ctx) {
        DgtChargers.sites(ctx);
        BnetzaChargers.sites(ctx);
    }

    /** Sube cuando cambia cualquiera de los dos (para rehacer la lista de cargadores de la ruta). */
    static int version() {
        return DgtChargers.version * 31 + BnetzaChargers.version;
    }

    /** Los puntos oficiales de una cuadrícula (ChargerCache.tileOf), o null si no hay ninguno. */
    static List<DgtChargers.Site> inTile(String tile) {
        List<DgtChargers.Site> es = DgtChargers.inTile(tile);
        List<DgtChargers.Site> de = BnetzaChargers.inTile(tile);
        if (de == null) return es;
        if (es == null) return de;
        List<DgtChargers.Site> both = new ArrayList<>(es);
        both.addAll(de);
        return both;
    }

    /** De qué registro son los datos de una cuadrícula: «dgt», «de» o null (OpenStreetMap). */
    static String sourceOf(String tile) {
        if (DgtChargers.inTile(tile) != null) return "dgt";
        if (BnetzaChargers.inTile(tile) != null) return "de";
        return null;
    }
}
