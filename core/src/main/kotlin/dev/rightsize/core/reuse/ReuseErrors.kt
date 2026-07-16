package dev.rightsize.core.reuse

/**
 * Thrown at `start()` when a container is both reuse-active (`withReuse()` plus
 * `RIGHTSIZE_REUSE`) and joined to a custom [dev.rightsize.Network] via `withNetwork()` — reuse
 * identity ([ReuseIdentitySpec]) covers only a container's own configuration, never
 * cross-container network topology, so the two can't be combined. Drop `withNetwork()` or
 * `withReuse()` to resolve.
 */
class ReuseNetworkConflictException(message: String) : RuntimeException(message)

/**
 * Thrown at `start()` when a container built via `GenericContainer.fromCheckpoint(...)` is also
 * reuse-active (`withReuse()` plus `RIGHTSIZE_REUSE`) — `ContainerSpec.checkpointRef` is
 * deliberately excluded from [ReuseIdentitySpec] (identity covers only a container's own
 * configuration, and a checkpoint ref no more describes that than a network does), so the two
 * can't be combined. Drop `withReuse()`, or restore the checkpoint without it.
 */
class ReuseFromCheckpointConflictException(message: String) : RuntimeException(message)

/**
 * Signals that a backend's create/start failed because the reuse sandbox name was already taken
 * — another process's `start()` won the same create race. `GenericContainer`'s reuse flow
 * catches this to re-enter the adopt path once instead of treating it as an ordinary failure.
 * Backend authors should throw this directly when they can positively identify the condition (a
 * 409 name-conflict response, an "already exists" CLI error); [dev.rightsize.GenericContainer]
 * also recognizes a handful of known message phrasings as a fallback — the same
 * primary-typed/fallback-string pattern [dev.rightsize.core.PortBindConflictException] uses for
 * the ordinary (non-reuse) port-retry loop.
 */
class SandboxNameCollisionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
