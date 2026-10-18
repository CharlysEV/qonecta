package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Leapmotor Mate (ProtossBlaster, AGPL-3.0) en Home Assistant: Mate lee el coche de la nube de Leapmotor las 24 h con su
 * propia cuenta y lo publica por MQTT; Home Assistant crea un dispositivo con sus sensores, interruptores y botones.
 * Aquí se reconoce ese dispositivo entre los estados de /api/states, sin que el usuario tenga que elegir nada, y se
 * pasa al estado del coche de HeadQLink (LeapStatus), para pintarlo con la pestaña Estado de siempre.
 *
 * Cada elemento se reconoce por el final de su entity_id, que es el nombre en inglés que le pone Mate («Battery» →
 * …_battery, «A/C Off» → …_a_c_off) detrás del nombre del dispositivo (…leapmotor_mate…_). Las unidades se pasan a
 * km, km/h, °C, kPa y kW sea cual sea el sistema de unidades de cada Home Assistant. Puro: se prueba en el PC.
 */
final class MateHa {
    /** Lo que hay en el nombre de todos los elementos de Mate (el dispositivo se llama «Leapmotor Mate …»). */
    static final String MARK = "leapmotor_mate";
    /** Con menos elementos reconocidos no se da por un coche de Mate. */
    static final int MIN_KEYS = 5;

    /** Los elementos que se usan, por «dominio.nombre» (el final del entity_id). */
    private static final String[] KEYS = {
            "sensor.battery", "sensor.range", "sensor.charge_power", "sensor.charge_current", "sensor.charge_voltage",
            "sensor.charge_time_remaining", "sensor.inside_temp", "sensor.outside_temp", "sensor.battery_temp",
            "sensor.ac_target", "sensor.odometer", "sensor.speed", "sensor.gear", "sensor.state", "sensor.data_timestamp",
            "sensor.last_seen", "sensor.data_link", "sensor.tyre_fl", "sensor.tyre_fr", "sensor.tyre_rl", "sensor.tyre_rr",
            "sensor.seat_heating_driver", "sensor.seat_heating_passenger", "sensor.seat_ventilation_driver",
            "sensor.seat_ventilation_passenger", "sensor.steering_wheel_heat", "sensor.mirror_heating_left",
            "sensor.climate_mode",
            "binary_sensor.charging", "binary_sensor.plug_connected", "binary_sensor.any_door", "binary_sensor.any_window",
            "binary_sensor.door_front_left", "binary_sensor.door_front_right", "binary_sensor.door_rear_left",
            "binary_sensor.door_rear_right", "binary_sensor.locked", "binary_sensor.ready", "binary_sensor.trunk",
            "binary_sensor.climate",
            "lock.door_lock", "switch.trunk", "switch.air_recirculation", "number.charge_limit",
            "button.quick_cool", "button.quick_heat", "button.quick_ventilation", "button.defrost", "button.a_c_off",
            "button.find_car", "button.preheat_battery", "button.unlock_charge_cable", "button.open_trunk",
            "button.close_trunk", "button.open_windows", "button.close_windows",
            "button.driver_seat_heat_on", "button.driver_seat_heat_off", "button.passenger_seat_heat_on",
            "button.passenger_seat_heat_off", "button.driver_seat_vent_on", "button.driver_seat_vent_off",
            "button.passenger_seat_vent_on", "button.passenger_seat_vent_off", "button.steering_heat_on",
            "button.steering_heat_off", "button.mirror_heat_on", "button.mirror_heat_off",
    };

    private MateHa() {
    }

    /** Un coche de Mate: sus elementos por nombre («sensor.battery»), con el estado tal y como lo da /api/states. */
    static final class Car {
        /** Lo que va delante del nombre en los entity_id (el dispositivo), p. ej. «leapmotor_mate_123456_leapmotor_mate». */
        final String prefix;
        final Map<String, JSONObject> byKey = new LinkedHashMap<>();

        Car(String prefix) {
            this.prefix = prefix;
        }

        boolean has(String key) {
            return byKey.containsKey(key);
        }

        /** entity_id del elemento, o null si este coche no lo tiene (Mate solo publica lo que admite cada modelo). */
        String id(String key) {
            JSONObject o = byKey.get(key);
            return o == null ? null : o.optString("entity_id");
        }

