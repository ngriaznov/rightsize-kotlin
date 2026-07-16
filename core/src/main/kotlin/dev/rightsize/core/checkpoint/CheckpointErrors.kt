package dev.rightsize.core.checkpoint

/**
 * Thrown by `GenericContainer.checkpoint(name)` when [name] doesn't match the pinned
 * `^[a-z0-9][a-z0-9-]{0,40}$` grammar (lowercase letters, digits, and hyphens; must start with a
 * letter or digit; 1-41 characters total) — checked before any backend call, the same placement
 * as `dev.rightsize.core.CheckpointUnsupportedException`. The grammar is pinned identically
 * across the three rightsize libraries (see docs/checkpoints.md's "Reusing checkpoints across
 * runs" section).
 */
class InvalidCheckpointNameException(name: String) : RuntimeException(
    "Checkpoint name '$name' is invalid — names must match ^[a-z0-9][a-z0-9-]{0,40}$ " +
        "(lowercase letters, digits, and hyphens; must start with a letter or digit; 1-41 characters total)")
