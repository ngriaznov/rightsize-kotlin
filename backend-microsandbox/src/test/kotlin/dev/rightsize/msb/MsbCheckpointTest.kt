package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
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

    @Test fun `createCheckpoint drives exactly stop, snapshot create, rm, then an msb restore re-boot keeping ports but dropping env`() {
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

            assertEquals(fakeEffectiveRef("rz-ckpt-test"), effectiveRef,
                "createCheckpoint must return the artifact path captured from snapshot create's stdout, not the input ref")
            assertTrue("rz-ckpt-test" in backend.runningSandboxNames(),
                "the sandbox must be Running again once createCheckpoint returns")

            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-test",
                "snapshot create --from-sandbox rz-ckpt-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-test",
            ), calls.take(3))
            assertEquals(5, calls.size,
                "no extra commands beyond stop/snapshot-create/rm/restore/exec-revival: $calls")
            val reboot = calls[3]
            assertTrue(reboot.startsWith("restore $effectiveRef --name rz-ckpt-test"),
                "unexpected re-boot argv: $reboot")
            assertFalse("--disk-only" in reboot,
                "msb 0.7.1 rejects --disk-only for a disk-scope snapshot — restore must never emit it: $reboot")
            assertTrue("-p 23456:80" in reboot, "re-boot must keep the original port mapping: $reboot")
            assertFalse("-e" in reboot.split(" "), "restore has no -e/--env flag — env must never be emitted: $reboot")
            assertFalse("FOO=bar" in reboot, "restore has no -e/--env flag — env must never be emitted: $reboot")
            assertFalse("irrelevant" in reboot, "the ordinary image arg must not appear on a restore: $reboot")

            // The workload-revival exec: upstream's restore boots the sandbox idle, so this is
            // what actually re-runs the checkpointed workload — WITH the env restore itself drops.
            val revival = calls[4]
            assertEquals("exec -e FOO=bar rz-ckpt-test -- serve", revival,
                "the revival exec must carry the checkpoint's own env (which restore itself never " +
                    "gets to pass) and the spec's explicit command: $revival")
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
            assertTrue(e.message!!.contains(effectiveRef),
                "message must name the EFFECTIVE ref, not the input hint: ${e.message}")
            assertTrue(e.message!!.contains("fromCheckpoint"), "message must name the recovery path: ${e.message}")
            assertTrue(e.message!!.contains("reboot from snapshot failed"),
                "message must carry msb's stderr: ${e.message}")

            assertFalse("rz-ckpt-reboot-fail-test" in backend.runningSandboxNames(),
                "a failed re-boot must not be misreported as the sandbox running again")
            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-reboot-fail-test",
                "snapshot create --from-sandbox rz-ckpt-reboot-fail-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-reboot-fail-test",
            ), calls.take(3), "the re-boot attempt must follow a successful snapshot and rm: $calls")
            assertTrue(calls[3].startsWith("restore $effectiveRef --name rz-ckpt-reboot-fail-test"),
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

    @Test fun `removeCheckpoint never throws when msb refuses to remove a head snapshot`(
        @TempDir tmp: Path,
    ) {
        // msb 0.7.1: removing the NEWEST (head) snapshot of a source sandbox while an older
        // sibling still exists is refused ("invalid config: cannot remove current head ...;
        // first select another snapshot with 'msb snapshot head src:<snapshot>'"). removeCheckpoint
        // never inspects the exit code (best-effort, same contract as removeByName) — this pins
        // that the refusal is neither retried nor worked around with automatic head rotation, it
        // simply leaves the snapshot in place without throwing.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-headrefusal-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-headrefusal", "").also {
            Files.writeString(
                it,
                "#!/bin/sh\necho \"\$*\" >> \"$callLog\"\n" +
                    "echo \"invalid config: cannot remove current head snap_0123456789abcdef0123456789abcdef; " +
                    "first select another snapshot with 'msb snapshot head src:name'\" 1>&2\nexit 1\n",
            )
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val artifact = tmp.resolve("snap_0123456789abcdef0123456789abcdef")

        assertDoesNotThrow { backend.removeCheckpoint(artifact.toString()) }
        assertEquals(listOf("snapshot rm $artifact -f"), Files.readAllLines(callLog).filter { it.isNotBlank() })
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
            assertTrue(calls[3].startsWith("restore $expectedRef --name rz-ckpt-path-test"),
                "re-boot must use the CAPTURED artifact path, not the original --dest-dir ref hint, as restore's positional: ${calls[3]}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
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

    // As of msb 0.7.1, a path ref is no longer a pure filesystem check: `--dest-dir` snapshots
    // are tracked in msb's own index just like any other, and msb resolves `snapshot inspect`
    // reliably only against the artifact's own path — every ref this backend mints already IS
    // that path, so hasCheckpoint now calls msb for a path ref exactly like it always has for a
    // bare one (see MsbCliBackend.hasCheckpoint's doc).

    @Test fun `hasCheckpoint for a path ref calls msb snapshot inspect with the full path, not a filesystem check`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-hascp-path-calllog-", "")
        val artifact = tmp.resolve("checkpoints").resolve("rz-ckpt-test").resolve("snap_0123456789abcdef0123456789abcdef")
        val script = Files.createTempFile("rz-fake-msb-hascp-path", "").also {
            Files.writeString(
                it,
                """
                |#!/bin/sh
                |echo "${'$'}*" >> "$callLog"
                |if [ "${'$'}1" = "snapshot" ] && [ "${'$'}2" = "inspect" ] && [ "${'$'}3" = "$artifact" ]; then
                |  exit 0
                |fi
                |echo "error: snapshot not found: ${'$'}3" 1>&2
                |exit 1
                |""".trimMargin(),
            )
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)

        // No filesystem artifact exists at all — proving the answer comes from msb, not a
        // Files.exists/isDirectory check (the pre-0.7.1 shortcut this test replaces).
        assertFalse(Files.exists(artifact))
        assertTrue(backend.hasCheckpoint(artifact.toString()),
            "hasCheckpoint must call msb snapshot inspect with the full artifact path")
        assertEquals(listOf("snapshot inspect $artifact"), Files.readAllLines(callLog).filter { it.isNotBlank() })
    }

    @Test fun `hasCheckpoint for a path ref is false when msb snapshot inspect reports a miss`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val artifact = tmp.resolve("checkpoints").resolve("rz-ckpt-test").resolve("snap_missing00000000000000000000")
        val script = Files.createTempFile("rz-fake-msb-hascp-path-miss", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"error: snapshot not found: \$3\" 1>&2\nexit 1\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        // Even a real, existing artifact directory on disk must not short-circuit the answer.
        Files.createDirectories(artifact)
        Files.writeString(artifact.resolve("snapshot.json"), "{}")
        assertFalse(backend.hasCheckpoint(artifact.toString()))
    }

    @Test fun `removeCheckpoint for a path ref runs snapshot rm with the full path and -f, and clears a leftover artifact dir`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-rm", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val artifact = tmp.resolve("snap_0123456789abcdef0123456789abcdef")
        Files.createDirectories(artifact)
        Files.writeString(artifact.resolve("snapshot.json"), "{}")

        backend.removeCheckpoint(artifact.toString())

        assertEquals(listOf("snapshot rm $artifact -f"), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "msb snapshot rm must be called with the ref's full path and -f, never reduced to a basename")
        assertFalse(Files.exists(artifact), "a leftover artifact dir (index lost it) must be best-effort deleted")
    }

    @Test fun `removeCheckpoint never recursively deletes a path ref that is not a checkpoint artifact dir`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-rm-guard-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-rm-guard", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"${'$'}*\" >> \"$callLog\"\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)

        // No snapshot.json at all: not a checkpoint artifact dir, whatever its basename.
        val noSnapshotJson = tmp.resolve("rz-ckpt-arbitrary")
        Files.createDirectories(noSnapshotJson)
        Files.writeString(noSnapshotJson.resolve("some-other-file"), "not a checkpoint")
        backend.removeCheckpoint(noSnapshotJson.toString())
        assertTrue(Files.exists(noSnapshotJson), "a dir without snapshot.json must never be deleted")
        assertTrue(Files.exists(noSnapshotJson.resolve("some-other-file")), "its contents must be untouched")

        // snapshot.json present but the basename doesn't carry a recognized checkpoint-artifact
        // shape (neither the pre-0.7.1 rz-ckpt- prefix nor the msb-0.7.1 snap_ prefix): still
        // not this backend's artifact shape.
        val wrongBasename = tmp.resolve("not-a-checkpoint-dir")
        Files.createDirectories(wrongBasename)
        Files.writeString(wrongBasename.resolve("snapshot.json"), "{}")
        Files.writeString(wrongBasename.resolve("payload"), "arbitrary populated directory")
        backend.removeCheckpoint(wrongBasename.toString())
        assertTrue(Files.exists(wrongBasename), "a dir with the wrong basename shape must never be deleted")
        assertTrue(Files.exists(wrongBasename.resolve("payload")), "its contents must be untouched")
    }

    @Test fun `removeCheckpoint clears a leftover artifact dir with the msb-0-7-1 snap_ basename shape`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val script = Files.createTempFile("rz-fake-msb-rm-snapshape", "").also {
            Files.writeString(it, "#!/bin/sh\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val artifact = tmp.resolve("some-sandbox").resolve("snap_abcdefabcdefabcdefabcdefabcdefab")
        Files.createDirectories(artifact)
        Files.writeString(artifact.resolve("snapshot.json"), "{}")

        backend.removeCheckpoint(artifact.toString())

        assertFalse(Files.exists(artifact), "a leftover snap_<hex> artifact dir must be best-effort deleted")
    }
}