        String state(String key) {
            JSONObject o = byKey.get(key);
            return o == null ? null : o.optString("state", null);
        }

        JSONObject attrs(String key) {
            JSONObject o = byKey.get(key);
            JSONObject a = o == null ? null : o.optJSONObject("attributes");
            return a == null ? new JSONObject() : a;
        }

        /** «on»/«off» de un binary_sensor como Boolean (null si no está o no se sabe). */
        Boolean on(String key) {
            String s = state(key);
            if ("on".equals(s)) return true;
            if ("off".equals(s)) return false;
            return null;
        }

        /** Valor numérico (NaN si no está, «unknown» o «unavailable»). */
        double num(String key) {
            String s = state(key);
            if (s == null) return Double.NaN;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        String unit(String key) {
            return attrs(key).optString("unit_of_measurement", "");
        }
    }

    /**
     * Puro: el coche de Mate entre los estados de Home Assistant, o null si no hay. Con dos coches en Mate (dos
     * dispositivos), el que tenga más elementos reconocidos.
     */
    static Car find(JSONArray states) {
        if (states == null) return null;
        Map<String, Car> cars = new HashMap<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject o = states.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("entity_id");
            int dot = id.indexOf('.');
            if (dot <= 0 || !id.contains(MARK)) continue;
            String domain = id.substring(0, dot);
            String obj = id.substring(dot + 1);
            String best = null;
            for (String k : KEYS) {
                if (!k.startsWith(domain + ".")) continue;
                String name = k.substring(domain.length() + 1);
                if (obj.endsWith("_" + name) && (best == null || name.length() > best.length())) best = name;
            }
            if (best == null) continue;
            String prefix = obj.substring(0, obj.length() - best.length() - 1);
            if (!prefix.contains(MARK)) continue;
            Car c = cars.computeIfAbsent(prefix, Car::new);
            c.byKey.putIfAbsent(domain + "." + best, o);
        }
        Car out = null;
        for (Car c : cars.values()) {
            if (c.byKey.size() >= MIN_KEYS && (out == null || c.byKey.size() > out.byKey.size())) out = c;
        }
        return out;
    }

