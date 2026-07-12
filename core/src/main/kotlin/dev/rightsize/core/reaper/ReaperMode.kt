package dev.rightsize.core.reaper

/**
 * `RIGHTSIZE_REAPER` values — see docs/reaping.md. [ON] runs both the init-time sweep and the
 * per-run watchdog (the default); [SWEEP] runs the sweep only; [OFF] disables both. Unknown
 * values fall back to [ON], the same permissive-default posture as unset/blank.
 */
enum class ReaperMode {
    ON, SWEEP, OFF;

    companion object {
        fun from(value: String?): ReaperMode = when (value?.trim()?.lowercase()) {
            "sweep" -> SWEEP
            "off" -> OFF
            else -> ON
        }
    }
}
