package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node Neo4j Community container, queried over its HTTP Cypher transaction endpoint
 * (`/db/neo4j/tx/commit`) — no bolt driver dependency needed in either language, matching the
 * house convention for HTTP-first modules ([ClickHouseContainer], [PinotContainer]). The bolt
 * port (7687) is still exposed and its URI available via [boltUrl] for callers who do want a
 * real driver.
 *
 * Defaults to a `neo4j`/`rightsize-test` username/password pair (the image refuses passwords
 * under 8 characters — `neo4j`/`neo4j` is rejected at boot) so [httpUrl] plus basic auth is
 * usable with zero configuration; call [withPassword] before [start] to override it. The
 * username is fixed by the image at `neo4j` — there is no env var to change it.
 *
 * ### Readiness — `Started.` is the exact log line, verified against a real boot
 *
 * Captured verbatim from `neo4j:5-community`:
 *
 * ```
 * ... INFO  Bolt enabled on 0.0.0.0:7687.
 * ... INFO  HTTP enabled on 0.0.0.0:7474.
 * ... INFO  Remote interface available at http://localhost:7474/
 * ... INFO  Started.
 * ```
 *
 * `Started.` is logged only after both connectors are already listening, so it is both accurate
 * and simpler than a two-port HTTP/bolt race; pinned as the wait strategy over `forHttp`.
 *
 * ### Memory — measured, needed the ladder
 *
 * At msb's default ~450 MB microVM RAM, the server logs `ERROR Invalid memory configuration -
 * exceeds physical memory. Check the configured values for server.memory.pagecache.size and
 * server.memory.heap.max_size` and shuts itself down cleanly (`INFO Stopped.`) rather than
 * hanging or getting OOM-killed — Neo4j's own memory-recommendation calculator sizes the page
 * cache and heap off total visible RAM and refuses to start if the sums don't fit. A real docker
 * boot with no memory cap sits at ~468 MiB RSS (`docker stats`), just over that default budget.
 * Retried with `-m 1024M`: boots clean, the HTTP Cypher endpoint answers within the startup
 * timeout. `withMemoryLimit(1024)` is this module's default, same number as [KeycloakContainer]
 * and matching this family's established floor for a single JVM.
 *
 * No control characters were found in the image's baked env (checked via `docker image
 * inspect`), so no env override is needed here.
 */
class Neo4jContainer(image: String = "neo4j:5-community") : GenericContainer<Neo4jContainer>(image) {
    private var passwordState = "rightsize-test"

    init {
        withExposedPorts(HTTP_PORT, BOLT_PORT)
        withEnv("NEO4J_AUTH", authEnvValue())
        // Single JVM, boots clean at 1024M — see the class doc for the measured
        // default-memory refusal (Neo4j's own memory calculator, not an OOM kill) and the fix.
        withMemoryLimit(1024)
        waitingFor(Wait.forLogMessage(".*Started\\..*", 1).withStartupTimeout(Duration.ofSeconds(120)))
    }

    /** Overrides `NEO4J_AUTH`'s password half (default `rightsize-test`; the image requires ≥8 chars). */
    fun withPassword(password: String): Neo4jContainer {
        passwordState = password
        return withEnv("NEO4J_AUTH", authEnvValue())
    }

    private fun authEnvValue(): String = "$username/$passwordState"

    /** The fixed admin username (`neo4j` — the image has no env var to change it). */
    val username: String get() = "neo4j"
    /** The configured admin password (default `rightsize-test`). */
    val password: String get() = passwordState

    /** The HTTP interface's base URI (Cypher transactions via `POST {httpUrl}/db/neo4j/tx/commit`). */
    val httpUrl: String get() = "http://$host:${getMappedPort(HTTP_PORT)}"
    /** The bolt interface's URI, for callers using a real bolt driver instead of the HTTP helpers. */
    val boltUrl: String get() = "bolt://$host:${getMappedPort(BOLT_PORT)}"

    private companion object {
        const val HTTP_PORT = 7474
        const val BOLT_PORT = 7687
    }
}
