package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El trivial del camino: preguntas de tres respuestas sobre los sitios que la Guía tiene alrededor (Wikipedia), para
 * los que van en el coche en un viaje largo. Cuatro clases: de qué siglo es (sacado del resumen), qué es (del tipo de sus
 * coordenadas), cuál está más cerca y cuál es el más visitado. Puro: lo prueban los tests (los textos, con Str).
 */
final class RoadTrivia {
    static final int OPTIONS = 3;

    private RoadTrivia() {
    }

    static final class Question {
        final String text;
        final String[] options;
        final int answer;
        /** El sitio del que va (para no repetir). */
        final long placeId;

        Question(String text, String[] options, int answer, long placeId) {
            this.text = text;
            this.options = options;
            this.answer = answer;
            this.placeId = placeId;
        }
    }

    enum Kind { CENTURY, WHAT, NEAREST, FAMOUS }

    /**
     * La siguiente pregunta con estos sitios (los de la Guía), sin repetir sitio de asked; null si no hay bastantes. Va
     * probando las clases en orden aleatorio.
     */
    static Question next(List<RoadGuide.Place> pool, double lat, double lon, Set<Long> asked, Random r, Locale loc) {
        List<Kind> kinds = new ArrayList<>(java.util.Arrays.asList(Kind.values()));
        Collections.shuffle(kinds, r);
        for (Kind k : kinds) {
            Question q = make(k, pool, lat, lon, asked, r, loc);
            if (q != null) return q;
        }
        return null;
    }

    static Question make(Kind k, List<RoadGuide.Place> pool, double lat, double lon, Set<Long> asked, Random r, Locale loc) {
        switch (k) {
            case CENTURY:
                return century(pool, asked, r, loc);
            case WHAT:
                return what(pool, asked, r);
            case NEAREST:
                return nearest(pool, lat, lon, asked, r);
            default:
                return famous(pool, asked, r);
        }
    }

    // ------------------------------------------------------------------ ¿de qué siglo es?

    /** El primer siglo que nombra el resumen (romano o «12th century», según el idioma), o 0. */
    static int centuryIn(String text, Locale loc) {
        if (text == null || text.isEmpty()) return 0;
        String lang = loc.getLanguage();
        Pattern roman;
        Pattern arabic;
        switch (lang) {
            case "es":
                roman = Pattern.compile("\\bsiglo\\s+([IVXL]+)\\b");
                arabic = null;
                break;
            case "pt":
                roman = Pattern.compile("\\bs[ée]culo\\s+([IVXL]+)\\b");
                arabic = null;
                break;
            case "it":
                roman = Pattern.compile("\\b([IVXL]+)\\s+secolo\\b");
                arabic = null;
                break;
            case "fr":
                roman = Pattern.compile("\\b([IVXL]+)(?:e|ème|eme)\\s+si[èe]cle\\b");
                arabic = null;
                break;
            case "de":
                roman = null;
                arabic = Pattern.compile("\\b(\\d{1,2})\\.\\s*Jahrhundert");
                break;
            case "nl":
                roman = null;
                arabic = Pattern.compile("\\b(\\d{1,2})(?:e|de)\\s+eeuw\\b");
                break;
            case "zh":
                roman = null;
                arabic = Pattern.compile("(\\d{1,2})世纪");
                break;
            default:
                roman = null;
                arabic = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)[ -]century\\b", Pattern.CASE_INSENSITIVE);
        }
        if (roman != null) {
            Matcher m = roman.matcher(text);
            if (m.find()) return fromRoman(m.group(1));
        } else {
            Matcher m = arabic.matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    static int fromRoman(String s) {
        Map<Character, Integer> v = new HashMap<>();
        v.put('I', 1);
        v.put('V', 5);
        v.put('X', 10);
        v.put('L', 50);
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            int a = v.getOrDefault(s.charAt(i), 0);
            int b = i + 1 < s.length() ? v.getOrDefault(s.charAt(i + 1), 0) : 0;
            total += a < b ? -a : a;
        }
        return total >= 1 && total <= 21 ? total : 0;
    }

