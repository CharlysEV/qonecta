package com.headqlink.link;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filtro de los cargadores de la pestaña Ruta, como en los planificadores de rutas: potencia mínima y redes (Tesla,
 * Zunder, Ionity…). La red de cada cargador sale de sus etiquetas de OpenStreetMap (brand, operator, network, name).
 * Sin Android: se prueba en el PC.
 */
final class ChargerFilter {
    /** Potencias mínimas que se pueden elegir (kW; 0 = cualquiera). */
    static final int[] MIN_KW_CHOICES = {0, 22, 50, 100, 150, 200, 300};
    /**
     * Voltaje que se puede elegir (20-10): cualquiera, 800 V (de continua y de ChargePlanner.HIGH_VOLTS o más) o
     * 400–500 V (de continua, por debajo). En España, el que declara cada conector en el registro de la DGT.
     */
    static final int VOLTS_ANY = 0;
    static final int VOLTS_800 = 800;
    static final int VOLTS_400 = 400;
    static final int[] VOLTS_CHOICES = {VOLTS_ANY, VOLTS_800, VOLTS_400};
    /** Clave de los cargadores de una red que no está en la lista. */
    static final String OTHER = "other";

    /** Redes conocidas: clave → {nombre que se enseña, trozos que la delatan en las etiquetas (normalizados)}. */
    static final Map<String, String[]> NETWORKS = new LinkedHashMap<>();

