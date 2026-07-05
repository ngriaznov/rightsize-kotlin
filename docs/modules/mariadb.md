# MariaDB

`dev.rightsize.modules.MariaDBContainer` — a single-node MariaDB container. Defaults
to a `test`/`test`/`test` user/password/database trio (plus
`MARIADB_ROOT_PASSWORD=test`) so `jdbcUrl` is usable with zero configuration.

## Defaults

| | |
|---|---|
| Default image | `mariadb:11.4` |
| Exposed port | `3306` |
| Env | `MARIADB_USER=test`, `MARIADB_PASSWORD=test`, `MARIADB_DATABASE=test`, `MARIADB_ROOT_PASSWORD=test` |
| Wait strategy | `Wait.forLogMessage(".*port: 3306.*mariadb\\.org binary distribution.*", times = 1)` |

## Helpers

| Member | Returns |
|---|---|
| `jdbcUrl: String` | A `jdbc:mariadb://` URL for the running container's `databaseName` |
| `username` / `password` / `databaseName: String` | The configured credentials/database (default `test`/`test`/`test`) |
| `withUsername(username: String): MariaDBContainer` | Overrides `MARIADB_USER` |
| `withPassword(password: String): MariaDBContainer` | Overrides `MARIADB_PASSWORD` |
| `withDatabase(database: String): MariaDBContainer` | Overrides `MARIADB_DATABASE` |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.MariaDBContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class MariaDBContainerTest {
    @Test
    fun `creates a table and reads it back`() {
        val mariadb = MariaDBContainer()
        mariadb.start()
        try {
            DriverManager.getConnection(mariadb.jdbcUrl, mariadb.username, mariadb.password).use { conn ->
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
            mariadb.stop()
        }
    }
}
```

## Backend notes: same double-boot pattern as MySQL, one less trap

The official `mariadb` entrypoint double-boots exactly like MySQL's — once as a
throwaway temp server to run init scripts (printing `ready for connections` with
`port: 0`, i.e. before any port is bound), then for real on port 3306. Captured
verbatim from a real `docker run mariadb:11.4` boot with this module's env:

```
2026-07-04  8:47:29 0 [Note] mariadbd: ready for connections.
Version: '11.4.12-MariaDB-ubu2404'  socket: '/run/mysqld/mysqld.sock'  port: 0  mariadb.org binary distribution
2026-07-04  8:47:30 0 [Note] Server socket created on IP: '0.0.0.0', port: '3306'.
2026-07-04  8:47:30 0 [Note] Server socket created on IP: '::', port: '3306'.
2026-07-04  8:47:30 0 [Note] mariadbd: ready for connections.
Version: '11.4.12-MariaDB-ubu2404'  socket: '/run/mysqld/mysqld.sock'  port: 3306  mariadb.org binary distribution
```

Unlike MySQL 8.4, MariaDB has no X Plugin adding a third `ready for connections` line
with a decoy `3306`-prefixed port (`33060`) — so there's no false-match trap to anchor
against here, and an unanchored `times = 2` count would actually work by coincidence
(there are exactly two `ready for connections` lines total). This module follows
[MySQL](mysql.md)'s house precedent anyway and anchors the regex on the literal
`port: 3306` of the real server's line, so the wait stays robust even if a future
MariaDB point release changes the temp-server line's exact wording — it doesn't rely
on today's line count staying exactly two forever.
