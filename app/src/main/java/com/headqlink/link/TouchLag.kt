package com.headqlink.link

/**
 * La hora de llegada del toque del coche que se está repartiendo, para medir cuánto tarda en aplicarse ([LagMeter])
 * sin cambiar las firmas del reparto. `QdSessionBridge.onTouch` la pone ([begin]) antes de `VideoHub.touch` y la quita
 * ([end]) al volver; las fuentes (AaPassthroughSource, CarUi, TouchService) la recogen con [current] en esa misma
 * llamada, en el hilo de eventos, y se la llevan a su hilo (envío a Android Auto, hilo principal). Por hilo: un toque
 * sintético de otro hilo no se lleva la de otro.
 */
internal object TouchLag {
    /** Un toque del coche: a qué sesión medirlo y cuándo lo sacó del enlace el lector (`System.nanoTime`). */
    class Stamp(private val meter: LagMeter, val arrivalNs: Long) {
        /** El toque llegó a [stage] ahora. */
        fun done(stage: LagMeter.Stage) {
            meter.record(stage, (System.nanoTime() - arrivalNs) / 1_000_000)
        }
    }

    private val current = ThreadLocal<Stamp?>()

    @JvmStatic
    fun begin(meter: LagMeter, arrivalNs: Long) = current.set(Stamp(meter, arrivalNs))

    @JvmStatic
    fun end() = current.set(null)

    /** El toque que se reparte en este hilo, o null (toque sintético, vista previa, otro hilo). */
    @JvmStatic
    fun current(): Stamp? = current.get()
}