    static {
        NETWORKS.put("tesla", new String[]{"Tesla", "tesla", "supercharger"});
        NETWORKS.put("zunder", new String[]{"Zunder", "zunder"});
        NETWORKS.put("ionity", new String[]{"Ionity", "ionity"});
        NETWORKS.put("iberdrola", new String[]{"Iberdrola", "iberdrola"});
        NETWORKS.put("endesa", new String[]{"Endesa X", "endesa"});
        NETWORKS.put("repsol", new String[]{"Repsol", "repsol"});
        NETWORKS.put("wenea", new String[]{"Wenea", "wenea"});
        NETWORKS.put("moeve", new String[]{"Moeve / Cepsa", "moeve", "cepsa"});
        NETWORKS.put("galp", new String[]{"Galp", "galp"});
        NETWORKS.put("fastned", new String[]{"Fastned", "fastned"});
        NETWORKS.put("electra", new String[]{"Electra", "electra"});
        NETWORKS.put("allego", new String[]{"Allego", "allego"});
        NETWORKS.put("powerdot", new String[]{"Powerdot", "powerdot"});
        NETWORKS.put("atlante", new String[]{"Atlante", "atlante"});
        NETWORKS.put("easycharger", new String[]{"Easycharger", "easycharger"});
        NETWORKS.put("plenitude", new String[]{"Plenitude / Be Charge", "plenitude", "be charge", "becharge"});
        NETWORKS.put("lidl", new String[]{"Lidl", "lidl"});
        NETWORKS.put("ewiva", new String[]{"Ewiva", "ewiva"});
        NETWORKS.put("enel", new String[]{"Enel X", "enel"});
        NETWORKS.put("totalenergies", new String[]{"TotalEnergies", "totalenergies", "total energies"});
        NETWORKS.put("shell", new String[]{"Shell Recharge", "shell"});
        // En el registro alemán, Aral pulse figura como «BP Europa SE».
        NETWORKS.put("bp", new String[]{"bp pulse", "bp pulse", "aral pulse", "aral", "bp europa"});
        NETWORKS.put("plenoil", new String[]{"Plenoil", "plenoil"});
        NETWORKS.put("acciona", new String[]{"Acciona", "acciona"});
        NETWORKS.put("edp", new String[]{"EDP", "edp"});
        NETWORKS.put("instavolt", new String[]{"InstaVolt", "instavolt"});
        // Alemania y alrededores (27-10, pedido en el foro LPMFF): las de más cargadores rápidos del registro de la
        // Bundesnetzagentur (EnBW sola tiene ~2000 sitios) y las grandes de Austria, Suiza, Francia e Italia.
        // «=…»: el campo entero, no una palabra suelta («Mer» sí; «Aire de la Mer», no).
        NETWORKS.put("enbw", new String[]{"EnBW", "enbw"});
        NETWORKS.put("ewego", new String[]{"EWE Go", "ewe go"});
        NETWORKS.put("pfalzwerke", new String[]{"Pfalzwerke", "pfalzwerke"});
        NETWORKS.put("kaufland", new String[]{"Kaufland", "kaufland"});
        NETWORKS.put("vattenfall", new String[]{"Vattenfall InCharge", "vattenfall", "incharge"});
        NETWORKS.put("aldi", new String[]{"Aldi", "aldi"});
        NETWORKS.put("eon", new String[]{"E.ON Drive", "e on drive", "eon drive", "=e on", "=eon"});
        NETWORKS.put("mer", new String[]{"Mer", "=mer", "mer germany", "mer norge", "mer sverige", "mer uk"});
        NETWORKS.put("teag", new String[]{"TEAG", "teag"});
        NETWORKS.put("citywatt", new String[]{"Citywatt", "citywatt"});
        NETWORKS.put("edeka", new String[]{"EDEKA", "edeka"});
        NETWORKS.put("comfortcharge", new String[]{"Comfortcharge", "comfortcharge", "comfort charge"});
        NETWORKS.put("circlek", new String[]{"Circle K", "circle k"});
        NETWORKS.put("mercedes", new String[]{"Mercedes-Benz", "mercedes benz high power", "mercedes benz charging", "mercedes benz hpc"});
        NETWORKS.put("eviny", new String[]{"Eviny", "eviny", "bilkraft"});
        NETWORKS.put("orlen", new String[]{"Orlen", "orlen"});
        NETWORKS.put("smatrics", new String[]{"Smatrics", "smatrics"});
        NETWORKS.put("gofast", new String[]{"GOFAST", "gofast"});
        NETWORKS.put("izivia", new String[]{"Izivia", "izivia"});
        NETWORKS.put("freetox", new String[]{"Free To X", "free to x"});
        // Suiza: las de más puntos rápidos del registro de la Oficina Federal de Energía (SwissChargers, 27-10).
        NETWORKS.put("energie360", new String[]{"Energie 360°", "energie 360"});
        NETWORKS.put("mcharge", new String[]{"M-Charge (Migros)", "m charge", "mcharge"});
        NETWORKS.put("move", new String[]{"Move", "=move", "move mobility"});
        NETWORKS.put("ecarup", new String[]{"eCarUp", "ecarup"});
        NETWORKS.put("swisscharge", new String[]{"swisscharge", "swisscharge"});
        NETWORKS.put("powerup", new String[]{"Power Up", "=power up", "powerup"});
        NETWORKS.put("eponet", new String[]{"Eponet", "eponet"});
        NETWORKS.put("aviavolt", new String[]{"AVIA VOLT", "avia volt"});
        NETWORKS.put("iwb", new String[]{"IWB", "iwb"});
        NETWORKS.put("ewz", new String[]{"ewz", "ewz", "elektrizitatswerk der stadt zurich"});
    }

    private ChargerFilter() {
    }

    /** Minúsculas, sin tildes ni signos. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** La red de un cargador por sus etiquetas (la primera que encaje, mirando primero brand, network y operator), u OTHER. */
    static String classify(String brand, String network, String operator, String name) {
        for (String field : new String[]{brand, network, operator, name}) {
            String n = " " + norm(field) + " ";
            if (n.trim().isEmpty()) continue;
            for (Map.Entry<String, String[]> e : NETWORKS.entrySet()) {
                String[] v = e.getValue();
                for (int i = 1; i < v.length; i++) {
                    // Palabra entera (o varias): «bp pulse» sí; «bp» dentro de otra palabra, no. «=…»: el campo entero.
                    if (v[i].startsWith("=") ? n.trim().equals(v[i].substring(1)) : n.contains(" " + v[i] + " ")) return e.getKey();
                }
            }
        }
        return OTHER;
    }

