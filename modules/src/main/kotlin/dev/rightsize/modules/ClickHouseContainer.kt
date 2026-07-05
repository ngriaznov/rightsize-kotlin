package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node ClickHouse container, queried over its HTTP interface (port 8123). The native
 * protocol port (9000) is exposed too, but this module's helpers are HTTP-first: the HTTP
 * interface needs no client dependency in either language (`java.net.http.HttpClient` /
 * `ureq`), matching the house convention for HTTP-first modules like [PinotContainer].
 *
 * Defaults to a `test`/`test` user/password pair and a `test` database so [httpUrl] plus basic
 * auth is usable with zero configuration; call [withUsername]/[withPassword]/[withDatabase]
 * before [start] to override any of them.
 *
 * ### Env var names — verified against a real boot
 *
 * `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` / `CLICKHOUSE_DB` are the image's documented names
 * and were confirmed directly: booting with those three set produces
 * `/entrypoint.sh: create new user 'test' instead 'default'` and
 * `/entrypoint.sh: create database 'test'` in the logs, and `curl -u test:test` against the HTTP
 * interface authenticates and runs queries successfully.
 *
 * ### Readiness — verified against a real 25.8 (LTS) boot
 *
 * `GET /ping` on the HTTP port returns the literal body `Ok.\n` as soon as the HTTP server is
 * accepting connections (protocol-aware — no restart/double-boot race like the Postgres/MySQL/
 * MariaDB entrypoints have), so `forHttp("/ping")` at the default 200 status code is the
 * correct and simplest readiness signal. Verified directly: `curl http://127.0.0.1:<port>/ping`
 * returned `Ok.` immediately once the container's logs showed the config merge complete.
 *
 * No control characters were found in the image's baked env (checked via `docker inspect`).
 * No `withMemoryLimit` override was needed at default settings (observed
 * ~12s IT round-trip on msb, no memory-ladder escalation needed) — a single-node ClickHouse
 * server, unlike Pinot's four-JVM QuickStart cluster, is not a JVM process at all.
 */
class ClickHouseContainer(image: String = "clickhouse/clickhouse-server:25.8") :
    GenericContainer<ClickHouseContainer>(image) {
    private var usernameState = "test"
    private var passwordState = "test"
    private var databaseState = "test"

    init {
        withExposedPorts(HTTP_PORT, NATIVE_PORT)
        withEnv("CLICKHOUSE_USER", usernameState)
        withEnv("CLICKHOUSE_PASSWORD", passwordState)
        withEnv("CLICKHOUSE_DB", databaseState)
        // Protocol-aware HTTP probe: /ping answers "Ok.\n" once the HTTP interface is really up —
        // no double-boot restart race the way the Postgres/MySQL/MariaDB entrypoints have.
        // 120s: the entrypoint's user/database provisioning runs a second server pass before the
        // HTTP interface opens, and on shared CI runners (and laptops running sibling containers
        // in parallel) that pass alone can exceed the default 60s.
        waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT)
            .withStartupTimeout(Duration.ofSeconds(120)))
    }

    /** Overrides `CLICKHOUSE_USER` (default `test`). */
    fun withUsername(username: String): ClickHouseContainer {
        usernameState = username
        return withEnv("CLICKHOUSE_USER", username)
    }

    /** Overrides `CLICKHOUSE_PASSWORD` (default `test`). */
    fun withPassword(password: String): ClickHouseContainer {
        passwordState = password
        return withEnv("CLICKHOUSE_PASSWORD", password)
    }

    /** Overrides `CLICKHOUSE_DB` (default `test`). */
    fun withDatabase(database: String): ClickHouseContainer {
        databaseState = database
        return withEnv("CLICKHOUSE_DB", database)
    }

    /** The configured database user (default `test`). */
    val username: String get() = usernameState
    /** The configured database password (default `test`). */
    val password: String get() = passwordState
    /** The configured database name (default `test`). */
    val databaseName: String get() = databaseState

    /** The HTTP interface's base URI (query via `POST` with a SQL body, basic-auth'd). */
    val httpUrl: String get() = "http://$host:${getMappedPort(HTTP_PORT)}"

    private companion object {
        const val HTTP_PORT = 8123
        const val NATIVE_PORT = 9000
    }
}
