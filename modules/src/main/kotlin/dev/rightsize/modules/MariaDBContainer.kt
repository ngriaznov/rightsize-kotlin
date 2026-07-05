package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node MariaDB container. Defaults to a `test`/`test`/`test` user/password/database trio
 * (plus `MARIADB_ROOT_PASSWORD=test`) so [jdbcUrl] is usable with zero configuration; call
 * [withUsername]/[withPassword]/[withDatabase] before [start] to override any of them.
 *
 * ### Env var names — verified against a real boot
 *
 * `MARIADB_USER`/`MARIADB_PASSWORD`/`MARIADB_DATABASE`/`MARIADB_ROOT_PASSWORD` are the image's
 * documented names and were confirmed directly, not just assumed from the docs: a real
 * `jdbc:mariadb://` connection as `test`/`test` against the `test` database succeeds once the
 * readiness log line below appears, and fails (access denied) with a wrong password — same
 * evidence shape as [ClickHouseContainer]'s.
 *
 * ### Readiness — empirically pinned, following [MySQLContainer]'s precedent exactly
 *
 * The official `mariadb` entrypoint double-boots exactly like MySQL's: once as a throwaway
 * "temp server" to run init scripts (which prints `ready for connections` with `port: 0`, i.e. no
 * port bound yet), then for real on port 3306. Captured verbatim from a real
 * `docker run mariadb:11.4` boot with this module's env (`MARIADB_USER=test`,
 * `MARIADB_DATABASE=test`, `MARIADB_ROOT_PASSWORD=test`):
 *
 * ```
 * 2026-07-04  8:47:29 0 [Note] mariadbd: ready for connections.
 * Version: '11.4.12-MariaDB-ubu2404'  socket: '/run/mysqld/mysqld.sock'  port: 0  mariadb.org binary distribution
 * 2026-07-04  8:47:30 0 [Note] Server socket created on IP: '0.0.0.0', port: '3306'.
 * 2026-07-04  8:47:30 0 [Note] Server socket created on IP: '::', port: '3306'.
 * 2026-07-04  8:47:30 0 [Note] mariadbd: ready for connections.
 * Version: '11.4.12-MariaDB-ubu2404'  socket: '/run/mysqld/mysqld.sock'  port: 3306  mariadb.org binary distribution
 * ```
 *
 * Unlike MySQL 8.4, MariaDB has no X Plugin adding a third `ready for connections` line with a
 * decoy `3306`-prefixed port (`33060`), so there's no false-match trap to anchor against — but the
 * temp server's `port: 0` line still means an unanchored `times=2` count would be correct only by
 * coincidence (it happens to work here because there are exactly two `ready for connections`
 * lines total). This module follows [MySQLContainer]'s house precedent anyway and anchors the
 * regex on the literal `port: 3306` of the real server's line, so the wait is robust to the
 * temp-server line's exact wording even if a future MariaDB point release changes it.
 */
class MariaDBContainer(image: String = "mariadb:11.4") : GenericContainer<MariaDBContainer>(image) {
    private var usernameState = "test"
    private var passwordState = "test"
    private var databaseState = "test"

    init {
        withExposedPorts(3306)
        withEnv("MARIADB_USER", usernameState)
        withEnv("MARIADB_PASSWORD", passwordState)
        withEnv("MARIADB_DATABASE", databaseState)
        withEnv("MARIADB_ROOT_PASSWORD", "test")
        // Anchored on the real server's line (see the class doc for the captured log excerpt).
        // 120s: same double-boot entrypoint as MySQL (init pass, temporary server, real
        // server), which can exceed the default 60s on shared CI runners.
        waitingFor(Wait.forLogMessage(".*port: 3306.*mariadb\\.org binary distribution.*", 1)
            .withStartupTimeout(Duration.ofSeconds(120)))
        // No withMemoryLimit override: same InnoDB-footprint precedent as MySQL 8.4 — booted
        // clean on msb's default ~450M microVM RAM (observed ~14.8s IT round-trip on msb; no
        // memory-ladder escalation was needed).
    }

    /** Overrides `MARIADB_USER` (default `test`). */
    fun withUsername(username: String): MariaDBContainer {
        usernameState = username
        return withEnv("MARIADB_USER", username)
    }

    /** Overrides `MARIADB_PASSWORD` (default `test`). */
    fun withPassword(password: String): MariaDBContainer {
        passwordState = password
        return withEnv("MARIADB_PASSWORD", password)
    }

    /** Overrides `MARIADB_DATABASE` (default `test`). */
    fun withDatabase(database: String): MariaDBContainer {
        databaseState = database
        return withEnv("MARIADB_DATABASE", database)
    }

    /** The configured database user (default `test`). */
    val username: String get() = usernameState
    /** The configured database password (default `test`). */
    val password: String get() = passwordState
    /** The configured database name (default `test`). */
    val databaseName: String get() = databaseState

    /** A `jdbc:mariadb://` URL for the running container's [databaseName]. */
    val jdbcUrl: String get() = "jdbc:mariadb://$host:${getMappedPort(3306)}/$databaseName"
}
