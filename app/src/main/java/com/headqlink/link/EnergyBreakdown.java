package com.headqlink.link;

/**
 * Reparto de la energía estimada de un viaje (EnergyModel): en qué se ha ido la batería (aire, rodadura, subidas,
 * aceleraciones, climatización y electrónica) y cuánto se ha recuperado al bajar y frenar.
 *
 * En cada paso, si las ruedas piden energía, lo que sale de la batería (rueda / rendimiento) se reparte entre las
 * partes que empujan en contra, en proporción a su potencia (una bajada suave que «ayuda» reduce lo que se reparte,
 * no resta de una parte). Si las ruedas devuelven energía, lo que entra en la batería es lo recuperado. Climatización
 * y electrónica salen siempre de la batería. Así, consumido − recuperado = energía neta del viaje (la que integra
 * CarSensors). Sin Android: se prueba en el PC.
 */
final class EnergyBreakdown {
    static final int AIR = 0;
    static final int ROLLING = 1;
    static final int SLOPE = 2;
    static final int ACCEL = 3;
    static final int CLIMATE = 4;
    static final int AUX = 5;
    static final int PARTS = 6;
    /** Con menos energía en el modelo que esto no se ajusta a lo real: el factor no diría nada. */
    private static final double MIN_FIT_KWH = 0.1;
    /** Factor creíble entre lo real y el modelo: fuera de esto, uno de los dos datos está mal. */
    static final double MIN_FIT = 0.4;
    static final double MAX_FIT = 2.5;

    private final double[] kwh = new double[PARTS];
    private double recovered;

    /** Suma un paso de dtH horas con el último reparto calculado por el modelo. */
    void add(EnergyModel m, double dtH) {
        if (!(dtH > 0)) return;
        kwh[CLIMATE] += m.hvacKw * dtH;
        kwh[AUX] += EnergyModel.AUX_KW * dtH;
        double air = m.aeroKw;
        double roll = m.rollKw;
        double slope = m.gradeKw;
        double accel = m.accelKw;
        double wheel = air + roll + slope + accel;
        if (wheel >= 0) {
            double pos = Math.max(0, air) + Math.max(0, roll) + Math.max(0, slope) + Math.max(0, accel);
            if (pos <= 0) return;
            double battery = wheel / EnergyModel.DRIVE_EFF * dtH;
            kwh[AIR] += Math.max(0, air) / pos * battery;
            kwh[ROLLING] += Math.max(0, roll) / pos * battery;
            kwh[SLOPE] += Math.max(0, slope) / pos * battery;
            kwh[ACCEL] += Math.max(0, accel) / pos * battery;
        } else {
            recovered += -wheel * EnergyModel.REGEN_EFF * dtH;
        }
    }

    double part(int i) {
        return kwh[i];
    }

    /** Energía que ha salido de la batería (kWh). */
    double consumed() {
        double t = 0;
        for (double v : kwh) t += v;
        return t;
    }

    /** Energía recuperada al bajar y frenar (kWh). */
    double recovered() {
        return recovered;
    }

    double net() {
        return consumed() - recovered;
    }

    /** Fracción del consumo que se lleva la parte i (0 si aún no hay consumo). */
    double share(int i) {
        double c = consumed();
        return c > 0 ? kwh[i] / c : 0;
    }

    /** Las partes y lo recuperado, para guardarlo (TripCarry). */
    double[] toArray() {
        double[] a = java.util.Arrays.copyOf(kwh, PARTS + 1);
        a[PARTS] = recovered;
        return a;
    }

    void fromArray(double[] a) {
        if (a == null || a.length != PARTS + 1) return;
        System.arraycopy(a, 0, kwh, 0, PARTS);
        recovered = a[PARTS];
    }

    /**
     * Pasa a ser el reparto del modelo (o) ajustado a la energía neta real: todas las partes y lo recuperado por el mismo
     * factor, así que los porcentajes no cambian. Con un factor increíble, se queda como el modelo y devuelve false.
     */
    boolean fitTo(EnergyBreakdown o, double netKwh) {
        copyFrom(o);
        double net = o.net();
        if (!(net > MIN_FIT_KWH)) return false;
        double f = netKwh / net;
        if (!(f >= MIN_FIT && f <= MAX_FIT)) return false;
        for (int i = 0; i < PARTS; i++) kwh[i] *= f;
        recovered *= f;
        return true;
    }

    /**
     * Igual, con la parte del clima y de «otros» (0-1) según el coche: salen de la energía neta real, y el resto se reparte
     * como dice el modelo entre aire, rodadura, subidas, aceleraciones y lo recuperado.
     */
    boolean fitTo(EnergyBreakdown o, double netKwh, double climateShare, double otherShare) {
        copyFrom(o);
        double drive = o.net() - o.kwh[CLIMATE] - o.kwh[AUX];
        if (!(drive > MIN_FIT_KWH)) return false;
        double f = netKwh * (1 - climateShare - otherShare) / drive;
        if (!(f >= MIN_FIT && f <= MAX_FIT)) return false;
        for (int i = 0; i < PARTS; i++) kwh[i] *= f;
        kwh[CLIMATE] = netKwh * climateShare;
        kwh[AUX] = netKwh * otherShare;
        recovered *= f;
        return true;
    }

    void copyFrom(EnergyBreakdown o) {
        System.arraycopy(o.kwh, 0, kwh, 0, PARTS);
        recovered = o.recovered;
    }

    void reset() {
        java.util.Arrays.fill(kwh, 0);
        recovered = 0;
    }
}
