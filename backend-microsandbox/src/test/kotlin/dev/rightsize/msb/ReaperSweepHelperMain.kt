package dev.rightsize.msb

import dev.rightsize.core.Backends

/**
 * Helper child process for [ReaperSweepIT] — its only job is to be "a fresh library init" in
 * a genuinely new process, so resolving the backend here runs the real init-time sweep
 * (`Backends.active()` -> `Reaper.onBackendResolved`) against whatever dead run records the
 * parent test fabricated in the shared cache dir, exactly as it would for any real process
 * that happens to start after a crashed one. Exits immediately once resolved.
 */
fun main() {
    Backends.active()
}