    /** Nombre que se enseña de una red (clave), o la propia clave. */
    static String label(String key) {
        String[] v = NETWORKS.get(key);
        return v != null ? v[0] : key;
    }

    /** ¿Pasa el filtro? minKw 0: cualquiera (un cargador sin potencia conocida solo pasa así); networks vacío: todas. */
    // De dónde sale la potencia de un cargador: OpenStreetMap la dice; se estima por la red (las que solo tienen carga
    // rápida: un Supercharger, un hub de Zunder o de Ionity); por los enchufes (CCS o CHAdeMO: continua, pero no se
    // sabe cuánta; solo Tipo 2: alterna); o nada.
    static final int KW_TAGGED = 0;
    static final int KW_NETWORK = 1;
    static final int KW_SOCKETS = 2;
    static final int KW_UNKNOWN = 3;
    static final int SOCKET_DC = 1;
    static final int SOCKET_AC = 2;

    /** Tipo de enchufe de una clave socket:* de OpenStreetMap (sin «socket:»): continua, alterna o 0 si no se sabe. */
    static int socketKind(String socket) {
        String s = socket.toLowerCase(Locale.ROOT);
        if (s.contains(":")) s = s.substring(0, s.indexOf(':'));
        if (s.equals("type2_combo") || s.equals("chademo") || s.equals("type1_combo") || s.equals("tesla_supercharger")
                || s.equals("tesla_supercharger_ccs") || s.equals("gb_dc") || s.equals("nacs")) {
            return SOCKET_DC;
        }
        if (s.equals("type2") || s.equals("type2_cable") || s.equals("type1") || s.equals("schuko") || s.equals("cee_blue")
                || s.equals("cee_red_16a") || s.equals("cee_red_32a") || s.equals("tesla_destination") || s.equals("type3c")) {
            return SOCKET_AC;
        }
        return 0;
    }

    /**
     * Potencia probable de un cargador sin potencia en OpenStreetMap: {kW, origen (KW_*)}. Las redes que solo montan carga
     * rápida tienen su potencia típica (por lo bajo); si no, los enchufes dicen si es continua (≈50 kW, sin confirmar) o
     * alterna (22 kW); si no se sabe nada, 0.
     */
    static double[] estimateKw(String network, String name, int sockets) {
        boolean dc = (sockets & SOCKET_DC) != 0;
        boolean ac = (sockets & SOCKET_AC) != 0;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("tesla".equals(network)) {
            // Los «Destination» (hoteles, restaurantes) son de alterna.
            if (n.contains("destination") || (ac && !dc)) return new double[]{11, KW_NETWORK};
            return new double[]{150, KW_NETWORK};
        }
        Double typical = FAST_ONLY.get(network);
        if (typical != null) return new double[]{typical, KW_NETWORK};
        if (dc) return new double[]{50, KW_SOCKETS};
        if (ac) return new double[]{22, KW_SOCKETS};
        return new double[]{0, KW_UNKNOWN};
    }

    /**
     * Distintivo propio de cada red para las listas: su color y sus iniciales (no son sus logotipos, que son marcas
     * registradas). {color ARGB, iniciales, texto oscuro (1) o claro (0)}; null para OTHER.
     */
    static Object[] badge(String network) {
        return BADGES.get(network);
    }

    private static final Map<String, Object[]> BADGES = new LinkedHashMap<>();

