package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node Cassandra container.
 *
 * ### Defaults to `cassandra:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with Cassandra's own releases instead of this library's
 * release cycle. Every fact below — the `GPG_KEYS` bug, the memory ladder, the readiness log line
 * and its timing — was verified against `cassandra:5.0.8` specifically; pass that image
 * explicitly to pin it: `CassandraContainer("cassandra:5.0.8")`. The `GPG_KEYS` override is kept
 * unconditionally on the floating default too — whether a future Cassandra release still bakes a
 * tab into that value has not been re-verified, so this module does not gate the override on the
 * image chosen.
 *
 * ### `GPG_KEYS` must be overridden to a tab-free value — the difference between booting and aborting
 *
 * `cassandra:5.0.8`'s baked env includes a `GPG_KEYS` value that contains a literal TAB
 * character. Under msb 0.6.6, and still under the pinned 0.6.8, booting any image whose baked env
 * contains a TAB aborts before the
 * guest is even reachable:
 *
 * ```
 * sandbox process exited (signal: 6 (SIGABRT)) before agent relay became available
 * ```
 *
 * with `msb logs --source system` showing the root cause:
 *
 * ```
 * panicked at msb_krun_vmm-0.1.25/src/builder.rs:1154: ... Err value: InvalidAscii
 * ```
 *
 * This is not specific to Cassandra's own behavior — it is msb's env-encoding step rejecting a
 * TAB anywhere in the image's baked env, before Cassandra ever runs. `withEnv("GPG_KEYS", "")` in
 * this module overrides the baked value with an empty, tab-free one. `GPG_KEYS` is consumed only
 * at image build time (verifying the Apache download's signing keys), so overriding it here has
 * no effect on anything Cassandra does at runtime. Verified directly: an otherwise identical `msb
 * run` aborts with the signature above without this override and boots cleanly with it.
 *
 * ### Memory — a heap-bounded JVM, ladder verified at 2560 MB
 *
 * `MAX_HEAP_SIZE=512M`/`HEAP_NEWSIZE=128M` keep the JVM heap itself small; the container's total
 * footprint still needs headroom above that for the JVM's non-heap regions and the rest of the
 * process. 2560 MB was verified stable for a full boot-to-ready cycle.
 *
 * ### Readiness — log line, not a port probe; 300s startup timeout
 *
 * `Starting listening for CQL clients` is logged once the CQL native protocol server is actually
 * serving. Observed at 58s on a quiet local machine; the house precedent for a generous ceiling
 * is 180s (Keycloak, MySQL), and a single-node Cassandra JVM is heavier than either of those, so
 * this module's startup timeout is set to 300s rather than reusing the 180s figure.
 *
 * ### Round-trip verified with the bundled `cqlsh`, no driver
 *
 * A `cqlsh -e "..."` round-trip — `CREATE KEYSPACE` → `CREATE TABLE` → `INSERT` → `SELECT` —
 * returned the inserted row, run through `exec` on the started container using the `cqlsh` binary
 * the image already bundles. This module and its tests pull in no Cassandra driver dependency.
 */
class CassandraContainer(image: DockerImageName) : GenericContainer<CassandraContainer>(image.toString()) {
    /** Defaults to `cassandra:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "cassandra:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(CQL_PORT)
        // Baked GPG_KEYS contains a TAB; msb SIGABRTs on any TAB in an image's baked env (0.6.6 and 0.6.8 both)
        // before the guest is reachable. See the class doc for the exact panic signature.
        withEnv("GPG_KEYS", "")
        withEnv("MAX_HEAP_SIZE", "512M")
        withEnv("HEAP_NEWSIZE", "128M")
        withMemoryLimit(2560)
        waitingFor(Wait.forLogMessage(".*Starting listening for CQL clients.*", 1)
            .withStartupTimeout(Duration.ofSeconds(300)))
    }

    /** The CQL native protocol contact point, `<host>:<port>`. */
    val contactPoint: String get() = "$host:${getMappedPort(CQL_PORT)}"
    /** The mapped CQL native protocol port. */
    val cqlPort: Int get() = getMappedPort(CQL_PORT)
    /** The single node's datacenter name (`datacenter1`, this image's default). */
    val localDatacenter: String get() = "datacenter1"

    private companion object {
        const val CQL_PORT = 9042
        const val EXPECTED_REPOSITORY = "cassandra"
    }
}
