package com.headqlink.link;

import org.json.JSONObject;

/**
 * Lo de «Consumo» de la app oficial, según la nube de Leapmotor (28-10):
 * - El reparto de la energía de una ventana (getLastweekEC): conducción, clima y otros, en kWh (texto).
 * - Los km de una ventana (mileage/energy/detail): totalAccumulatedMileage.
 * Rutas de leapmotor-api (markoceri) y leapmotor-ha (kerniger). El historial de cargas del coche
 * (charge/daily/detail/page) se probó el 28-10 y la nube lo rechaza con «信息校验失败» de las tres formas documentadas
 * (35 y 7 días, UTC y la zona del móvil): «Registros de carga» usa las cargas que vio Qonecta+ (ChargeLog). Sin Android
 * ni red: se prueba en el PC.
 */
final class CloudUsage {
    /** El reparto de la energía de una ventana (kWh). NaN: no lo dijo. */
    static final class Split {
        final double driveKwh;
        final double acKwh;
        final double otherKwh;

        Split(double driveKwh, double acKwh, double otherKwh) {
            this.driveKwh = driveKwh;
            this.acKwh = acKwh;
            this.otherKwh = otherKwh;
        }

        double total() {
            return z(driveKwh) + z(acKwh) + z(otherKwh);
        }

        /** Parte de cada uno (0–100), en el orden conducción, clima, otros; null si no hay energía. */
        double[] percents() {
            double t = total();
            if (!(t > 0)) return null;
            return new double[]{z(driveKwh) / t * 100, z(acKwh) / t * 100, z(otherKwh) / t * 100};
        }

        private static double z(double v) {
            return Double.isNaN(v) || v < 0 ? 0 : v;
        }
    }

    /** Lo de una ventana según el coche: los km (NaN si no los dio) y el reparto (null si no lo dio). */
    static final class Window {
        final long fromMs;
        final double km;
        final Split split;
        final long atMs;

        Window(long fromMs, double km, Split split, long atMs) {
            this.fromMs = fromMs;
            this.km = km;
            this.split = split;
            this.atMs = atMs;
        }

        double kwh() {
            return split == null ? Double.NaN : split.total();
        }

        double per100() {
            double k = kwh();
            return km >= 1 && k > 0 ? k / km * 100 : Double.NaN;
        }

        boolean useful() {
            return !Double.isNaN(km) || split != null;
        }
    }

    private CloudUsage() {
    }

    /** El reparto («data» de getLastweekEC), o null si no trae ninguna cifra. */
    static Split parseSplit(JSONObject data) {
        if (data == null) return null;
        double d = kwh(data.opt("driverEC"));
        double a = kwh(data.opt("acEC"));
        double o = kwh(data.opt("otherEC"));
        if (Double.isNaN(d) && Double.isNaN(a) && Double.isNaN(o)) return null;
        return new Split(d, a, o);
    }

    /** Los km de la ventana («data» de mileage/energy/detail), o NaN. */
    static double parseKm(JSONObject data) {
        if (data == null) return Double.NaN;
        double km = LeapStatus.asDouble(data.opt("totalAccumulatedMileage"));
        return Double.isNaN(km) || Double.isInfinite(km) || km < 0 ? Double.NaN : km;
    }

    private static double kwh(Object v) {
        double d = LeapStatus.asDouble(v);
        return Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > 100_000 ? Double.NaN : d;
    }
}
