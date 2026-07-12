package dev.rightsize.msb

import dev.rightsize.GenericContainer
import dev.rightsize.RunId
import dev.rightsize.core.CacheDir
import dev.rightsize.core.wait.Wait
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Helper child process for [ReaperSigkillIT]'s SIGKILL end-to-end test — spawned as a plain
 * `java` main from the test classpath (this repo's language-idiomatic "helper child process",
 * per the reaping spec's integration-test guidance). Starts one real msb sandbox, signals
 * readiness by writing its name/run-id/cache-dir to the marker file at `args[0]`, then blocks
 * forever so the parent test can SIGKILL it at will and observe the watchdog reap the sandbox.
 *
 * Never stops the container itself — that's the entire point: this process's only cleanup
 * path is the reaper watchdog reacting to this process's death, not normal `stop()` lifecycle.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: ReaperSigkillHelperMainKt <marker-file>" }
    val marker = Path.of(args[0])

    val c = GenericContainer("alpine:3.19").withCommand("sleep", "300")
        .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(60)))
    c.start()

    Files.writeString(marker, listOf(RunId.value, CacheDir.resolve().toString()).joinToString("\n"))

    // Block forever; the parent SIGKILLs this process once it has read the marker file. No
    // shutdown hook runs on SIGKILL, so the container is deliberately left for the reaper
    // watchdog to find and remove.
    Thread.sleep(Long.MAX_VALUE)
}
