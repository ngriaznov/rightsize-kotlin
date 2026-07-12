package dev.rightsize.core.reaper

import java.time.Duration
import java.time.Instant

/**
 * Cross-language liveness check for a run record: a run is ALIVE iff a process with [pid]
 * exists AND its OS-reported start time matches [startedIso] within [TOLERANCE] — the
 * start-time match is what defeats PID reuse (a different, unrelated process that happens to
 * land on the same PID later). All three rightsize libraries share this exact protocol (pid +
 * start-time-within-2s, JSON-identical), so a Kotlin sweep can judge a run record written by
 * the Node or Rust library sharing the same cache dir just as well as one of its own.
 */
object Liveness {
    val TOLERANCE: Duration = Duration.ofSeconds(2)

    /**
     * An unparseable [startedIso] is never alive — deliberately conservative in the *other*
     * direction from a missing start-time reading (see below), because an unparseable
     * timestamp on an otherwise-valid record most likely means someone (or something) other
     * than this library wrote it, and this method's caller ([Sweeper]) has its own separate,
     * more permissive handling for that case at the whole-record level. If the platform can't
     * report the running process's start time at all, this reports alive: a sweep must never
     * remove a genuinely running sandbox merely because the JVM couldn't positively confirm
     * PID reuse didn't happen.
     */
    fun isAlive(pid: Long, startedIso: String): Boolean {
        val recorded = runCatching { Instant.parse(startedIso) }.getOrNull() ?: return false
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        val actualStart = handle.info().startInstant().orElse(null) ?: return true
        return Duration.between(actualStart, recorded).abs() <= TOLERANCE
    }

    /** The current process's own start time, ISO-8601 — written into this run's record.
     * Falls back to the epoch if the platform can't report it (see [isAlive]'s doc for why a
     * missing reading is handled leniently rather than treated as an error here too). */
    fun currentProcessStartedIso(): String =
        ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH).toString()
}