    static {
        BADGES.put("tesla", new Object[]{0xFFE31937, "T", 0});
        BADGES.put("zunder", new Object[]{0xFF00B37E, "Z", 0});
        BADGES.put("ionity", new Object[]{0xFF2E2A6E, "IO", 0});
        BADGES.put("iberdrola", new Object[]{0xFF5CB615, "IB", 0});
        BADGES.put("endesa", new Object[]{0xFF0075BE, "EX", 0});
        BADGES.put("repsol", new Object[]{0xFFFF8200, "R", 0});
        BADGES.put("wenea", new Object[]{0xFF00A3E0, "W", 0});
        BADGES.put("moeve", new Object[]{0xFF00857C, "M", 0});
        BADGES.put("galp", new Object[]{0xFFFF6A13, "G", 0});
        BADGES.put("fastned", new Object[]{0xFFFFD400, "F", 1});
        BADGES.put("electra", new Object[]{0xFF14D3A0, "E", 1});
        BADGES.put("allego", new Object[]{0xFFFF7E1F, "A", 0});
        BADGES.put("powerdot", new Object[]{0xFFFFC72C, "P", 1});
        BADGES.put("atlante", new Object[]{0xFF00AEEF, "AT", 0});
        BADGES.put("easycharger", new Object[]{0xFF3DBE6A, "EC", 0});
        BADGES.put("plenitude", new Object[]{0xFFFFD100, "BC", 1});
        BADGES.put("lidl", new Object[]{0xFF0050AA, "L", 0});
        BADGES.put("ewiva", new Object[]{0xFF0062A8, "EW", 0});
        BADGES.put("enel", new Object[]{0xFF008C5A, "EN", 0});
        BADGES.put("totalenergies", new Object[]{0xFFED0000, "TE", 0});
        BADGES.put("shell", new Object[]{0xFFFBCE07, "S", 1});
        BADGES.put("bp", new Object[]{0xFF009900, "bp", 0});
        BADGES.put("plenoil", new Object[]{0xFF1E9E3E, "PL", 0});
        BADGES.put("acciona", new Object[]{0xFFE2001A, "AC", 0});
        BADGES.put("edp", new Object[]{0xFFE32119, "EDP", 0});
        BADGES.put("instavolt", new Object[]{0xFF00A0DF, "IV", 0});
        BADGES.put("enbw", new Object[]{0xFF1E50A0, "EB", 0});
        BADGES.put("ewego", new Object[]{0xFF00A0DC, "EG", 0});
        BADGES.put("pfalzwerke", new Object[]{0xFF0069B4, "PW", 0});
        BADGES.put("kaufland", new Object[]{0xFFE10915, "K", 0});
        BADGES.put("vattenfall", new Object[]{0xFFFFDA00, "V", 1});
        BADGES.put("aldi", new Object[]{0xFF1C3F94, "AL", 0});
        BADGES.put("eon", new Object[]{0xFFEA1C0A, "EO", 0});
        BADGES.put("mer", new Object[]{0xFF00A88E, "ME", 0});
        BADGES.put("teag", new Object[]{0xFF009FE3, "TG", 0});
        BADGES.put("citywatt", new Object[]{0xFF2E7D32, "CW", 0});
        BADGES.put("edeka", new Object[]{0xFFFFD500, "ED", 1});
        BADGES.put("comfortcharge", new Object[]{0xFFE20074, "CC", 0});
        BADGES.put("circlek", new Object[]{0xFFEE2E24, "CK", 0});
        BADGES.put("mercedes", new Object[]{0xFF5B6770, "MB", 0});
        BADGES.put("eviny", new Object[]{0xFF6B2C91, "EV", 0});
        BADGES.put("orlen", new Object[]{0xFFD0021B, "OR", 0});
        BADGES.put("smatrics", new Object[]{0xFF6CC24A, "SM", 1});
        BADGES.put("gofast", new Object[]{0xFFE30613, "GF", 0});
        BADGES.put("izivia", new Object[]{0xFF00A3A1, "IZ", 0});
        BADGES.put("freetox", new Object[]{0xFF00B140, "FX", 0});
        BADGES.put("energie360", new Object[]{0xFF00A19A, "360", 0});
        BADGES.put("mcharge", new Object[]{0xFFFF6600, "MC", 0});
        BADGES.put("move", new Object[]{0xFF00A0E1, "MV", 0});
        BADGES.put("ecarup", new Object[]{0xFF7AB800, "eC", 0});
        BADGES.put("swisscharge", new Object[]{0xFFE30613, "SC", 0});
        BADGES.put("powerup", new Object[]{0xFF0055A5, "PU", 0});
        BADGES.put("eponet", new Object[]{0xFF009EE0, "EP", 0});
        BADGES.put("aviavolt", new Object[]{0xFFE2001A, "AV", 0});
        BADGES.put("iwb", new Object[]{0xFF00965E, "IWB", 0});
        BADGES.put("ewz", new Object[]{0xFF0073B9, "EWZ", 0});
    }

