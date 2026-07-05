package dev.rightsize

import java.util.concurrent.CompletableFuture

object Startables {
    /**
     * Starts every container in parallel and blocks until all are ready. Use when independent
     * containers (no cross-links) would otherwise pay their startup latencies back-to-back.
     * Any container's failure propagates; the others are left running for the caller to stop.
     */
    fun deepStart(vararg containers: GenericContainer<*>) {
        val starting = containers.map { c -> CompletableFuture.runAsync { c.start() } }
        CompletableFuture.allOf(*starting.toTypedArray()).join()
    }
}
