package dev.rightsize.core.checkpoint

import java.nio.file.Path

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

/**
 * Thrown by `Checkpoint.exportTo` when the checkpoint's own backend reports (via
 * `SandboxBackend.hasCheckpoint`) that [ref]'s artifact no longer exists — probed before any
 * filesystem work, so `exportTo` never produces a broken archive for a checkpoint whose
 * backend-side artifact is already gone. See docs/checkpoints.md's "Moving checkpoints between
 * machines" section.
 */
class StaleCheckpointException(ref: String, backend: String) : RuntimeException(
    "Checkpoint '$ref' has no artifact on the '$backend' backend — it may already have been " +
        "removed (Checkpoint.remove, SandboxBackend.removeCheckpoint) or never existed; " +
        "exportTo requires the artifact to still be present")

/**
 * Thrown by `Checkpoint.importFrom` when the archive at [path] fails validation before any
 * backend call: a missing file, a missing or malformed `checkpoint.json`, an unsupported
 * `rightsizeArchive` version, or a missing `artifact` member. [reason] names what's wrong. See
 * docs/checkpoints.md's "Moving checkpoints between machines" section.
 */
class MalformedCheckpointArchiveException(path: Path, reason: String) : RuntimeException(
    "Checkpoint archive '$path' is malformed: $reason")