    /** Puro: el nombre («sensor.battery») si entityId es un elemento de este coche de Mate; si no, null. */
    static String keyOf(String entityId, Car car) {
        if (car == null || entityId == null) return null;
        for (Map.Entry<String, JSONObject> e : car.byKey.entrySet()) {
            if (entityId.equals(e.getValue().optString("entity_id"))) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ unidades

    /** Puro: a °C desde la unidad que dé Home Assistant (°F o °C). */
    static double celsius(double v, String unit) {
        if (Double.isNaN(v)) return v;
        return unit != null && unit.contains("F") ? (v - 32) * 5 / 9 : v;
    }

    /** Puro: a km (o km/h) desde mi (mph), m o m/s. */
    static double km(double v, String unit) {
        if (Double.isNaN(v) || unit == null) return v;
        switch (unit) {
            case "mi":
            case "mph":
                return v * 1.609344;
            case "m":
                return v / 1000;
            case "m/s":
                return v * 3.6;
            default:
                return v;
        }
    }

    /** Puro: a kPa desde psi, bar, Pa, hPa o mbar. */
    static double kpa(double v, String unit) {
        if (Double.isNaN(v) || unit == null) return v;
        switch (unit) {
            case "psi":
                return v * 6.894757;
            case "bar":
                return v * 100;
            case "Pa":
                return v / 1000;
            case "hPa":
            case "mbar":
                return v / 10;
            default:
                return v;
        }
    }

    /** Puro: a minutos desde s o h. */
    static double minutes(double v, String unit) {
        if (Double.isNaN(v) || unit == null) return v;
        switch (unit) {
            case "s":
                return v / 60;
            case "h":
                return v * 60;
            default:
                return v;
        }
    }

    // ------------------------------------------------------------------ al estado de HeadQLink

    /**
     * Puro: el objeto «data» con los nombres de LeapStatus (los de la nube de Leapmotor) a partir de lo que publica
     * Mate. La entrada de CA o CC no la publica: enchufado y cargando a más de 11,5 kW se toma por CC.
     */
    static JSONObject statusData(Car c) {
        JSONObject d = new JSONObject();
        try {
            double soc = c.num("sensor.battery");
            if (!Double.isNaN(soc)) {
                d.put("soc", Math.round(soc));
                d.put("preciseSoc", soc);
            }
            put(d, "liveRemainingRange", round(km(c.num("sensor.range"), c.unit("sensor.range"))));
            Boolean charging = c.on("binary_sensor.charging");
            Boolean plug = c.on("binary_sensor.plug_connected");
            if (charging != null) d.put("chargeState", charging ? 1 : 0);
            double kw = c.num("sensor.charge_power");
            if ("W".equals(c.unit("sensor.charge_power"))) kw /= 1000;
            if (plug != null) {
                boolean dc = plug && Boolean.TRUE.equals(charging) && !Double.isNaN(kw) && Math.abs(kw) > 11.5;
                d.put("dcInputFastCharge", dc ? 1 : 0);
                d.put("acInputSlowCharge", plug && !dc ? 1 : 0);
            }
            put(d, "chargeRemainTime", round(minutes(c.num("sensor.charge_time_remaining"), c.unit("sensor.charge_time_remaining"))));
            put(d, "batteryVoltage", c.num("sensor.charge_voltage"));
            put(d, "batteryCurrent", c.num("sensor.charge_current"));
            put(d, "minBatteryTemp", celsius(c.num("sensor.battery_temp"), c.unit("sensor.battery_temp")));
            put(d, "totalMileage", round(km(c.num("sensor.odometer"), c.unit("sensor.odometer"))));
            put(d, "speed", km(c.num("sensor.speed"), c.unit("sensor.speed")));
            put(d, "interiorTemp", celsius(c.num("sensor.inside_temp"), c.unit("sensor.inside_temp")));
            String lock = c.state("lock.door_lock");
            if ("locked".equals(lock)) d.put("driverDoorLockStatus", 1);
            else if ("unlocked".equals(lock) || "open".equals(lock)) d.put("driverDoorLockStatus", 0);
            else bool(d, "driverDoorLockStatus", c.on("binary_sensor.locked"));
            bool(d, "lbcmDriverDoorStatus", c.on("binary_sensor.door_front_left"));
            bool(d, "rbcmDriverDoorStatus", c.on("binary_sensor.door_front_right"));
            bool(d, "lbcmLeftRearDoorStatus", c.on("binary_sensor.door_rear_left"));
            bool(d, "rbcmRightRearDoorStatus", c.on("binary_sensor.door_rear_right"));
            bool(d, "bbcmBackDoorStatus", c.on("binary_sensor.trunk"));
            bool(d, "bcmKeyPositionOn3", c.on("binary_sensor.ready"));
            String[] tyres = {"leftFrontTirePressure", "rightFrontTirePressure", "leftRearTirePressure", "rightRearTirePressure"};
            String[] keys = {"sensor.tyre_fl", "sensor.tyre_fr", "sensor.tyre_rl", "sensor.tyre_rr"};
            for (int i = 0; i < 4; i++) put(d, tyres[i], round(kpa(c.num(keys[i]), c.unit(keys[i]))));
            String ts = c.state("sensor.data_timestamp");
            if (ts != null && ts.matches("\\d{4}-\\d\\d-\\d\\dT.*")) d.put("collectTime", ts);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    private static double round(double v) {
        return Double.isNaN(v) ? v : Math.round(v);
    }

    private static void put(JSONObject d, String k, double v) throws JSONException {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) d.put(k, v);
    }

    private static void bool(JSONObject d, String k, Boolean v) throws JSONException {
        if (v != null) d.put(k, v ? 1 : 0);
    }

    /** El estado del coche (LeapStatus) con lo que dice Mate. */
    static LeapStatus status(Car c) {
        return LeapStatus.parse(statusData(c));
    }

    /** La foto que pinta la pestaña Estado: lo de Mate, leído ahora de Home Assistant. */
    static CarCloud.Snapshot snapshot(Car c, long readAtMs, long latencyMs) {
        return new CarCloud.Snapshot(CarCloud.State.OK, status(c), readAtMs, latencyMs, CarSpec.current().usableKwh, "", 0, false);
    }
}
