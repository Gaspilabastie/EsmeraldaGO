package com.esmeraldago.core.calc

/**
 * Los cálculos largos llaman a [check] en cada vuelta: si el hilo fue interrumpido (por ejemplo, porque
 * la pantalla que los pidió se cerró y la app usa `runInterruptible`), cortan en vez de seguir de largo.
 */
object Interrupt {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cálculo cancelado")
    }
}