    static String toRoman(int n) {
        int[] val = {50, 40, 10, 9, 5, 4, 1};
        String[] sym = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < val.length; i++) {
            while (n >= val[i]) {
                b.append(sym[i]);
                n -= val[i];
            }
        }
        return b.toString();
    }

    /** «siglo XII», «12th century», «XIIe siècle», «12. Jahrhundert»…, como lo escribe cada idioma. */
    static String centuryLabel(int c, Locale loc) {
        switch (loc.getLanguage()) {
            case "es":
                return "siglo " + toRoman(c);
            case "pt":
                return "século " + toRoman(c);
            case "it":
                return toRoman(c) + " secolo";
            case "fr":
                return toRoman(c) + (c == 1 ? "er" : "e") + " siècle";
            case "de":
                return c + ". Jahrhundert";
            case "nl":
                return c + "e eeuw";
            case "zh":
                return c + "世纪";
            default:
                int m100 = c % 100;
                String suf = m100 >= 11 && m100 <= 13 ? "th" : c % 10 == 1 ? "st" : c % 10 == 2 ? "nd" : c % 10 == 3 ? "rd" : "th";
                return c + suf + " century";
        }
    }

    private static Question century(List<RoadGuide.Place> pool, Set<Long> asked, Random r, Locale loc) {
        List<RoadGuide.Place> cand = new ArrayList<>();
        for (RoadGuide.Place p : pool) if (!asked.contains(p.id) && centuryIn(p.extract, loc) >= 2) cand.add(p);
        if (cand.isEmpty()) return null;
        RoadGuide.Place p = cand.get(r.nextInt(cand.size()));
        int c = centuryIn(p.extract, loc);
        List<Integer> opts = new ArrayList<>();
        opts.add(c);
        // Dos siglos más, cerca del bueno (sin pasar del XXI ni bajar del I).
        int[] deltas = {-3, -2, -1, 1, 2, 3};
        List<Integer> d = new ArrayList<>();
        for (int x : deltas) if (c + x >= 1 && c + x <= 21) d.add(c + x);
        Collections.shuffle(d, r);
        for (int i = 0; i < d.size() && opts.size() < OPTIONS; i++) opts.add(d.get(i));
        Collections.sort(opts);
        String[] o = new String[opts.size()];
        for (int i = 0; i < o.length; i++) o[i] = centuryLabel(opts.get(i), loc);
        return new Question(Str.get(R.string.hql_trivia_q_century, p.title), o, opts.indexOf(c), p.id);
    }

    // ------------------------------------------------------------------ ¿qué es?

    /** El tipo de las coordenadas → qué es («un monumento», «una montaña»…), o 0 si no se pregunta por él. */
    static int whatLabel(String type) {
        switch (RoadGuide.baseType(type)) {
            case "city":
                return R.string.hql_trivia_a_city;
            case "landmark":
                return R.string.hql_trivia_a_landmark;
            case "mountain":
                return R.string.hql_trivia_a_mountain;
            case "waterbody":
                return R.string.hql_trivia_a_water;
            case "river":
                return R.string.hql_trivia_a_river;
            case "isle":
                return R.string.hql_trivia_a_isle;
            case "forest":
                return R.string.hql_trivia_a_forest;
            default:
                return 0;
        }
    }

    private static final int[] WHAT_ALL = {R.string.hql_trivia_a_city, R.string.hql_trivia_a_landmark, R.string.hql_trivia_a_mountain,
            R.string.hql_trivia_a_water, R.string.hql_trivia_a_river, R.string.hql_trivia_a_forest};

    private static Question what(List<RoadGuide.Place> pool, Set<Long> asked, Random r) {
        List<RoadGuide.Place> cand = new ArrayList<>();
        for (RoadGuide.Place p : pool) if (!asked.contains(p.id) && whatLabel(p.type) != 0) cand.add(p);
        if (cand.isEmpty()) return null;
        RoadGuide.Place p = cand.get(r.nextInt(cand.size()));
        int good = whatLabel(p.type);
        List<Integer> opts = new ArrayList<>();
        opts.add(good);
        List<Integer> others = new ArrayList<>();
        for (int x : WHAT_ALL) if (x != good) others.add(x);
        Collections.shuffle(others, r);
        opts.add(others.get(0));
        opts.add(others.get(1));
        Collections.shuffle(opts, r);
        String[] o = new String[OPTIONS];
        for (int i = 0; i < OPTIONS; i++) o[i] = Str.get(opts.get(i));
        return new Question(Str.get(R.string.hql_trivia_q_what, p.title), o, opts.indexOf(good), p.id);
    }

    // ------------------------------------------------------------------ ¿cuál está más cerca? ¿cuál es el más visitado?

    private static Question nearest(List<RoadGuide.Place> pool, double lat, double lon, Set<Long> asked, Random r) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        List<RoadGuide.Place> cand = new ArrayList<>();
        for (RoadGuide.Place p : pool) if (!asked.contains(p.id)) cand.add(p);
        if (cand.size() < OPTIONS) return null;
        Collections.shuffle(cand, r);
        // Tres que estén a distancias bien distintas (al menos 1,5 km entre ellas).
        List<RoadGuide.Place> pick = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        for (RoadGuide.Place p : cand) {
            double d = RoadInfo.dist(lat, lon, p.lat, p.lon) / 1000;
            boolean apart = true;
            for (double x : dist) if (Math.abs(x - d) < 1.5) apart = false;
            if (!apart) continue;
            pick.add(p);
            dist.add(d);
            if (pick.size() == OPTIONS) break;
        }
        if (pick.size() < OPTIONS) return null;
        int best = 0;
        for (int i = 1; i < OPTIONS; i++) if (dist.get(i) < dist.get(best)) best = i;
        String[] o = new String[OPTIONS];
        for (int i = 0; i < OPTIONS; i++) o[i] = pick.get(i).title;
        return new Question(Str.get(R.string.hql_trivia_q_nearest), o, best, pick.get(best).id);
    }

    private static Question famous(List<RoadGuide.Place> pool, Set<Long> asked, Random r) {
        List<RoadGuide.Place> cand = new ArrayList<>();
        for (RoadGuide.Place p : pool) if (!asked.contains(p.id) && p.views > 0) cand.add(p);
        if (cand.size() < OPTIONS) return null;
        Collections.shuffle(cand, r);
        // Tres con visitas bien distintas: el más visitado, al menos un 50 % por encima de los otros.
        List<RoadGuide.Place> pick = new ArrayList<>(cand.subList(0, OPTIONS));
        int best = 0;
        for (int i = 1; i < OPTIONS; i++) if (pick.get(i).views > pick.get(best).views) best = i;
        for (int i = 0; i < OPTIONS; i++) {
            if (i != best && pick.get(i).views * 1.5 > pick.get(best).views) return null;
        }
        String[] o = new String[OPTIONS];
        for (int i = 0; i < OPTIONS; i++) o[i] = pick.get(i).title;
        return new Question(Str.get(R.string.hql_trivia_q_famous), o, best, pick.get(best).id);
    }
}
