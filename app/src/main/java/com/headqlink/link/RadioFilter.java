package com.headqlink.link;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Buscar y ordenar las emisoras de una lista de la radio (RadioScreen, Android Auto). Todo puro: la búsqueda no distingue
 * mayúsculas ni acentos («cafe» encuentra «Café del Mar») y mira el nombre y el grupo; cada palabra escrita tiene que estar
 * en alguna parte. Una lista de miles de emisoras (iptv-org por país) se prepara una vez (Index: los textos ya sin acentos)
 * y cada letra escrita es una pasada barata; se puede llamar fuera del hilo principal.
 */
final class RadioFilter {
    /** Como viene en la lista. */
    static final int ORIGINAL = 0;
    /** De la A a la Z por nombre. */
    static final int AZ = 1;
    /** Por grupo (los sin grupo, al final) y dentro de cada uno, de la A a la Z. */
    static final int GROUPED = 2;

    private RadioFilter() {
    }

    /** Puro: el orden guardado, o ORIGINAL si no es uno conocido. */
    static int clampMode(int mode) {
        return mode == AZ || mode == GROUPED ? mode : ORIGINAL;
    }

    /** Puro: el siguiente orden al pulsar el botón (original → A–Z → por grupos → original). */
    static int next(int mode, boolean hasGroups) {
        int n = (clampMode(mode) + 1) % 3;
        return n == GROUPED && !hasGroups ? ORIGINAL : n;
    }

    /** Puro: en minúsculas y sin acentos ni diéresis; los espacios sobrantes, fuera. */
    static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) b.append(ch);
        }
        return b.toString().trim();
    }

    /** Puro: las palabras de una búsqueda ya sin acentos; vacío si no hay nada que buscar. */
    static String[] words(String query) {
        String q = fold(query);
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    /** Una lista preparada: lo que se busca de cada emisora y los órdenes ya calculados. */
    static final class Index {
        private final List<RadioPlayer.Station> stations;
        private final String[] names;
        private final String[] groups;
        private final boolean hasGroups;
        private final int[][] orders = new int[3][];

        Index(List<RadioPlayer.Station> stations) {
            this.stations = new ArrayList<>(stations);
            int n = this.stations.size();
            names = new String[n];
            groups = new String[n];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                RadioPlayer.Station s = this.stations.get(i);
                names[i] = fold(s.name);
                groups[i] = fold(s.group);
                if (!groups[i].isEmpty()) any = true;
            }
            hasGroups = any;
        }

        /** Alguna emisora trae grupo (si no, «por grupos» no cambiaría nada). */
        boolean hasGroups() {
            return hasGroups;
        }

        int size() {
            return stations.size();
        }

        /** Las emisoras que cumplen la búsqueda, en el orden pedido. Estable: las iguales siguen como venían. */
        synchronized List<RadioPlayer.Station> view(int mode, String query) {
            int[] order = order(clampMode(mode));
            String[] w = words(query);
            List<RadioPlayer.Station> out = new ArrayList<>();
            for (int i : order) {
                if (matches(i, w)) out.add(stations.get(i));
            }
            return out;
        }

        private boolean matches(int i, String[] words) {
            for (String w : words) {
                if (!names[i].contains(w) && !groups[i].contains(w)) return false;
            }
            return true;
        }

        private int[] order(int mode) {
            if (orders[mode] != null) return orders[mode];
            Integer[] idx = new Integer[stations.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            if (mode == AZ) {
                Arrays.sort(idx, (a, b) -> names[a].compareTo(names[b]));
            } else if (mode == GROUPED) {
                Arrays.sort(idx, (a, b) -> {
                    boolean ea = groups[a].isEmpty();
                    boolean eb = groups[b].isEmpty();
                    if (ea != eb) return ea ? 1 : -1;
                    int g = groups[a].compareTo(groups[b]);
                    return g != 0 ? g : names[a].compareTo(names[b]);
                });
            }
            // Arrays.sort de objetos es estable: a igual clave, el orden de la lista.
            int[] out = new int[idx.length];
            for (int i = 0; i < out.length; i++) out[i] = idx[i];
            orders[mode] = out;
            return out;
        }
    }

    /** Puro: atajo para quien no necesita guardar el índice. */
    static List<RadioPlayer.Station> apply(List<RadioPlayer.Station> stations, int mode, String query) {
        if (stations == null) return Collections.emptyList();
        return new Index(stations).view(mode, query);
    }
}
