package dev.rightsize.msb

import dev.rightsize.RunId
import dev.rightsize.core.CacheDir
import dev.rightsize.core.CheckpointCopiedFileMissingException
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.FileMount
import dev.rightsize.core.PortBinding
import dev.rightsize.core.TmpfsRootCheckpointException
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fake-`msb`-binary unit coverage for [MsbCliBackend.createCheckpoint] (see docs/checkpoints.md)
 * — the stop -> snapshot create -> rm -> msb-restore-from-snapshot cycle, and its three typed-failure
 * paths, driven entirely against a local stub script rather than a real msb binary or sandbox
 * (same pattern as [MsbCliBackendTest]'s `fakeMsbLifecycle`). POSIX-only; the msb-windows CI
 * lane's own `sandbox-it` integration test is what exercises this against the real binary on
 * Windows.
 */
class MsbCheckpointTest {

    /**
     * Extends the lifecycle fake with `snapshot create`/`rm`/a `restore` re-boot/the workload
     * revival `exec` that follows it: `run`/`ls`/`stop` behave exactly as [MsbCliBackendTest]'s
     * own fake; `rm` is always a no-op success (its own failure is not one of
     * [MsbCliBackend.createCheckpoint]'s typed-failure shapes); `snapshot create` exits non-zero
     * when [snapshotFailFlag] exists, otherwise prints a fake `Snapshot ID:` line followed by an
     * ABSOLUTE artifact path as its LAST stdout line — msb 0.7.1's own shape
     * (`<dest-dir-or-fixed-base>/<sandbox>/snap_<32-hex>`, using `--dest-dir` when the argv
     * carries one) — UNLESS [malformedCreateOutputFlag] exists, in which case it instead prints a
     * line that is not a parseable absolute path at all, to drive
     * [MsbCliBackend.createCheckpoint]'s defensive-parsing failure mode; a `restore` invocation
     * (the re-boot, as opposed to the initial ordinary `run` boot) exits non-zero with no marker
     * written when [rebootFailFlag] exists — otherwise, matching msb's own detached-restore
     * contract (see [MsbCliBackend.awaitRestoreRunning]'s doc), it recreates [marker] (so the
     * sandbox reports Running on the next `ls`) and THEN exits 0 immediately, never blocking the
     * way the ordinary `run` case above does — [MsbCliBackend] must wait for this short-lived
     * process to exit and poll `ls` separately, not treat it as a supervising child. `exec` — the
     * workload-revival session [MsbCliBackend.spawnWorkloadExecChild] spawns once THAT restore
     * reaches Running — blocks on the SAME [marker] `run`/`restore` themselves populate, so
     * `stop`'s existing `rm -f "$marker"` wakes it too, instantly, with no separate teardown
     * plumbing needed, then it exits 0. Every invocation's full argv is appended to [callLog], one
     * line per call, so the tests below can assert on the order and shape of the commands
     * [createCheckpoint] actually drives instead of re-implementing msb's own CLI. Every spec
     * these tests boot carries an explicit `command`, so [MsbCliBackend.createCheckpoint]'s
     * pre-stop guest-capture step (only attempted when a spec has none) never fires here — its own
     * coverage lives in [MsbCheckpointCaptureTest].
     */
    private fun fakeMsbCheckpointLifecycle(
        marker: Path,
        callLog: Path,
        snapshotFailFlag: Path,
        rebootFailFlag: Path,
        malformedCreateOutputFlag: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-checkpoint", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |echo "${'$'}*" >> "$callLog"
            |shift
            |case "${'$'}cmd" in
            |  run)
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    echo "${'$'}name" > "$marker"
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  restore)
            |    snapshot="${'$'}1"; shift
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    if [ -n "${'$'}snapshot" ] && [ -f "$rebootFailFlag" ]; then
            |      echo "reboot from snapshot failed" 1>&2
            |      exit 1
            |    fi
            |    echo "${'$'}name" > "$marker"
            |    exit 0
            |    ;;
            |  exec)
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --) shift; break ;;
            |        *) shift ;;
            |      esac
            |    done
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  ls)
            |    if [ -f "$marker" ]; then
            |      n=${'$'}(cat "$marker")
            |      echo "[{\"name\":\"${'$'}n\",\"status\":\"Running\"}]"
            |    else
            |      echo "[]"
            |    fi
            |    ;;
            |  stop) rm -f "$marker"; exit 0 ;;
            |  rm) exit 0 ;;
            |  snapshot)
            |    if [ "${'$'}1" = "create" ]; then
            |      if [ -f "$snapshotFailFlag" ]; then echo "snapshot create failed" 1>&2; exit 1; fi
            |      if [ -f "$malformedCreateOutputFlag" ]; then echo "not an absolute path"; exit 0; fi
            |      shift 2   # drop "create" "--from-sandbox"
            |      sandbox="${'$'}1"; shift
            |      shift     # drop <name>
            |      destdir="/fake-msb-store"
            |      while [ "${'$'}#" -gt 0 ]; do
            |        case "${'$'}1" in
            |          --dest-dir) destdir="${'$'}2"; shift 2 ;;
            |          *) shift ;;
            |        esac
            |      done
            |      echo "Snapshot ID: fake0000-0000-0000-0000-000000000000"
            |      echo "${'$'}destdir/${'$'}sandbox/snap_0123456789abcdef0123456789abcdef"
            |      exit 0
            |    fi
            |    exit 0
            |    ;;
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    /** Matches the fixed fake artifact path [fakeMsbCheckpointLifecycle]'s `snapshot create`
     * case prints — `<destDir-or-/fake-msb-store>/<sandbox>/snap_<32-hex>` — so tests can assert
     * on the EFFECTIVE ref [MsbCliBackend.createCheckpoint] returns without re-deriving it. */
    private fun fakeEffectiveRef(sandbox: String, destDir: String = "/fake-msb-store"): String =
        "$destDir/$sandbox/snap_0123456789abcdef0123456789abcdef"

    /** [callLog]'s lines minus `msb ls` polling calls — those interleave with every boot's
     * readiness wait and aren't part of the stop/snapshot-create/rm/run sequence these tests
     * pin down. */
    private fun nonPollingCalls(callLog: Path): List<String> =
        Files.readAllLines(callLog).filterNot { it == "ls --format json" }

    private fun unsetFlag(prefix: String): Path = Files.createTempFile(prefix, "").also { Files.deleteIfExists(it) }

    @Test fun `createCheckpoint drives exactly stop, snapshot create, rm, then an msb restore re-boot under a fresh name keeping ports but dropping env`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val spec = ContainerSpec(
            name = "rz-ckpt-test", image = "irrelevant", runId = "run1",
            env = mapOf("FOO" to "bar"),
            ports = listOf(PortBinding(hostPort = 23456, guestPort = 80)),
            command = listOf("serve"),
        )
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            assertTrue("rz-ckpt-test" in backend.runningSandboxNames())
            Files.writeString(callLog, "")   // only createCheckpoint's own calls matter below

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id   // rewritten in place by createCheckpoint's own reboot

            assertEquals(fakeEffectiveRef("rz-ckpt-test"), effectiveRef,
                "createCheckpoint must return the artifact path captured from snapshot create's stdout, not the input ref")
            assertNotEquals("rz-ckpt-test", freshName,
                "the reboot must mint a FRESH sandbox name, never reuse the original (see docs/checkpoints.md)")
            assertTrue(freshName.startsWith("rz-${RunId.value}-"),
                "the fresh name must come from the ordinary rz-<runid>-<n> generator, not a second naming scheme: $freshName")
            assertTrue(freshName in backend.runningSandboxNames(),
                "the sandbox must be Running again, under the fresh name, once createCheckpoint returns")
            assertFalse("rz-ckpt-test" in backend.runningSandboxNames(),
                "the original name must no longer be live once the reboot has happened under a fresh one")

            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-test",
                "snapshot create --from-sandbox rz-ckpt-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-test",
            ), calls.take(3))
            assertEquals(5, calls.size,
                "no extra commands beyond stop/snapshot-create/rm/restore/exec-revival: $calls")
            val reboot = calls[3]
            assertTrue(reboot.startsWith("restore $effectiveRef --name $freshName"),
                "unexpected re-boot argv: $reboot")
            assertFalse("--disk-only" in reboot,
                "msb 0.7.1 rejects --disk-only for a disk-scope snapshot — restore must never emit it: $reboot")
            assertTrue("-p 23456:80" in reboot, "re-boot must keep the original port mapping: $reboot")
            assertFalse("-e" in reboot.split(" "), "restore has no -e/--env flag — env must never be emitted: $reboot")
            assertFalse("FOO=bar" in reboot, "restore has no -e/--env flag — env must never be emitted: $reboot")
            assertFalse("irrelevant" in reboot, "the ordinary image arg must not appear on a restore: $reboot")

            // The workload-revival exec: upstream's restore boots the sandbox idle, so this is
            // what actually re-runs the checkpointed workload — WITH the env restore itself drops,
            // and targeting the FRESH name (handle.id was rewritten before this exec was spawned).
            val revival = calls[4]
            assertEquals("exec -e FOO=bar $freshName -- serve", revival,
                "the revival exec must carry the checkpoint's own env (which restore itself never " +
                    "gets to pass) and the spec's explicit command, against the fresh name: $revival")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // Copied files are start-time bind mounts, which a snapshot does not capture. The reboot passes
    // each one back as a --volume, from the same host path, so the file is there again.
    @Test fun `createCheckpoint's re-boot mounts every copied file again with --volume, in spec order, after -p`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val a = Files.writeString(tmp.resolve("a.conf"), "alpha")
        val b = Files.writeString(tmp.resolve("b.conf"), "beta")
        val spec = ContainerSpec(
            name = "rz-ckpt-mounts-test", image = "irrelevant", runId = "run1", command = listOf("serve"),
            ports = listOf(PortBinding(hostPort = 23457, guestPort = 80)),
            mounts = listOf(
                FileMount(a, "/etc/app/a.conf"),
                FileMount(b, "/etc/app/b.conf", readOnly = true),
            ),
        )
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")

            val calls = nonPollingCalls(callLog)
            assertEquals(5, calls.size, "no extra commands beyond stop/snapshot-create/rm/restore/exec-revival: $calls")
            val reboot = calls[3]
            assertEquals(
                "restore $effectiveRef --name ${handle.id} -p 23457:80 " +
                    "--volume $a:/etc/app/a.conf:rw,nodev --volume $b:/etc/app/b.conf:ro,nodev",
                reboot)
            assertFalse("--allow-missing-resources" in reboot, "a missing file must stop the restore: $reboot")
            assertFalse("--mount-file" in reboot, "restore takes a mount as --volume: $reboot")
            assertTrue(handle.id in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // A reboot that is refused anyway (anything a --volume cannot fix) keeps its typed cause, so the
    // refusal's type is not flattened into a message by the wrapper.
    @Test fun `createCheckpoint's wrapper around a failed re-boot keeps the cause`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val rebootFailFlag = Files.createTempFile("rz-rebootfail-", "")   // present => the restore re-boot fails
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(
            marker, callLog, unsetFlag("rz-snapfail-"), rebootFailFlag, unsetFlag("rz-malformed-")))
        val handle = backend.create(ContainerSpec(
            name = "rz-ckpt-cause-test", image = "irrelevant", runId = "run1", command = listOf("serve")))
        try {
            backend.start(handle)
            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            assertNotNull(e.cause, "the wrapper must chain the failure it wraps")
            assertTrue(e.cause!!.message!!.contains("reboot from snapshot failed"), "cause was ${e.cause}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // The reboot reads each copied file's host path again, so a missing one is refused before the
    // source sandbox is stopped, snapshotted or removed: no msb call at all reaches the fake.
    @Test fun `createCheckpoint throws CheckpointCopiedFileMissingException before any msb call when a copied file's host file is gone`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-mounts-guard-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-mounts-guard", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val present = Files.writeString(tmp.resolve("present.conf"), "here")
        val gone = tmp.resolve("gone.conf")   // never created
        val spec = ContainerSpec(
            name = "rz-ckpt-mounts-guard-test", image = "irrelevant", runId = "run1",
            mounts = listOf(FileMount(present, "/etc/present.conf"), FileMount(gone, "/etc/gone.conf")),
        )
        val handle = backend.create(spec)

        val e = assertThrows(CheckpointCopiedFileMissingException::class.java) {
            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
        }

        assertEquals("/etc/gone.conf", e.guestPath)
        assertEquals(gone, e.hostPath)
        assertEquals(
            "checkpoint() can't keep the copied file at /etc/gone.conf: its host file $gone no longer " +
                "exists. Copied files are not part of a checkpoint; on microsandbox, checkpoint() mounts " +
                "them again from their host files when it reboots the container.",
            e.message)
        assertEquals(emptyList<String>(), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "no stop, snapshot, rm, restore or guest-capture exec may run before the guard fires")
        assertEquals("rz-ckpt-mounts-guard-test", handle.id, "the handle must not be renamed")
    }

    @Test fun `checkpoint reboots under a fresh name different from the original, and a subsequent stop targets the new name`() {
        // Red-proof (a): the restore argv never names the original sandbox, and every operation
        // against the handle afterward — here, stop() — keeps working transparently against
        // whatever name the reboot actually used.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val originalName = "rz-ckpt-freshname-test"
        val spec = ContainerSpec(name = originalName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        backend.start(handle)
        try {
            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id

            assertNotEquals(originalName, freshName)
            val reboot = nonPollingCalls(callLog).first { it.startsWith("restore ") }
            assertTrue("--name $freshName" in reboot, "restore argv must target the fresh name: $reboot")
            assertFalse("--name $originalName" in reboot, "restore argv must never target the original name: $reboot")

            Files.writeString(callLog, "")
            backend.stop(handle)
            assertEquals(listOf("stop $freshName"), nonPollingCalls(callLog),
                "a subsequent stop() must target the fresh name the reboot actually used, not the original")
        } finally {
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint appends the fresh name to the reaper's run ledger before ever invoking restore`(
        @TempDir tmp: Path,
    ) {
        // Red-proof (b): the ledger append (Reaper.beforeCreate -> RunLedger.beforeSandboxCreate)
        // must land on disk BEFORE the restore subprocess is even spawned — the same
        // append-before-create discipline an ordinary GenericContainer.start() gets — not merely
        // by the time createCheckpoint() returns. The fake's own `restore)` case snapshots the
        // real ledger sandboxes file (RIGHTSIZE_CACHE_DIR/RIGHTSIZE_REAPER are pinned for this
        // module's test task — see build.gradle.kts) the instant it is invoked, which is the
        // ledger test seam this proves the ordering through.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val ledgerAtRestoreFile = tmp.resolve("ledger-at-restore.txt")
        val sandboxesFile = CacheDir.resolve().resolve("runs").resolve("${RunId.value}.sandboxes")
        val script = Files.createTempFile("rz-fake-msb-ledger", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |echo "${'$'}*" >> "$callLog"
            |shift
            |case "${'$'}cmd" in
            |  run)
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --name) name="${'$'}2"; shift 2 ;; *) shift ;; esac
            |    done
            |    echo "${'$'}name" > "$marker"
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  restore)
            |    snapshot="${'$'}1"; shift
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --name) name="${'$'}2"; shift 2 ;; *) shift ;; esac
            |    done
            |    cat "$sandboxesFile" > "$ledgerAtRestoreFile" 2>/dev/null || : > "$ledgerAtRestoreFile"
            |    echo "${'$'}name" > "$marker"
            |    exit 0
            |    ;;
            |  exec)
            |    while [ "${'$'}#" -gt 0 ]; do case "${'$'}1" in --) shift; break ;; *) shift ;; esac; done
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  ls)
            |    if [ -f "$marker" ]; then
            |      n=${'$'}(cat "$marker")
            |      echo "[{\"name\":\"${'$'}n\",\"status\":\"Running\"}]"
            |    else
            |      echo "[]"
            |    fi
            |    ;;
            |  stop) rm -f "$marker"; exit 0 ;;
            |  rm) exit 0 ;;
            |  snapshot)
            |    if [ "${'$'}1" = "create" ]; then
            |      shift 2; sandbox="${'$'}1"; shift; shift
            |      destdir="/fake-msb-store"
            |      while [ "${'$'}#" -gt 0 ]; do
            |        case "${'$'}1" in --dest-dir) destdir="${'$'}2"; shift 2 ;; *) shift ;; esac
            |      done
            |      echo "Snapshot ID: fake0000-0000-0000-0000-000000000000"
            |      echo "${'$'}destdir/${'$'}sandbox/snap_0123456789abcdef0123456789abcdef"
            |      exit 0
            |    fi
            |    exit 0
            |    ;;
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)

        val backend = MsbCliBackend(script)
        val spec = ContainerSpec(name = "rz-ckpt-ledger-test", image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id

            assertTrue(Files.exists(ledgerAtRestoreFile),
                "the restore call must have run (and snapshotted the ledger) for this file to exist")
            val ledgerAtRestoreTime = Files.readAllLines(ledgerAtRestoreFile)
            assertTrue(freshName in ledgerAtRestoreTime,
                "the fresh name must already be in the reaper ledger's sandboxes file by the moment restore " +
                    "is invoked — append-before-create, the same discipline an ordinary create uses: " +
                    ledgerAtRestoreTime)
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint leaves the sandbox stopped and issues no rm or reboot when snapshot create fails`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = Files.createTempFile("rz-snapfail-", "")   // present => snapshot create fails
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val spec = ContainerSpec(name = "rz-ckpt-fail-test", image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            assertTrue(e.message!!.contains("snapshot create"), "message: ${e.message}")
            assertTrue(e.message!!.contains("left") && e.message!!.contains("stopped"),
                "message must say the sandbox is left stopped: ${e.message}")
            assertTrue(e.message!!.contains("msb start rz-ckpt-fail-test"),
                "message must name the by-hand remedy: ${e.message}")

            assertFalse("rz-ckpt-fail-test" in backend.runningSandboxNames(),
                "a failed snapshot step must leave the sandbox stopped, never best-effort resumed")
            assertEquals(listOf(
                "stop rz-ckpt-fail-test",
                "snapshot create --from-sandbox rz-ckpt-fail-test rz-ckpt-0123456789ab",
            ), nonPollingCalls(callLog), "a failed snapshot create must issue no rm and no re-boot")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint throws quoting the unparsed output when snapshot create succeeds but prints no absolute artifact path`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = Files.createTempFile("rz-malformed-", "")   // present => malformed create output
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val spec = ContainerSpec(name = "rz-ckpt-malformed-test", image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            assertTrue(e.message!!.contains("not an absolute path"), "message must quote the unparsed output: ${e.message}")
            assertTrue(e.message!!.contains("left") && e.message!!.contains("stopped"),
                "message must say the sandbox is left stopped: ${e.message}")
            assertTrue(e.message!!.contains("msb start rz-ckpt-malformed-test"),
                "message must name the by-hand remedy: ${e.message}")

            assertFalse("rz-ckpt-malformed-test" in backend.runningSandboxNames(),
                "an unparseable snapshot-create output must leave the sandbox stopped, never best-effort resumed")
            assertEquals(listOf(
                "stop rz-ckpt-malformed-test",
                "snapshot create --from-sandbox rz-ckpt-malformed-test rz-ckpt-0123456789ab",
            ), nonPollingCalls(callLog), "an unparseable snapshot-create output must issue no rm and no re-boot")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint throws naming the effective ref and fromCheckpoint recovery when the re-boot from a successful snapshot fails`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")   // snapshot succeeds
        val rebootFailFlag = Files.createTempFile("rz-rebootfail-", "")   // present => the restore re-boot fails
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val spec = ContainerSpec(name = "rz-ckpt-reboot-fail-test", image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = fakeEffectiveRef("rz-ckpt-reboot-fail-test")
            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            // handle.spec/id is rewritten to the fresh name BEFORE the reboot is even attempted
            // (see createCheckpoint's own doc), so this still reflects it even though the reboot failed.
            val freshName = handle.id
            assertNotEquals("rz-ckpt-reboot-fail-test", freshName,
                "the fresh name must have been minted and applied even though the reboot itself failed")
            assertTrue(e.message!!.contains(effectiveRef),
                "message must name the EFFECTIVE ref, not the input hint: ${e.message}")
            assertTrue(e.message!!.contains("fromCheckpoint"), "message must name the recovery path: ${e.message}")
            assertTrue(e.message!!.contains("reboot from snapshot failed"),
                "message must carry msb's stderr: ${e.message}")

            assertFalse("rz-ckpt-reboot-fail-test" in backend.runningSandboxNames(),
                "a failed re-boot must not be misreported as the sandbox running again")
            assertFalse(freshName in backend.runningSandboxNames(),
                "the fresh name must not be misreported as running either, when the reboot itself failed")
            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-reboot-fail-test",
                "snapshot create --from-sandbox rz-ckpt-reboot-fail-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-reboot-fail-test",
            ), calls.take(3), "the re-boot attempt must follow a successful snapshot and rm: $calls")
            assertTrue(calls[3].startsWith("restore $effectiveRef --name $freshName"),
                "unexpected re-boot argv: ${calls[3]}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `removeCheckpoint never throws even when the msb binary cannot be run`() {
        val backend = MsbCliBackend(Path.of("/nonexistent/msb"))
        assertDoesNotThrow { backend.removeCheckpoint("rz-ckpt-0123456789ab") }
    }

    @Test fun `createCheckpoint throws TmpfsRootCheckpointException before touching msb when the spec uses a tmpfs root`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-tmpfs-guard-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-tmpfs-guard", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val spec = ContainerSpec(name = "rz-ckpt-tmpfs-test", image = "irrelevant", runId = "run1", tmpfsRootMb = 256)
        val handle = backend.create(spec)

        assertThrows(TmpfsRootCheckpointException::class.java) {
            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
        }
        assertEquals(emptyList<String>(), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "no msb command may run before the tmpfs-root guard fires, not even stop")
    }

    @Test fun `createCheckpoint against a path ref creates the parent dir and passes --dest-dir with the basename as the snapshot name, but returns the captured artifact path`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = unsetFlag("rz-snapfail-")
        val rebootFailFlag = unsetFlag("rz-rebootfail-")
        val malformedFlag = unsetFlag("rz-malformed-")
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag, malformedFlag))
        val spec = ContainerSpec(name = "rz-ckpt-path-test", image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        val destDir = tmp.resolve("checkpoints")
        val refHint = destDir.resolve("rz-ckpt-0123456789ab").toString()
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = backend.createCheckpoint(handle, refHint)
            val freshName = handle.id

            assertTrue(Files.isDirectory(destDir), "createCheckpoint must create the ref hint's parent dir")
            val expectedRef = fakeEffectiveRef("rz-ckpt-path-test", destDir.toString())
            assertEquals(expectedRef, effectiveRef,
                "the returned ref must be the artifact path msb's stdout reported, nested under --dest-dir, " +
                    "not the input hint verbatim")
            val calls = nonPollingCalls(callLog)
            assertEquals(
                "snapshot create --from-sandbox rz-ckpt-path-test rz-ckpt-0123456789ab --dest-dir $destDir",
                calls[1],
            )
            assertTrue(calls[3].startsWith("restore $expectedRef --name $freshName"),
                "re-boot must use the CAPTURED artifact path, not the original --dest-dir ref hint, as restore's " +
                    "positional, and the fresh name (not the original) as --name: ${calls[3]}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `stripWindowsVerbatimPrefix drops the extended-length prefix msb 0_8 prints on Windows`() {
        assertEquals("C:\\Users\\r\\checkpoints\\rz-ckpt-x\\snap_ab",
            stripWindowsVerbatimPrefix("\\\\?\\C:\\Users\\r\\checkpoints\\rz-ckpt-x\\snap_ab"))
        assertEquals("\\\\server\\share\\ck", stripWindowsVerbatimPrefix("\\\\?\\UNC\\server\\share\\ck"))
        assertEquals("/cache/checkpoints/rz-ckpt-x/snap_ab", stripWindowsVerbatimPrefix("/cache/checkpoints/rz-ckpt-x/snap_ab"))
        assertEquals("C:\\ck\\snap_ab", stripWindowsVerbatimPrefix("C:\\ck\\snap_ab"))
    }

    // --- parseSnapshotCreateArtifactPath: pure, no binary needed ---
    //
    // Absolute-path fixtures are built via toAbsolutePath() rather than hand-typed Unix strings
    // (e.g. "/home/x/...") — a leading-slash string is NOT absolute on Windows' Path
    // implementation (it lacks a drive letter), so a hardcoded Unix path would make these tests
    // platform-dependent for no reason; building the fixture through the real Path API keeps
    // them correct on every OS the msb-windows CI lane also runs this module's tests on.

    @Test fun `parseSnapshotCreateArtifactPath reads the last non-blank line when it is an absolute path`() {
        val absolute = Path.of("rz-abc-1", "snap_0123456789abcdef0123456789abcdef").toAbsolutePath().normalize()
        val output = "Snapshot ID: abc123\n$absolute"
        assertEquals(absolute.toString(), parseSnapshotCreateArtifactPath(output))
    }

    @Test fun `parseSnapshotCreateArtifactPath ignores trailing blank lines`() {
        val absolute = Path.of("rz-abc-1", "snap_0123456789abcdef0123456789abcdef").toAbsolutePath().normalize()
        val output = "$absolute\n\n\n"
        assertEquals(absolute.toString(), parseSnapshotCreateArtifactPath(output))
    }

    @Test fun `parseSnapshotCreateArtifactPath returns null for blank output`() {
        assertNull(parseSnapshotCreateArtifactPath(""))
        assertNull(parseSnapshotCreateArtifactPath("\n\n"))
    }

    @Test fun `parseSnapshotCreateArtifactPath returns null when the last line is not an absolute path`() {
        val relative = Path.of("relative", "snap_0123456789abcdef0123456789abcdef")
        assertNull(parseSnapshotCreateArtifactPath("Snapshot ID: abc123\n$relative"))
        assertNull(parseSnapshotCreateArtifactPath("Snapshot ID: abc123\nsnap_0123456789abcdef0123456789abcdef"))
        assertNull(parseSnapshotCreateArtifactPath("not a path at all, just words"))
    }

    // --- parseNulSeparatedCmdline: pure, no binary needed ---

    @Test fun `parseNulSeparatedCmdline splits on NUL and drops the trailing empty token`() {
        assertEquals(listOf("nginx", "-g", "daemon off;"), parseNulSeparatedCmdline("nginx\u0000-g\u0000daemon off;\u0000"))
    }

    @Test fun `parseNulSeparatedCmdline tolerates the trailing newline MsbCliBackend invoke's line draining appends`() {
        // invoke() drains the guest's raw NUL-separated bytes (no trailing newline of their own)
        // through appendLine, which adds one — captureWorkloadCmdline's real input shape.
        assertEquals(listOf("nginx", "-g", "daemon off;"), parseNulSeparatedCmdline("nginx\u0000-g\u0000daemon off;\u0000\n"))
    }

    @Test fun `parseNulSeparatedCmdline returns an empty list for blank or unparseable input`() {
        assertEquals(emptyList<String>(), parseNulSeparatedCmdline(""))
        assertEquals(emptyList<String>(), parseNulSeparatedCmdline("\n"))
    }

    // --- isRestoreAccessDenied: pure, no binary needed ---

    @Test fun `isRestoreAccessDenied matches msb's Windows deferred-file-release errno`() {
        assertTrue(isRestoreAccessDenied("error: io error: Access is denied. (os error 5)"))
    }

    @Test fun `isRestoreAccessDenied requires both the access-denied phrase and the errno marker`() {
        assertFalse(isRestoreAccessDenied("Access is denied."), "the errno-suffix marker alone is missing")
        assertFalse(isRestoreAccessDenied("error: io error: something else entirely"), "the access-denied phrase is missing")
        assertFalse(isRestoreAccessDenied("error: permission denied"), "an unrelated denial must not match")
    }

    // --- isRestoreMissingBindings: pure, no binary needed ---

    // msb 0.7.8, verbatim.
    private val refusal078 = "error: invalid config: restore requires destination bindings for: " +
        "mount /etc/app/b.conf; provide a destination mount for each path or explicitly allow " +
        "missing resources\n  \u2192 map each path with -v SOURCE:GUEST or --mount-disk SOURCE:GUEST\n" +
        "  \u2192 or pass --allow-missing-resources to start without them"

    @Test fun `isRestoreMissingBindings matches msb 0_7_8's refusal verbatim`() {
        assertTrue(isRestoreMissingBindings(refusal078))
    }

    @Test fun `isRestoreMissingBindings matches the sentence whatever follows it - the 0_7_6 tail, two paths, a disk entry`() {
        assertTrue(isRestoreMissingBindings(
            "restore requires destination bindings for: mount /srv/x; provide --volume mappings, select " +
                "captured disks with --volume GUEST, or explicitly use --allow-missing-resources"))
        assertTrue(isRestoreMissingBindings(
            "error: invalid config: restore requires destination bindings for: mount /etc/app/a.conf, " +
                "mount /etc/app/b.conf; provide a destination mount for each path or explicitly allow missing resources"))
        assertTrue(isRestoreMissingBindings(
            "invalid config: restore requires destination bindings for: disk /data; provide a destination " +
                "mount or select a captured disk for each path, or explicitly allow missing resources"))
    }

    @Test fun `isRestoreMissingBindings does not depend on the prefix, the hint lines, or the arrow`() {
        assertTrue(isRestoreMissingBindings("restore requires destination bindings for: mount /etc/a"))
        assertTrue(isRestoreMissingBindings(
            "msb.exe : error: invalid config: restore requires destination bindings for: mount /etc/app/b.conf; " +
                "provide a destination mount for each path\nAt line:1 char:1\n+ & 'C:\\msb.exe' restore x --name y\n" +
                "+ CategoryInfo          : NotSpecified: (error: invalid ...:String) [], RemoteException\n" +
                "+ FullyQualifiedErrorId : NativeCommandError"))
        // A console that wrapped the sentence at a space still matches.
        assertTrue(isRestoreMissingBindings("error: invalid config: restore requires\ndestination bindings\n for: mount /etc/a"))
    }

    @Test fun `isRestoreMissingBindings is false for every other restore failure`() {
        assertFalse(isRestoreMissingBindings(""))
        assertFalse(isRestoreMissingBindings("error: io error: Access is denied. (os error 5)"))
        assertFalse(isRestoreMissingBindings(
            "error: failed to start \"rz-731fdcdc-8\"\n  \u2192 other: sandbox process exited (exit code: 0) before agent relay became available"))
        assertFalse(isRestoreMissingBindings(
            "error: sandbox already exists: sandbox 'x' already exists; remove it, start the stopped sandbox, or recreate with .replace()"))
        assertFalse(isRestoreMissingBindings("error: snapshot not found: rz-ckpt-x"))
        assertFalse(isRestoreMissingBindings("error: invalid config: disk_only requires a full snapshot with checkpoint state"))
        assertFalse(isRestoreMissingBindings("restore requires a snapshot"))
        assertFalse(isRestoreMissingBindings("destination bindings for: mount /etc/a"))
    }

    /**
     * Fake `msb snapshot inspect <ref>`: exits 0 for `rz-ckpt-exists`; exits 1 with msb's own
     * miss wording on stderr for `rz-ckpt-missing`; exits 1 with unrelated stderr (a stand-in
     * for a corrupted state database, a permission failure, or any other probe breakage) for
     * anything else — proving [MsbCliBackend.hasCheckpoint] tells "genuinely gone" apart from
     * "probe failed" without a real snapshot store.
     */
    private fun fakeMsbInspect(): Path {
        val script = Files.createTempFile("rz-fake-msb-inspect", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"; shift
            |if [ "${'$'}cmd" = "snapshot" ] && [ "${'$'}1" = "inspect" ]; then
            |  case "${'$'}2" in
            |    rz-ckpt-exists) exit 0 ;;
            |    rz-ckpt-missing) echo "error: snapshot not found: ${'$'}2 at /path/to/.microsandbox/snapshots/${'$'}2" 1>&2; exit 1 ;;
            |    *) echo "error: database error: state database is corrupt" 1>&2; exit 1 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `hasCheckpoint returns true when msb snapshot inspect exits 0`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        assertTrue(backend.hasCheckpoint("rz-ckpt-exists"))
    }

    @Test fun `hasCheckpoint returns false only when stderr carries msb's snapshot-not-found wording`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        assertFalse(backend.hasCheckpoint("rz-ckpt-missing"))
    }

    @Test fun `hasCheckpoint throws carrying stderr when inspect fails without the miss wording`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        val e = assertThrows(IllegalStateException::class.java) {
            backend.hasCheckpoint("rz-ckpt-other")
        }
        assertTrue(e.message!!.contains("state database is corrupt"), "message must carry msb's stderr: ${e.message}")
        assertTrue(e.message!!.contains("rz-ckpt-other"), "message must name the ref: ${e.message}")
    }

    @Test fun `hasCheckpoint treats msb 0_8 io-error no-such-file as a miss for a bare name`() {
        assertTrue(isCheckpointMiss("error: io error: No such file or directory (os error 2)"))
        assertTrue(isCheckpointMiss("error: snapshot not found: rz-ckpt-x"))
        assertTrue(isCheckpointMiss("error: io error: The system cannot find the file specified. (os error 2)"),
            "Windows localizes the OS text between `io error` and the errno")
        assertFalse(isCheckpointMiss("error: io error: Permission denied (os error 13)"))
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val script = Files.createTempFile("rz-fake-msb-hascp-enoent", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"error: io error: No such file or directory (os error 2)\" 1>&2\nexit 1\n")
            it.toFile().setExecutable(true)
        }
        assertFalse(MsbCliBackend(script).hasCheckpoint("rz-ckpt-nope"))
    }

    // msb 0.8's `snapshot inspect` takes a group, so a member path is refused even for a live
    // checkpoint. An absolute ref is therefore answered from the filesystem, with no msb call.

    @Test fun `hasCheckpoint for a path ref is true for a live member dir and never calls msb`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-hascp-path-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-hascp-path", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\necho boom 1>&2\nexit 1\n")
            it.toFile().setExecutable(true)
        }
        val artifact = tmp.resolve("checkpoints").resolve("rz-ckpt-test").resolve("snap_0123456789abcdef0123456789abcdef")
        Files.createDirectories(artifact)
        Files.writeString(artifact.resolve("snapshot.json"), "{}")

        assertTrue(MsbCliBackend(script).hasCheckpoint(artifact.toString()))
        assertEquals(emptyList<String>(), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "an absolute ref is a filesystem check; msb must not be spawned")
    }

    @Test fun `hasCheckpoint for a path ref is false for a missing member dir or one without snapshot json`(
        @TempDir tmp: Path,
    ) {
        val backend = MsbCliBackend(Path.of("/nonexistent/msb"))
        val missing = tmp.resolve("rz-ckpt-test").resolve("snap_missing00000000000000000000")
        assertFalse(backend.hasCheckpoint(missing.toString()))
        Files.createDirectories(missing)
        assertFalse(backend.hasCheckpoint(missing.toString()), "a member dir without snapshot.json is not a checkpoint")
    }

    /** Fake msb that records each argv to [callLog] and exits 0 without touching the disk. */
    private fun recordingFakeMsb(callLog: Path, exitCode: Int = 0): Path =
        Files.createTempFile("rz-fake-msb-rm", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\nexit $exitCode\n")
            it.toFile().setExecutable(true)
        }

    private fun member(group: Path, id: String): Path {
        val dir = group.resolve("snap_$id")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("snapshot.json"), "{}")
        return dir
    }

    @Test fun `removeCheckpoint of the only member runs snapshot rm --all on the group dir`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-calllog-", "")
        val group = tmp.resolve("rz-ckpt-one")
        val artifact = member(group, "0123456789abcdef0123456789abcdef")
        Files.writeString(group.resolve("group.json"), "{}")

        MsbCliBackend(recordingFakeMsb(callLog)).removeCheckpoint(artifact.toString())

        assertEquals(listOf("snapshot rm $group --all"), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "a single-member group must be removed whole, group.json included")
    }

    @Test fun `removeCheckpoint with a sibling member runs snapshot rm --checkpoint member -f and keeps the sibling`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-calllog-", "")
        val group = tmp.resolve("some-sandbox")
        val artifact = member(group, "0123456789abcdef0123456789abcdef")
        val sibling = member(group, "fedcba9876543210fedcba9876543210")

        // msb exits 1 (stands in for any failure), so the manual fallback runs: member only.
        MsbCliBackend(recordingFakeMsb(callLog, exitCode = 1)).removeCheckpoint(artifact.toString())

        assertEquals(
            listOf("snapshot rm $group --checkpoint snap_0123456789abcdef0123456789abcdef -f"),
            Files.readAllLines(callLog).filter { it.isNotBlank() })
        assertFalse(Files.exists(artifact), "the named member's leftover dir is cleared by hand")
        assertTrue(Files.exists(sibling.resolve("snapshot.json")), "a member the caller did not name must survive")
    }

    @Test fun `removeCheckpoint falls back to deleting the whole group dir when msb fails on a rz-ckpt single-member group`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-calllog-", "")
        val group = tmp.resolve("rz-ckpt-fallback")
        val artifact = member(group, "0123456789abcdef0123456789abcdef")
        Files.writeString(group.resolve("group.json"), "{}")

        MsbCliBackend(recordingFakeMsb(callLog, exitCode = 1)).removeCheckpoint(artifact.toString())

        assertFalse(Files.exists(group), "the whole leftover group dir must go, group.json included")
    }

    @Test fun `removeCheckpoint of a missing group counts as gone and calls no msb`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-calllog-", "")
        val artifact = tmp.resolve("rz-ckpt-gone").resolve("snap_0123456789abcdef0123456789abcdef")

        assertDoesNotThrow { MsbCliBackend(recordingFakeMsb(callLog)).removeCheckpoint(artifact.toString()) }

        assertEquals(emptyList<String>(), Files.readAllLines(callLog).filter { it.isNotBlank() })
    }

    @Test fun `removeCheckpoint never recursively deletes a path ref that is not a checkpoint artifact dir`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-guard-calllog-", "")
        val backend = MsbCliBackend(recordingFakeMsb(callLog, exitCode = 1))

        // No snapshot.json at all: not a checkpoint artifact dir, whatever its basename.
        val noSnapshotJson = tmp.resolve("rz-ckpt-group").resolve("snap_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        Files.createDirectories(noSnapshotJson)
        Files.writeString(noSnapshotJson.resolve("some-other-file"), "not a checkpoint")
        backend.removeCheckpoint(noSnapshotJson.toString())
        assertTrue(Files.exists(noSnapshotJson), "a dir without snapshot.json must never be deleted")
        assertTrue(Files.exists(noSnapshotJson.resolve("some-other-file")), "its contents must be untouched")

        // snapshot.json present but the basename doesn't carry a recognized checkpoint-artifact
        // shape (neither the pre-0.7.1 rz-ckpt- prefix nor the snap_ prefix), in a group that
        // does not look like one this library made: still not this backend's artifact shape.
        val wrongBasename = tmp.resolve("not-a-checkpoint-dir")
        Files.createDirectories(wrongBasename)
        Files.writeString(wrongBasename.resolve("snapshot.json"), "{}")
        Files.writeString(wrongBasename.resolve("payload"), "arbitrary populated directory")
        backend.removeCheckpoint(wrongBasename.toString())
        assertTrue(Files.exists(wrongBasename), "a dir with the wrong basename shape must never be deleted")
        assertTrue(Files.exists(wrongBasename.resolve("payload")), "its contents must be untouched")
    }

    @Test fun `removeCheckpoint never deletes a single-member group dir that is not named rz-ckpt`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val group = tmp.resolve("some-sandbox")   // an earlier release's group, named after a sandbox
        val artifact = member(group, "abcdefabcdefabcdefabcdefabcdefab")
        Files.writeString(group.resolve("group.json"), "{}")

        MsbCliBackend(recordingFakeMsb(Files.createTempFile("rz-rm-calllog-", ""), exitCode = 1))
            .removeCheckpoint(artifact.toString())

        assertFalse(Files.exists(artifact), "the member dir itself is cleared")
        assertTrue(Files.exists(group.resolve("group.json")), "a group this library did not name stays")
    }
}
