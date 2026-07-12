package dev.rightsize.core.reuse

/**
 * The environment half of reuse's double opt-in (see docs/reuse.md): a container marked with
 * `withReuse()` only actually reuses when `RIGHTSIZE_REUSE` is also the exact string `"true"` or
 * `"1"`. Unlike `RIGHTSIZE_REAPER`'s permissive on/sweep/off vocabulary (unknown values fall
 * back to the safe default), reuse's env gate has no loose-truthy fallback: misreading an
 * unrelated/malformed value as "enabled" would silently start leaving sandboxes running in an
 * environment that never meant to opt into that (e.g. ephemeral CI) — anything not exactly one
 * of the two accepted spellings is treated as disabled.
 */
object ReuseMode {
    fun enabled(env: Map<String, String>): Boolean {
        val v = env["RIGHTSIZE_REUSE"]
        return v == "true" || v == "1"
    }
}
