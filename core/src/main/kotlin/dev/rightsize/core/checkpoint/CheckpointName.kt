package dev.rightsize.core.checkpoint

private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,40}$")

/**
 * Validates a named checkpoint's [name] against the cross-language-pinned grammar (see
 * [InvalidCheckpointNameException]) before any backend call — `GenericContainer.checkpoint(name)`
 * calls this first thing, ahead of even the capability gate's backend call. `internal`, not
 * `private`: called from `dev.rightsize.GenericContainer`, a different package in the same
 * module, the same visibility `dev.rightsize.core.reaper.canonicalBackendId` already uses for
 * the identical cross-package reason.
 */
internal fun validateCheckpointName(name: String) {
    if (!NAME_PATTERN.matches(name)) throw InvalidCheckpointNameException(name)
}
