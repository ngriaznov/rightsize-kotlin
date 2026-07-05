# ClickHouse

`dev.rightsize.modules.ClickHouseContainer` — a single-node ClickHouse container,
queried over its HTTP interface. Defaults to a `test`/`test` user/password pair and a
`test` database.

## Defaults

| | |
|---|---|
| Default image | `clickhouse/clickhouse-server:25.8` |
| Exposed ports | `8123` (HTTP interface — what the helpers use), `9000` (native protocol, exposed but not wrapped by a helper) |
| Env | `CLICKHOUSE_USER=test`, `CLICKHOUSE_PASSWORD=test`, `CLICKHOUSE_DB=test` |
| Wait strategy | `Wait.forHttp("/ping").forPort(8123)` |

## Helpers

| Member | Returns |
|---|---|
| `httpUrl: String` | The HTTP interface's base URI — `POST` a SQL body, basic-auth'd |
| `username` / `password` / `databaseName: String` | The configured credentials/database (default `test`/`test`/`test`) |
| `withUsername(username: String): ClickHouseContainer` | Overrides `CLICKHOUSE_USER` |
| `withPassword(password: String): ClickHouseContainer` | Overrides `CLICKHOUSE_PASSWORD` |
| `withDatabase(database: String): ClickHouseContainer` | Overrides `CLICKHOUSE_DB` |

Call the `withX` overrides before `start()`. This module is deliberately HTTP-first —
the HTTP interface needs no client dependency in the JVM (`java.net.http.HttpClient`
covers it), which is why there's no separate `jdbcUrl` helper.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.ClickHouseContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

class ClickHouseContainerTest {
    private val http = HttpClient.newHttpClient()

    private fun ClickHouseContainer.query(sql: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI(httpUrl))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `creates a table, inserts, and selects`() {
        val ch = ClickHouseContainer()
        ch.start()
        try {
            assertEquals(200, ch.query("CREATE TABLE t (x Int32) ENGINE=Memory").statusCode())
            assertEquals(200, ch.query("INSERT INTO t VALUES (1)").statusCode())
            val select = ch.query("SELECT x FROM t")
            assertEquals(200, select.statusCode())
            assertEquals("1", select.body().trim())
        } finally {
            ch.stop()
        }
    }
}
```

## Backend notes

**Readiness is genuinely simple here, no race to work around.** `GET /ping` on the
HTTP port returns the literal body `Ok.\n` as soon as the HTTP server is accepting
connections — verified directly against a real ClickHouse 25.8 boot. There's no
restart/double-boot race the way Postgres/MySQL/MariaDB's entrypoints have, so
`Wait.forHttp("/ping")` at the default 200 status is both the simplest and the correct
signal, with nothing further to work around.

**Env var names were confirmed against a real boot**, not assumed from documentation:
booting with `CLICKHOUSE_USER`/`CLICKHOUSE_PASSWORD`/`CLICKHOUSE_DB` set produces
`create new user 'test' instead 'default'` and `create database 'test'` in the logs,
and an authenticated `curl -u test:test` against the HTTP interface runs queries
successfully.

No `withMemoryLimit` override is needed — a single-node ClickHouse server isn't a JVM
process at all, so it has none of the Paketo/QuickStart-style fixed-heap-region
problems that [Spring Cloud Config](spring-cloud-config.md) and [Pinot](pinot.md) ran
into (observed ~12s integration-test round-trip on the microsandbox backend with no
memory-ladder escalation needed).