    /**
     * Voltaje típico (V) de un cargador de continua sin el dato: los Supercharger de Europa son de 400–500 V (en España,
     * los 80 del registro de la DGT); Ionity, de 920 V. 0 si no se sabe.
     */
    static double typicalVolts(String network, String name, int sockets) {
        if ((sockets & SOCKET_DC) == 0 && sockets != 0) return 0;
        if ("tesla".equals(network)) {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return n.contains("destination") ? 0 : 500;
        }
        if ("ionity".equals(network)) return 920;
        return 0;
    }

    /** Redes que solo montan carga rápida, con su potencia típica por lo bajo (kW). */
    private static final Map<String, Double> FAST_ONLY = new LinkedHashMap<>();

    static {
        FAST_ONLY.put("ionity", 350.0);
        FAST_ONLY.put("zunder", 180.0);
        FAST_ONLY.put("fastned", 300.0);
        FAST_ONLY.put("electra", 300.0);
        FAST_ONLY.put("atlante", 150.0);
        FAST_ONLY.put("mercedes", 300.0);
    }

    /**
     * El cargador pasa el filtro. Con potencia mínima: la de OpenStreetMap o la típica de su red; los demás (potencia sin
     * confirmar) solo si includeUnknown, y nunca los de solo alterna si piden más de lo que dan.
     */
    static boolean accepts(RoutePlanner.Charger c, int minKw, Set<String> networks, boolean includeUnknown) {
        return accepts(c, minKw, VOLTS_ANY, networks, includeUnknown);
    }

    /** Lo mismo con voltaje (VOLTS_*): sin voltaje conocido, solo si includeUnknown; los de solo alterna, nunca. */
    static boolean accepts(RoutePlanner.Charger c, int minKw, int volts, Set<String> networks, boolean includeUnknown) {
        boolean netOk = networks == null || networks.isEmpty()
                || networks.contains(c.network == null || c.network.isEmpty() ? OTHER : c.network);
        if (!netOk) return false;
        if (!voltsOk(c.acOnly, c.maxVolts, volts, includeUnknown)) return false;
        if (minKw <= 0) return true;
        if (c.kwSource == KW_TAGGED || c.kwSource == KW_NETWORK || c.acOnly) return c.maxKw >= minKw;
        return includeUnknown;
    }

    /** Lo mismo con un tope de precio ad hoc (céntimos/kWh, 0 = sin tope; solo donde hay precios: Alemania). */
    static boolean accepts(RoutePlanner.Charger c, int minKw, int volts, Set<String> networks, boolean includeUnknown, int maxAdHocCt) {
        return accepts(c, minKw, volts, networks, includeUnknown) && adHocOk(c.adHocArea, c.adHocKwh, maxAdHocCt, includeUnknown);
    }

    /**
     * Puro: ¿pasa el tope de precio ad hoc? Sin tope o fuera de donde hay precios (area), sí; sin precio conocido, solo si
     * includeUnknown (como los que no dicen su potencia).
     */
    static boolean adHocOk(boolean area, double kwh, int maxCt, boolean includeUnknown) {
        if (maxCt <= 0 || !area) return true;
        if (Double.isNaN(kwh)) return includeUnknown;
        return kwh * 100 <= maxCt + 1e-6;
    }

    /** Topes de precio ad hoc que se pueden elegir (céntimos/kWh; 0 = sin tope). */
    static final int[] ADHOC_CHOICES = {0, 50, 60, 70};

    /** Tope guardado, a una elección de ADHOC_CHOICES. */
    static int snapAdHoc(int ct) {
        for (int v : ADHOC_CHOICES) if (v == ct) return v;
        return 0;
    }

