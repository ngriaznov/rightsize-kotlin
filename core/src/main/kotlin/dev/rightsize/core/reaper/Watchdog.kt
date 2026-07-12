package dev.rightsize.core.reaper

import dev.rightsize.core.WatchdogCommands
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Spawns the per-run watchdog (see docs/reaping.md): a detached script that blocks reading
 * stdin until EOF — the owning process died, cleanly or not — then reaps that run's own
 * sandboxes/networks and deletes its ledger files. The script content is generic (parameterized
 * entirely by argv) and its filename is a hash of that content, so it's written once and reused
 * by every run whose library ships the identical script. Content-derived naming is what makes
 * the shared cache directory safe: the sibling rightsize libraries (Rust, TypeScript) and any
 * other version of this one write their own differently-named scripts, and nothing can ever
 * execute a script whose argv contract it doesn't match.
 *
 * Correctness hinges on the returned [OutputStream] (the write end of the child's stdin pipe):
 * the caller must keep it referenced for the life of the process and never explicitly close it
 * — natural process death, by any means including `SIGKILL`, closes the OS pipe automatically,
 * and that closure IS the EOF signal. The other half of the correctness story — this fd must
 * never leak into a later child the JVM spawns (a leaked write end means the watchdog never
 * sees EOF) — is covered by the JDK's own default behavior: `Process`-created pipe file
 * descriptors have been close-on-exec since JDK 7, so no extra plumbing is needed here.
 */
object Watchdog {

    private val POSIX_SCRIPT = """
        |#!/bin/sh
        |# rightsize reaper watchdog (POSIX) -- see docs/reaping.md. Generic and
        |# version-stable: every run passes its own paths/commands via argv, so this file's
        |# content never needs to change just because a run's cache dir or commands differ.
        |#   ${'$'}1 sandboxes-file  ${'$'}2 networks-file  ${'$'}3 record-json-file
        |#   ${'$'}4-${'$'}6  sandbox-stop command words (each may be "", meaning "skip this step")
        |#   ${'$'}7-${'$'}9  sandbox-remove command words
        |#   ${'$'}10-${'$'}12 network-remove command words
        |set -u
        |sandboxes_file=${'$'}1
        |networks_file=${'$'}2
        |record_file=${'$'}3
        |stop1=${'$'}4; stop2=${'$'}5; stop3=${'$'}6
        |rm1=${'$'}7; rm2=${'$'}8; rm3=${'$'}9
        |shift 9
        |netrm1=${'$'}1; netrm2=${'$'}2; netrm3=${'$'}3
        |
        |run_cmd() {
        |  w1=${'$'}1; w2=${'$'}2; w3=${'$'}3; name=${'$'}4
        |  [ -z "${'$'}w1" ] && return 0
        |  if [ -n "${'$'}w3" ]; then "${'$'}w1" "${'$'}w2" "${'$'}w3" "${'$'}name"
        |  elif [ -n "${'$'}w2" ]; then "${'$'}w1" "${'$'}w2" "${'$'}name"
        |  else "${'$'}w1" "${'$'}name"
        |  fi
        |}
        |
        |# Block until stdin hits EOF -- the process-death signal this whole mechanism is built on.
        |cat >/dev/null 2>&1 || true
        |
        |if [ -f "${'$'}sandboxes_file" ]; then
        |  while IFS= read -r name || [ -n "${'$'}name" ]; do
        |    [ -z "${'$'}name" ] && continue
        |    out=${'$'}(run_cmd "${'$'}stop1" "${'$'}stop2" "${'$'}stop3" "${'$'}name" 2>&1)
        |    out="${'$'}out${'$'}(run_cmd "${'$'}rm1" "${'$'}rm2" "${'$'}rm3" "${'$'}name" 2>&1)"
        |    case "${'$'}out" in
        |      *"error: database error:"*) run_cmd "${'$'}rm1" "${'$'}rm2" "${'$'}rm3" "${'$'}name" >/dev/null 2>&1 ;;
        |    esac
        |  done < "${'$'}sandboxes_file"
        |fi
        |
        |if [ -f "${'$'}networks_file" ]; then
        |  while IFS= read -r net || [ -n "${'$'}net" ]; do
        |    [ -z "${'$'}net" ] && continue
        |    run_cmd "${'$'}netrm1" "${'$'}netrm2" "${'$'}netrm3" "${'$'}net" >/dev/null 2>&1
        |  done < "${'$'}networks_file"
        |fi
        |
        |rm -f "${'$'}sandboxes_file" "${'$'}networks_file" "${'$'}record_file"
        |""".trimMargin("|")

    private val POWERSHELL_SCRIPT = """
        |# rightsize reaper watchdog (Windows) -- see docs/reaping.md. Same argv protocol as
        |# the POSIX script (watchdog.sh): positional, fixed-arity, generic/version-stable.
        |param(
        |  [string]${'$'}SandboxesFile, [string]${'$'}NetworksFile, [string]${'$'}RecordFile,
        |  [string]${'$'}Stop1 = "", [string]${'$'}Stop2 = "", [string]${'$'}Stop3 = "",
        |  [string]${'$'}Rm1 = "", [string]${'$'}Rm2 = "", [string]${'$'}Rm3 = "",
        |  [string]${'$'}NetRm1 = "", [string]${'$'}NetRm2 = "", [string]${'$'}NetRm3 = ""
        |)
        |function Invoke-RzCmd(${'$'}w1, ${'$'}w2, ${'$'}w3, ${'$'}name) {
        |  if ([string]::IsNullOrEmpty(${'$'}w1)) { return "" }
        |  ${'$'}cmdArgs = @()
        |  if (-not [string]::IsNullOrEmpty(${'$'}w2)) { ${'$'}cmdArgs += ${'$'}w2 }
        |  if (-not [string]::IsNullOrEmpty(${'$'}w3)) { ${'$'}cmdArgs += ${'$'}w3 }
        |  ${'$'}cmdArgs += ${'$'}name
        |  & ${'$'}w1 @cmdArgs 2>&1 | Out-String
        |}
        |# Block until stdin hits EOF -- the process-death signal.
        |[Console]::In.ReadToEnd() | Out-Null
        |if (Test-Path ${'$'}SandboxesFile) {
        |  Get-Content ${'$'}SandboxesFile | ForEach-Object {
        |    ${'$'}name = ${'$'}_.Trim()
        |    if (${'$'}name) {
        |      ${'$'}out = Invoke-RzCmd ${'$'}Stop1 ${'$'}Stop2 ${'$'}Stop3 ${'$'}name
        |      ${'$'}out += Invoke-RzCmd ${'$'}Rm1 ${'$'}Rm2 ${'$'}Rm3 ${'$'}name
        |      if (${'$'}out -match "error: database error:") { Invoke-RzCmd ${'$'}Rm1 ${'$'}Rm2 ${'$'}Rm3 ${'$'}name | Out-Null }
        |    }
        |  }
        |}
        |if (Test-Path ${'$'}NetworksFile) {
        |  Get-Content ${'$'}NetworksFile | ForEach-Object {
        |    ${'$'}net = ${'$'}_.Trim()
        |    if (${'$'}net) { Invoke-RzCmd ${'$'}NetRm1 ${'$'}NetRm2 ${'$'}NetRm3 ${'$'}net | Out-Null }
        |  }
        |}
        |Remove-Item -Force -ErrorAction SilentlyContinue ${'$'}SandboxesFile, ${'$'}NetworksFile, ${'$'}RecordFile
        |""".trimMargin("|")

    /**
     * Writes (idempotently, version-named) the watchdog script under `<cacheDir>/reaper/` and
     * spawns it detached, all other stdio redirected to null (CI runners hang on inherited
     * handles otherwise). Returns the write end of its stdin pipe — see the class doc for the
     * caller's obligation. [isWindows] defaults to the real host OS; overridable for tests.
     */
    fun spawn(
        cacheDir: Path,
        sandboxesFile: Path,
        networksFile: Path,
        recordFile: Path,
        commands: WatchdogCommands,
        isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
    ): OutputStream {
        val scriptDir = Files.createDirectories(cacheDir.resolve("reaper"))
        return if (isWindows) spawnPowerShell(scriptDir, sandboxesFile, networksFile, recordFile, commands)
        else spawnPosix(scriptDir, sandboxesFile, networksFile, recordFile, commands)
    }

    private fun spawnPosix(
        scriptDir: Path, sandboxesFile: Path, networksFile: Path, recordFile: Path, commands: WatchdogCommands,
    ): OutputStream {
        val script = scriptDir.resolve(scriptName(POSIX_SCRIPT, "sh"))
        writeIfAbsent(script, POSIX_SCRIPT, executable = true)
        val argv = listOf("sh", script.toString(), sandboxesFile.toString(), networksFile.toString(), recordFile.toString()) +
            padTo3(commands.sandboxStop) + padTo3(commands.sandboxRemove) + padTo3(commands.networkRemove)
        return start(argv)
    }

    private fun spawnPowerShell(
        scriptDir: Path, sandboxesFile: Path, networksFile: Path, recordFile: Path, commands: WatchdogCommands,
    ): OutputStream {
        val script = scriptDir.resolve(scriptName(POWERSHELL_SCRIPT, "ps1"))
        writeIfAbsent(script, POWERSHELL_SCRIPT, executable = false)
        val argv = listOf("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.toString(), sandboxesFile.toString(), networksFile.toString(), recordFile.toString()) +
            padTo3(commands.sandboxStop) + padTo3(commands.sandboxRemove) + padTo3(commands.networkRemove)
        return start(argv)
    }

    private fun start(argv: List<String>): OutputStream {
        val pb = ProcessBuilder(argv)
        pb.redirectOutput(ProcessBuilder.Redirect.to(NULL_DEVICE))
        pb.redirectError(ProcessBuilder.Redirect.to(NULL_DEVICE))
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        return pb.start().outputStream
    }

    private fun writeIfAbsent(script: Path, content: String, executable: Boolean) {
        if (Files.exists(script)) return
        val tmp = Files.createTempFile(script.parent, script.fileName.toString(), ".tmp")
        Files.writeString(tmp, content)
        if (executable) tmp.toFile().setExecutable(true, false)
        runCatching {
            Files.move(tmp, script, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Files.deleteIfExists(tmp) }   // another process won the race; its content is identical
    }

    /** Pads/truncates [words] to exactly 3 entries (empty string = "unused slot") — the
     * watchdog script's fixed-arity argv protocol; see [POSIX_SCRIPT]'s doc comment. */
    private fun padTo3(words: List<String>): List<String> = (words + listOf("", "", "")).take(3)

    /**
     * `watchdog-<12 hex of SHA-256(content)>.<ext>` — the content-derived name that makes the
     * shared `reaper/` directory collision-proof across rightsize's sibling libraries and
     * versions (see the object doc). Internal for tests.
     */
    internal fun scriptName(content: String, ext: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return "watchdog-" + digest.joinToString("") { "%02x".format(it) }.take(12) + ".$ext"
    }

    private val NULL_DEVICE = File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")
}
