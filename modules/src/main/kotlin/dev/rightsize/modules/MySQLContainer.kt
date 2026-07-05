package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node MySQL container. Defaults to a `test`/`test`/`test` user/password/database trio
 * (plus `MYSQL_ROOT_PASSWORD=test`) so [jdbcUrl] is usable with zero configuration; call
 * [withUsername]/[withPassword]/[withDatabase] before [start] to override any of them.
 *
 * ### Readiness — empirically pinned, not guessed
 *
 * The official entrypoint boots mysqld **twice**: once as a throwaway "temp server" to run
 * init scripts, then for real. Both prints, plus the X Plugin's own "ready for connections"
 * line, contain the substring `ready for connections`, and naively counting occurrences is a
 * trap: the temp server's X Plugin binds `port: 33060` — whose digits *start with* `3306`, so an
 * unanchored `port: 3306` regex false-matches it too. Captured verbatim from a real
 * `docker run mysql:8.4` boot with this module's env (`MYSQL_USER=test`, `MYSQL_DATABASE=test`,
 * `MYSQL_ROOT_PASSWORD=test`):
 *
 * ```
 * ...
 * [System] [MY-011323] [Server] X Plugin ready for connections. Socket: /var/run/mysqld/mysqlx.sock
 * [System] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.10'  socket: '/var/run/mysqld/mysqld.sock'  port: 0  MySQL Community Server - GPL.
 * ...(init scripts run, temp server shuts down)...
 * [System] [MY-011323] [Server] X Plugin ready for connections. Bind-address: '::' port: 33060, socket: /var/run/mysqld/mysqlx.sock
 * [System] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.10'  socket: '/var/run/mysqld/mysqld.sock'  port: 3306  MySQL Community Server - GPL.
 * ```
 *
 * Four lines contain `ready for connections`; only the last is the real server bound to 3306.
 * The temp server prints `port: 0` (no port yet) and the X Plugin lines print `33060`, whose
 * `3306` prefix would satisfy an unanchored match — so `times=N` counting is fragile here (N
 * would have to track exactly which of the 4 lines are "real", and a naive `times=2` fires on
 * the temp server's own X-Plugin + mysqld pair, one full boot early). Instead this pins a regex
 * anchored on the real server's `port: 3306` with a trailing non-digit-or-end boundary, so it
 * cannot match `33060`, and `times=1` is then unambiguous (that exact line appears once, only
 * after the real server is up).
 */
class MySQLContainer(image: String = "mysql:8.4") : GenericContainer<MySQLContainer>(image) {
    private var usernameState = "test"
    private var passwordState = "test"
    private var databaseState = "test"

    init {
        withExposedPorts(3306)
        withEnv("MYSQL_USER", usernameState)
        withEnv("MYSQL_PASSWORD", passwordState)
        withEnv("MYSQL_DATABASE", databaseState)
        withEnv("MYSQL_ROOT_PASSWORD", "test")
        // Anchored on the real server's line (see the class doc for the captured log excerpt and
        // why an unanchored "port: 3306" or a naive times=2 both misfire on the temp-server boot).
        // 120s: the entrypoint's full sequence (initialize database files, boot a temporary
        // server, provision user/database, shut it down, boot the real server) can exceed the
        // default 60s on shared CI runners.
        waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306($|[^0-9]).*", 1)
            .withStartupTimeout(Duration.ofSeconds(120)))
        // No withMemoryLimit override: boots clean on msb's default ~450M microVM RAM well under
        // 60s — unlike SpringCloudConfig's Paketo JVM image, MySQL 8.4's InnoDB default footprint
        // fits the default, so no module-level memory floor is warranted here.
    }

    /** Overrides `MYSQL_USER` (default `test`). */
    fun withUsername(username: String): MySQLContainer {
        usernameState = username
        return withEnv("MYSQL_USER", username)
    }

    /** Overrides `MYSQL_PASSWORD` (default `test`). */
    fun withPassword(password: String): MySQLContainer {
        passwordState = password
        return withEnv("MYSQL_PASSWORD", password)
    }

    /** Overrides `MYSQL_DATABASE` (default `test`). */
    fun withDatabase(database: String): MySQLContainer {
        databaseState = database
        return withEnv("MYSQL_DATABASE", database)
    }

    /** The configured database user (default `test`). */
    val username: String get() = usernameState
    /** The configured database password (default `test`). */
    val password: String get() = passwordState
    /** The configured database name (default `test`). */
    val databaseName: String get() = databaseState

    /** A `jdbc:mysql://` URL for the running container's [databaseName]. */
    val jdbcUrl: String get() = "jdbc:mysql://$host:${getMappedPort(3306)}/$databaseName"
}
