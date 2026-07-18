package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointBackendMismatchException
import dev.rightsize.core.CheckpointSpec
import dev.rightsize.core.SandboxBackend
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Backs `Checkpoint.exportTo`/`Checkpoint.importFrom` (see docs/checkpoints.md's "Moving
 * checkpoints between machines" section) — constructed against an explicit [registry] rather than
 * `CacheDir.resolve()` directly, the same split [CheckpointRegistry] itself already documents, so
 * unit tests exercise the whole export/import policy against a fake backend and a temp directory.
 *
 * The archive is a plain tar, built with the host `tar` CLI (present on Linux, macOS, and Windows
 * 10+ as bsdtar) rather than a hand-rolled tar writer, with exactly two members at its root:
 * `checkpoint.json` (a [CheckpointArchiveRecord]) and `artifact` (the backend's own payload file,
 * byte-for-byte what [SandboxBackend.exportCheckpoint]/[SandboxBackend.importCheckpoint]
 * produce/consume) — pinned identically across the three rightsize libraries. No container-level
 * compression: msb's payload is already zstd-compressed, and a docker `save` tar compresses
 * poorly enough not to matter here.
 */
class CheckpointArchiver(private val registry: CheckpointRegistry) {

    /**
     * [checkpoint]'s own `backend` must equal [backend]'s name before any backend or filesystem
     * work at all — the same [CheckpointBackendMismatchException] `fromCheckpoint`/
     * `checkpoint(name)`'s replace path use. Once that passes, [backend].`hasCheckpoint` probes
     * that the artifact still exists — a stale checkpoint throws [StaleCheckpointException] rather
     * than producing a broken archive. [registry] is then searched for an entry pointing at this
     * exact ref (see [CheckpointRegistry.findByRef]) to recover the checkpoint's `name`/`createdIso`
     * if it was ever checkpointed with a name — a bare [Checkpoint] carries neither, so this reverse
     * lookup is the only way `exportTo` can tell a named checkpoint's archive apart from an unnamed
     * one (which archives with `name: null` and a freshly minted `createdIso`). Staging happens in
     * a fresh, unique temp directory, removed in a `finally` whether the capture below succeeds or
     * fails. [dest]'s parent directories are created if missing; an existing file at [dest] is
     * overwritten (tar's own destination-file semantics).
     */
    fun exportArchive(checkpoint: Checkpoint, backend: SandboxBackend, dest: Path) {
        if (!checkpoint.backend.equals(backend.name, ignoreCase = true)) {
            throw CheckpointBackendMismatchException(checkpoint.backend, backend.name)
        }
        if (!backend.hasCheckpoint(checkpoint.ref)) {
            throw StaleCheckpointException(checkpoint.ref, backend.name)
        }
        val existing = registry.findByRef(checkpoint.ref, backend.name)
        val staging = Files.createTempDirectory("rightsize-checkpoint-export")
        try {
            backend.exportCheckpoint(checkpoint.ref, staging.resolve(ARTIFACT_MEMBER))
            val manifest = CheckpointArchiveRecord(
                rightsizeArchive = ARCHIVE_VERSION,
                name = existing?.name,
                ref = checkpoint.ref,
                backend = checkpoint.backend,
                createdIso = existing?.createdIso ?: Instant.now().toString(),
                spec = checkpoint.spec,
            )
            Files.writeString(staging.resolve(MANIFEST_MEMBER), manifest.toJson())
            dest.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            runTar(
                dest.toAbsolutePath().parent,
                listOf("-cf", dest.fileName.toString(), "-C", tarDirArg(staging), MANIFEST_MEMBER, ARTIFACT_MEMBER),
            )
        } finally {
            deleteRecursively(staging)
        }
    }

    /**
     * Extracts [src] into a fresh temp directory and validates `checkpoint.json` — present at
     * all, well-formed, `rightsizeArchive == 1`, [CheckpointArchiveRecord.name] against the
     * checkpoint-name grammar when non-null, and [CheckpointArchiveRecord.backend] against
     * [backend]'s name — entirely before any backend call, so a missing file, a malformed
     * archive, or a wrong-backend one is rejected with a typed error and never reaches
     * [SandboxBackend.importCheckpoint]. On success, [backend].`importCheckpoint` materializes
     * the artifact and returns the EFFECTIVE ref (not necessarily the archived one — see that
     * method's doc: msb mints a fresh digest-shaped ref, docker's is unchanged). A NAMED archive
     * then replaces [CheckpointArchiveRecord.name]'s registry entry the same way
     * `GenericContainer.checkpoint(name)` does (best-effort remove the OLD same-backend artifact
     * first, only when its ref actually differs, then rewrite the entry to the new ref); a
     * nameless archive returns an ephemeral [Checkpoint] with no registry write at all. Staging
     * is removed in a `finally` regardless of outcome.
     */
    fun importArchive(backend: SandboxBackend, src: Path): Checkpoint {
        if (!Files.isRegularFile(src)) throw MalformedCheckpointArchiveException(src, "no such file")
        val staging = Files.createTempDirectory("rightsize-checkpoint-import")
        try {
            runTar(
                src.toAbsolutePath().parent,
                listOf("-xf", src.fileName.toString(), "-C", tarDirArg(staging)),
            )
            val manifestFile = staging.resolve(MANIFEST_MEMBER)
            if (!Files.exists(manifestFile)) throw MalformedCheckpointArchiveException(src, "missing $MANIFEST_MEMBER")
            val text = runCatching { Files.readString(manifestFile) }.getOrNull()
                ?: throw MalformedCheckpointArchiveException(src, "$MANIFEST_MEMBER is unreadable")
            val manifest = CheckpointArchiveRecord.parse(text)
                ?: throw MalformedCheckpointArchiveException(src, "$MANIFEST_MEMBER is malformed")
            if (manifest.rightsizeArchive != ARCHIVE_VERSION) {
                throw MalformedCheckpointArchiveException(
                    src, "unsupported rightsizeArchive ${manifest.rightsizeArchive} (expected $ARCHIVE_VERSION)")
            }
            manifest.name?.let(::validateCheckpointName)
            if (!manifest.backend.equals(backend.name, ignoreCase = true)) {
                throw CheckpointBackendMismatchException(manifest.backend, backend.name)
            }
            val artifactFile = staging.resolve(ARTIFACT_MEMBER)
            if (!Files.exists(artifactFile)) throw MalformedCheckpointArchiveException(src, "missing $ARTIFACT_MEMBER")

            val effectiveRef = backend.importCheckpoint(artifactFile, manifest.ref)
            manifest.name?.let { name -> replaceRegistryEntry(name, effectiveRef, backend, manifest.spec) }
            return Checkpoint(ref = effectiveRef, backend = backend.name, spec = manifest.spec)
        } finally {
            deleteRecursively(staging)
        }
    }

    /** Same replace semantics as `GenericContainer.checkpoint(name)`'s own
     * `replaceExistingNamedCheckpoint`: the OLD entry's artifact is best-effort removed only when
     * it's recorded under [backend] itself (a foreign ref means nothing to this backend's own
     * `removeCheckpoint` — same same-backend gate every other checkpoint-replace path applies) AND
     * its ref actually differs from the freshly imported [effectiveRef]; the entry is rewritten to
     * the new ref either way. */
    private fun replaceRegistryEntry(
        name: String, effectiveRef: String, backend: SandboxBackend, spec: CheckpointSpec,
    ) {
        registry.read(name)?.let { old ->
            if (old.ref != effectiveRef && old.backend.equals(backend.name, ignoreCase = true)) {
                runCatching { backend.removeCheckpoint(old.ref) }
            }
        }
        registry.write(name, CheckpointRecord(name, effectiveRef, backend.name, Instant.now().toString(), spec))
    }

    /** Normalizes a directory for tar's `-C` argument: on Windows, Git's GNU (MSYS) tar
     * mangles backslash paths ("Cannot open"), while both it and System32's bsdtar accept
     * the same path with forward slashes. Elsewhere the path is returned untouched — a
     * backslash is a legal filename character on POSIX. */
    private fun tarDirArg(dir: Path): String =
        if (System.getProperty("os.name").lowercase().contains("win")) dir.toString().replace('\\', '/')
        else dir.toString()

    /** Runs `tar` with [workingDir] as the child's cwd so the `-f` argument can stay a bare
     * BASENAME: an absolute Windows path there (`C:\...`) is parsed by GNU tar as a
     * `host:path` remote-archive spec, and which flavor `tar` resolves to on Windows depends
     * on PATH order (System32's bsdtar accepts drive-letter paths, Git's GNU tar does not);
     * basename-plus-cwd behaves identically under both. Streams are joined only after the
     * process has actually exited (naturally or via [Process.destroyForcibly]) — joining first
     * would block on a timed-out process that's still writing output. */
    private fun runTar(workingDir: Path?, args: List<String>) {
        val proc = ProcessBuilder(listOf("tar") + args)
            .apply { workingDir?.let { directory(it.toFile()) } }
            .start()
        val stderr = StringBuilder()
        val outDrain = drain(proc.inputStream) {}
        val errDrain = drain(proc.errorStream) { stderr.appendLine(it) }
        val finished = proc.waitFor(TAR_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        outDrain.join(); errDrain.join()
        if (!finished) {
            error("tar ${args.joinToString(" ")} timed out after ${TAR_TIMEOUT_SEC}s and was force-killed")
        }
        if (proc.exitValue() != 0) {
            error("tar ${args.joinToString(" ")} failed (exit ${proc.exitValue()}): ${stderr.toString().trim()}")
        }
    }

    private fun drain(stream: InputStream, onLine: (String) -> Unit): Thread =
        Thread { stream.bufferedReader().forEachLine(onLine) }.apply { isDaemon = true; start() }

    /** Best-effort: staging is a fresh temp directory this class alone owns, so a failure to
     * remove it (a lingering file lock, e.g.) must never mask the real export/import outcome. */
    private fun deleteRecursively(dir: Path) {
        runCatching { dir.toFile().deleteRecursively() }
    }

    private companion object {
        const val MANIFEST_MEMBER = "checkpoint.json"
        const val ARTIFACT_MEMBER = "artifact"
        const val ARCHIVE_VERSION = 1
        // msb's snapshot payload is a sparse-but-potentially-large disk image and a docker save
        // tar can be a full image — generous but bounded, same order of magnitude as
        // MsbCliBackend's own SNAPSHOT_TIMEOUT_SEC.
        const val TAR_TIMEOUT_SEC = 300L
    }
}
