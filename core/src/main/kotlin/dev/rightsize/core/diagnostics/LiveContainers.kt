package dev.rightsize.core.diagnostics

import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle

/**
 * Process-local registry of currently running containers — registered by
 * [dev.rightsize.GenericContainer.start] on success, deregistered by
 * [dev.rightsize.GenericContainer.stop]. Purely in-memory bookkeeping for [Diagnostics.report]
 * to describe *this* live process; not the reaper's ledger (`RunLedger`), which is disk-based,
 * survives process death, and exists to let a *different* process reap a dead one's leftovers.
 * Not gated by backend name (unlike the reaper, which only participates for "msb"/"docker") —
 * diagnostics works the same for a test double as for a real backend.
 */
object LiveContainers {
    /** One registered live container: everything [Diagnostics.render] needs to describe it,
     * without touching anything but [backend]`.logs`. */
    data class Entry(val handle: SandboxHandle, val backend: SandboxBackend, val host: String)

    // LinkedHashMap for registration-order iteration (a report listing containers in the order
    // they were started reads more naturally than an arbitrary hash order); synchronized since
    // GenericContainer.start()/stop() can run concurrently across independent containers.
    private val entries = LinkedHashMap<String, Entry>()

    /** Registers [handle] as live under its [SandboxHandle.spec] name — call only after a
     * successful start. */
    @Synchronized fun register(handle: SandboxHandle, backend: SandboxBackend, host: String) {
        entries[handle.spec.name] = Entry(handle, backend, host)
    }

    /** Removes the entry for [name], if any. A no-op for a name never registered (or already
     * removed) — callers need not track whether registration actually happened. */
    @Synchronized fun deregister(name: String) {
        entries.remove(name)
    }

    /** A stable-ordered point-in-time copy — safe to iterate without holding this object's lock. */
    @Synchronized fun snapshot(): List<Entry> = entries.values.toList()
}
