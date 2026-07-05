# MySQL

`dev.rightsize.modules.MySQLContainer` — a single-node MySQL container. Defaults to a
`test`/`test`/`test` user/password/database trio so `jdbcUrl` is usable with zero
configuration.

## Defaults

| | |
|---|---|
| Default image | `mysql:8.4` |
| Exposed port | `3306` |
| Env | `MYSQL_USER=test`, `MYSQL_PASSWORD=test`, `MYSQL_DATABASE=test`, `MYSQL_ROOT_PASSWORD=test` |
| Wait strategy | `Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306($\|[^0-9]).*", times = 1)` |

## Helpers

| Member | Returns |
|---|---|
| `jdbcUrl: String` | A `jdbc:mysql://` URL for the running container's `databaseName` |
| `username` / `password` / `databaseName: String` | The configured credentials/database (default `test`/`test`/`test`) |
| `withUsername(username: String): MySQLContainer` | Overrides `MYSQL_USER` |
| `withPassword(password: String): MySQLContainer` | Overrides `MYSQL_PASSWORD` |
| `withDatabase(database: String): MySQLContainer` | Overrides `MYSQL_DATABASE` |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.MySQLContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class MySQLContainerTest {
    @Test
    fun `creates a table and reads it back`() {
        val mysql = MySQLContainer()
        mysql.start()
        try {
            DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (x INT)") }
                conn.createStatement().use { it.execute("INSERT INTO t (x) VALUES (1)") }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT x FROM t").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(1, rs.getInt("x"))
                    }
                }
            }
        } finally {
            mysql.stop()
        }
    }
}
```

## Backend notes: readiness, pinned empirically, not guessed

MySQL's entrypoint is a genuine trap for a naive log-message wait. It boots `mysqld`
**twice** — once as a throwaway "temp server" to run init scripts, then for real — and
**four separate log lines** contain the substring `ready for connections`: the temp
server's own line, the temp server's X Plugin line, the real server's X Plugin line,
and the real server's own line. Naively counting occurrences of `ready for connections`
is fragile, because the temp server's X Plugin line prints `port: 33060` — whose
digits *start with* `3306` — so even an unanchored `port: 3306` regex false-matches it.

Captured verbatim from a real `docker run mysql:8.4` boot with this module's env:

```
[System] [MY-011323] [Server] X Plugin ready for connections. Socket: /var/run/mysqld/mysqlx.sock
[System] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.10'  socket: '/var/run/mysqld/mysqld.sock'  port: 0  MySQL Community Server - GPL.
...(init scripts run, temp server shuts down)...
[System] [MY-011323] [Server] X Plugin ready for connections. Bind-address: '::' port: 33060, socket: /var/run/mysqld/mysqlx.sock
[System] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.10'  socket: '/var/run/mysqld/mysqld.sock'  port: 3306  MySQL Community Server - GPL.
```

The temp server's real-mysqld line prints `port: 0` (no port bound yet); the X Plugin
lines print `33060`. A naive `times = 2` count would fire one full boot early, on the
temp server's own X-Plugin-plus-mysqld pair. This module's wait strategy instead
anchors the regex on the real server's exact line — `port: 3306` followed by a
non-digit-or-end boundary, so it structurally cannot match `33060` — which makes
`times = 1` unambiguous: that exact line appears once, only after the real server is
listening.

If you're writing a `GenericContainer` wait strategy for a different MySQL-family
image, or extending this pattern elsewhere, capture a real boot log first rather than
assuming a single readiness line — see [MariaDB](mariadb.md) for the sibling case that
follows the same precedent.