    /** Puro: ¿pasa el voltaje elegido? acOnly: solo alterna; maxVolts 0: no se sabe. */
    static boolean voltsOk(boolean acOnly, double maxVolts, int volts, boolean includeUnknown) {
        if (volts != VOLTS_800 && volts != VOLTS_400) return true;
        if (acOnly) return false;
        if (!(maxVolts > 0)) return includeUnknown;
        boolean high = maxVolts >= ChargePlanner.HIGH_VOLTS;
        return volts == VOLTS_800 ? high : !high;
    }

    /** Voltaje guardado, a una elección de VOLTS_CHOICES. */
    static int snapVolts(int v) {
        return v == VOLTS_800 || v == VOLTS_400 ? v : VOLTS_ANY;
    }

    static boolean accepts(double maxKw, String network, int minKw, Set<String> networks) {
        if (minKw > 0 && !(maxKw >= minKw)) return false;
        return networks == null || networks.isEmpty() || networks.contains(network == null || network.isEmpty() ? OTHER : network);
    }

    /** Redes presentes en una lista de claves, con cuántos cargadores tiene cada una, de más a menos (OTHER al final). */
    static List<Map.Entry<String, Integer>> counts(List<String> networks) {
        Map<String, Integer> m = new LinkedHashMap<>();
        // Sin getOrDefault ni List.sort: la versión de GitHub llega a Android antiguos sin esas API.
        for (String k : networks) {
            Integer c = m.get(k);
            m.put(k, c == null ? 1 : c + 1);
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>(m.entrySet());
        Collections.sort(out, (a, b) -> {
            if (a.getKey().equals(OTHER) != b.getKey().equals(OTHER)) return a.getKey().equals(OTHER) ? 1 : -1;
            return Integer.compare(b.getValue(), a.getValue());
        });
        return out;
    }

    /**
     * Países (ISO en minúsculas) donde tiene cargadores cada red; «*»: en casi toda Europa. Aproximado y solo para ordenar
     * el filtro (que salgan las de tu país), nunca para quitar cargadores de una ruta.
     */
    private static final Map<String, String[]> COUNTRIES = new LinkedHashMap<>();

    static {
        String[] eu = {"*"};
        COUNTRIES.put("tesla", eu);
        COUNTRIES.put("ionity", eu);
        COUNTRIES.put("shell", eu);
        COUNTRIES.put("lidl", eu);
        COUNTRIES.put("zunder", new String[]{"es", "pt", "fr"});
        COUNTRIES.put("iberdrola", new String[]{"es", "pt"});
        COUNTRIES.put("endesa", new String[]{"es", "pt"});
        COUNTRIES.put("repsol", new String[]{"es", "pt"});
        COUNTRIES.put("wenea", new String[]{"es"});
        COUNTRIES.put("moeve", new String[]{"es", "pt"});
        COUNTRIES.put("galp", new String[]{"pt", "es"});
        COUNTRIES.put("fastned", new String[]{"nl", "de", "be", "fr", "ch", "gb", "dk"});
        COUNTRIES.put("electra", new String[]{"fr", "be", "it", "es", "nl", "de", "ch", "lu"});
        COUNTRIES.put("allego", new String[]{"nl", "be", "de", "fr", "dk", "se", "no", "gb"});
        COUNTRIES.put("powerdot", new String[]{"pt", "pl", "es", "fr", "be"});
        COUNTRIES.put("atlante", new String[]{"it", "fr", "es", "pt"});
        COUNTRIES.put("easycharger", new String[]{"es"});
        COUNTRIES.put("plenitude", new String[]{"it", "es", "fr", "de"});
        COUNTRIES.put("ewiva", new String[]{"it"});
        COUNTRIES.put("enel", new String[]{"it", "es"});
        COUNTRIES.put("totalenergies", new String[]{"fr", "be", "nl", "de", "lu", "gb"});
        COUNTRIES.put("bp", new String[]{"de", "es", "gb", "nl", "pt", "at"});
        COUNTRIES.put("plenoil", new String[]{"es"});
        COUNTRIES.put("acciona", new String[]{"es"});
        COUNTRIES.put("edp", new String[]{"pt", "es"});
        COUNTRIES.put("instavolt", new String[]{"gb", "es", "ie"});
        COUNTRIES.put("enbw", new String[]{"de", "at", "ch"});
        COUNTRIES.put("ewego", new String[]{"de"});
        COUNTRIES.put("pfalzwerke", new String[]{"de"});
        COUNTRIES.put("kaufland", new String[]{"de", "cz", "pl", "sk", "hr", "ro", "bg"});
        COUNTRIES.put("vattenfall", new String[]{"nl", "de", "se"});
        COUNTRIES.put("aldi", new String[]{"de", "at", "ch", "nl", "be"});
        COUNTRIES.put("eon", new String[]{"de", "se", "dk", "cz"});
        COUNTRIES.put("mer", new String[]{"de", "no", "se", "gb"});
        COUNTRIES.put("teag", new String[]{"de"});
        COUNTRIES.put("citywatt", new String[]{"de"});
        COUNTRIES.put("edeka", new String[]{"de"});
        COUNTRIES.put("comfortcharge", new String[]{"de"});
        COUNTRIES.put("circlek", new String[]{"no", "se", "dk", "de", "pl", "ie"});
        COUNTRIES.put("mercedes", new String[]{"de", "at", "ch", "nl", "be", "it", "fr", "es"});
        COUNTRIES.put("eviny", new String[]{"no", "de", "se"});
        COUNTRIES.put("orlen", new String[]{"pl", "de", "cz"});
        COUNTRIES.put("smatrics", new String[]{"at"});
        COUNTRIES.put("gofast", new String[]{"ch", "at", "de"});
        COUNTRIES.put("izivia", new String[]{"fr"});
        COUNTRIES.put("freetox", new String[]{"it"});
        String[] ch = {"ch", "li"};
        for (String k : new String[]{"energie360", "mcharge", "move", "ecarup", "swisscharge", "powerup", "eponet", "aviavolt",
                "iwb", "ewz"}) {
            COUNTRIES.put(k, ch);
        }
    }

    /** Puro: ¿la red tiene cargadores en ese país (ISO en minúsculas)? Sin país ("" o null) o red sin datos: sí. */
    static boolean inCountry(String key, String iso) {
        if (iso == null || iso.isEmpty()) return true;
        String[] cs = COUNTRIES.get(key);
        if (cs == null) return true;
        for (String c : cs) if (c.equals("*") || c.equals(iso)) return true;
        return false;
    }

    /** Puro: las redes conocidas que no están en exclude, por su nombre de la A a la Z (para el filtro, debajo de las de la ruta). */
    static List<String> others(Set<String> exclude) {
        return others(exclude, null, null);
    }

    /**
     * Puro: lo mismo, solo las del país iso (null o "": todas), más las de keep aunque sean de otro país (las elegidas,
     * para poder quitarlas). OTHER no entra.
     */
    static List<String> others(Set<String> exclude, String iso, Set<String> keep) {
        List<String> out = new ArrayList<>();
        for (String k : NETWORKS.keySet()) {
            if (exclude != null && exclude.contains(k)) continue;
            if (inCountry(k, iso) || (keep != null && keep.contains(k))) out.add(k);
        }
        Collections.sort(out, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        return out;
    }

    // ------------------------------------------------------------------ guardar

    static String formatNetworks(Set<String> s) {
        if (s == null || s.isEmpty()) return "";
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        StringBuilder b = new StringBuilder();
        for (String k : l) b.append(b.length() > 0 ? "," : "").append(k);
        return b.toString();
    }

    static Set<String> parseNetworks(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Potencia guardada, a la elección más cercana de MIN_KW_CHOICES. */
    static int snapMinKw(int kw) {
        int best = 0;
        for (int c : MIN_KW_CHOICES) if (Math.abs(c - kw) < Math.abs(best - kw)) best = c;
        return best;
    }
}
