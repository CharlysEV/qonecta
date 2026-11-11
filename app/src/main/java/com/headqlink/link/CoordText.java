package com.headqlink.link;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordenadas escritas como texto: «48.1401, 11.5763», «48,1401 11,5763» (coma decimal, como en alemán),
 * «48°08'24.4"N 11°34'47.0"E» (lo que copia Google Maps de un punto marcado) o «N 48° 08.407' E 011° 34.783'»
 * (grados y minutos, como en geocaching). Antes se buscaban como si fueran una dirección y el buscador devolvía un
 * sitio cualquiera cerca (LPMFF #416: unos 3 km al lado). Puro.
 */
final class CoordText {
    private CoordText() {
    }

    /** Un número con decimales (punto o coma). */
    private static final String DEC = "\\d+(?:[.,]\\d+)?";
    /** Grados, minutos y segundos (o grados y minutos), con el hemisferio delante o detrás. O = Ost (este en alemán). */
    private static final String DMS_ONE = "([NSEWO])?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*°\\s*(?:(" + DEC + ")\\s*'\\s*(?:(" + DEC
            + ")\\s*\"\\s*)?)?([NSEWO])?";
    private static final Pattern DMS = Pattern.compile("(?<![\\w.,])" + DMS_ONE + "\\s*[,;/]?\\s*" + DMS_ONE + "(?![\\w])",
            Pattern.CASE_INSENSITIVE);
    /** Decimal con punto: «48.1401, 11.5763», «48.1401 11.5763», «N48.1401 E11.5763», «48.1401N, 11.5763E». */
    private static final Pattern DOT = Pattern.compile("(?<![\\w.,])([NS])?\\s*(-?\\d{1,3}\\.\\d{2,})\\s*°?\\s*([NS])?"
            + "\\s*[,;/\\s]\\s*([EWO])?\\s*(-?\\d{1,3}\\.\\d{2,})\\s*°?\\s*([EWO])?(?![\\w.])", Pattern.CASE_INSENSITIVE);
    /** Decimal con coma: «48,1401 11,5763», «48,1401; 11,5763», «48,1401, 11,5763» (al menos 4 decimales). */
    private static final Pattern COMMA = Pattern.compile("(?<![\\w.,])(-?\\d{1,3},\\d{4,})\\s*(?:[;/]\\s*|,\\s+|\\s+)(-?\\d{1,3},\\d{4,})(?![\\w.,])");

    /** {lat, lon} si el texto lleva unas coordenadas, o null. */
    static double[] parse(String s) {
        if (s == null) return null;
        String t = s.replace('″', '"').replace('′', '\'').replace('’', '\'').replace('º', '°').replace("''", "\"")
                .replace('\u00a0', ' ');
        Matcher m = DMS.matcher(t);
        while (m.find()) {
            double[] r = dms(m);
            if (r != null) return r;
        }
        m = DOT.matcher(t);
        while (m.find()) {
            double lat = signed(Double.parseDouble(m.group(2)), first(m.group(1), m.group(3)));
            double lon = signed(Double.parseDouble(m.group(5)), first(m.group(4), m.group(6)));
            double[] r = valid(lat, lon);
            if (r != null) return r;
        }
        m = COMMA.matcher(t);
        while (m.find()) {
            double[] r = valid(num(m.group(1)), num(m.group(2)));
            if (r != null) return r;
        }
        return null;
    }

    /** El nombre para un sitio dado por coordenadas: «48.14010, 11.57630». */
    static String label(double lat, double lon) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    private static double[] dms(Matcher m) {
        String h1 = first(m.group(1), m.group(5));
        String h2 = first(m.group(6), m.group(10));
        double a = deg(m.group(2), m.group(3), m.group(4));
        double b = deg(m.group(7), m.group(8), m.group(9));
        // Sin hemisferio ni minutos no es claramente una coordenada («5° 3°»): fuera.
        if (h1 == null && h2 == null && m.group(3) == null && m.group(8) == null) return null;
        boolean swap = h1 != null && isLon(h1) || h2 != null && !isLon(h2);
        if (swap) {
            double x = a;
            a = b;
            b = x;
            String hx = h1;
            h1 = h2;
            h2 = hx;
        }
        return valid(signed(a, h1), signed(b, h2));
    }

    private static double deg(String d, String min, String sec) {
        double v = num(d);
        if (min != null) v += num(min) / 60.0;
        if (sec != null) v += num(sec) / 3600.0;
        return v;
    }

    private static boolean isLon(String h) {
        char c = Character.toUpperCase(h.charAt(0));
        return c == 'E' || c == 'W' || c == 'O';
    }

    private static double signed(double v, String h) {
        if (h == null) return v;
        char c = Character.toUpperCase(h.charAt(0));
        return c == 'S' || c == 'W' ? -Math.abs(v) : v;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static double[] valid(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || lat == 0 && lon == 0) return null;
        return new double[]{lat, lon};
    }
}
