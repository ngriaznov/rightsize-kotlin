package dev.rightsize.core.reaper

/**
 * Maps a [dev.rightsize.core.SandboxBackend.name] (user-facing, `RIGHTSIZE_BACKEND`-matching:
 * `"microsandbox"` / `"docker"`) to the ledger's own cross-language vocabulary
 * (`"msb"` / `"docker"` — see `RunRecord.backend`'s doc). The two differ because "microsandbox"
 * is this repo's chosen backend-selection name, while the ledger's `backend` field must be a
 * shared literal every one of the three rightsize libraries writes and reads identically.
 * Anything other than the known msb/docker names passes through lowercased unchanged — the
 * reaper simply never matches it against a real backend's own name (see [Sweeper]).
 */
internal fun canonicalBackendId(backendName: String): String =
    if (backendName.equals("microsandbox", ignoreCase = true)) "msb" else backendName.lowercase()
