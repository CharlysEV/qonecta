package com.headqlink.link;

/**
 * Lo real del viaje en curso para Eficiencia: la energía, el coste y el reparto, con los datos de la nube si los hay.
 *
 * El consumo medido (CloudEnergy) va de la primera lectura de la nube a la última, y Reiniciar viaje mueve el inicio de
 * los sensores: lo que vale para el viaje que se enseña es su ritmo por los km de ese viaje. Sin consumo medido, lo
 * estimado (EnergyModel). Sin Android: se prueba en el PC.
 */
final class LiveEnergy {
    /** Con menos energía que esto subida por el coche, su reparto aún no dice nada. */
    static final double MIN_SPLIT_KWH = 1.0;

    /** De dónde sale el reparto que se enseña. */
    enum Fit {
        /** Solo el modelo. */
        MODEL,
        /** El modelo, ajustado al consumo real. */
        REAL,
        /** Además, el clima y «otros» según el coche. */
        CAR
    }

    /** kWh que han salido de la batería. */
    final double batteryKwh;
    /** Litros de gasolina del generador (REEV; 0 si no se han gastado o no se saben). */
    final double fuelL;
    /** Medido con los datos del coche; si no, estimado. */
    final boolean real;

    private LiveEnergy(double batteryKwh, double fuelL, boolean real) {
        this.batteryKwh = batteryKwh;
        this.fuelL = fuelL;
        this.real = real;
    }

    /** estKwh: lo estimado. km: los del viaje. real y fuelUsedL (NaN si no es REEV): lo medido entre las lecturas. */
    static LiveEnergy of(double estKwh, double km, CloudEnergy.Result real, double fuelUsedL) {
        if (!real.ok()) return new LiveEnergy(Math.max(0, estKwh), 0, false);
        double share = Math.max(0, km) / real.km;
        return new LiveEnergy(real.kwh * share, Double.isNaN(fuelUsedL) ? 0 : fuelUsedL * share, true);
    }

    /** Coste del viaje: la batería a su precio y, en un REEV, la gasolina al suyo. */
    double cost(double elecPrice, double fuelPrice) {
        return batteryKwh * elecPrice + fuelL * fuelPrice;
    }

    /** Toda la energía del viaje, con la que da la gasolina en el generador. */
    double totalKwh() {
        return batteryKwh + fuelL * TripStats.KWH_PER_LITER;
    }

    /**
     * Deja en out el reparto que se enseña: el del modelo, ajustado a lo real si se puede. car: lo que ya ha subido el
     * coche del viaje (null: nada). Dice la parte del clima y de «otros», aunque aún no cubra todo el viaje.
     */
    Fit fit(EnergyBreakdown model, CloudUsage.Split car, EnergyBreakdown out) {
        if (!real) {
            out.copyFrom(model);
            return Fit.MODEL;
        }
        double[] pct = car != null && car.total() >= MIN_SPLIT_KWH ? car.percents() : null;
        if (pct != null && out.fitTo(model, totalKwh(), pct[1] / 100, pct[2] / 100)) return Fit.CAR;
        return out.fitTo(model, totalKwh()) ? Fit.REAL : Fit.MODEL;
    }
}
