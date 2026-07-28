# PostgreSQL

`dev.rightsize.modules.PostgreSQLContainer` — a single-node PostgreSQL container.
Defaults to a `test`/`test`/`test` user/password/database trio so `jdbcUrl` is usable
with zero configuration.

## Defaults

| | |
|---|---|
| Default image | `postgres:latest` — this image's floating reference (see below) |
| Exposed port | `5432` |
| Env | `POSTGRES_USER=test`, `POSTGRES_PASSWORD=test`, `POSTGRES_DB=test`, `DOCKER_PG_LLVM_DEPS=""` (see below) |
| Wait strategy | `Wait.forLogMessage(".*database system is ready to accept connections.*", times = 2)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins. This module previously pinned `postgres:18-alpine`; `latest` is the
Debian-based variant, not Alpine — functionally equivalent for everything this module
exercises, just a larger pull. Pass that image explicitly to pin it:

```kotlin
PostgreSQLContainer("postgres:18-alpine")
```

## Helpers

| Member | Returns |
|---|---|
| `jdbcUrl: String` | A `jdbc:postgresql://` URL for the running container's `databaseName` |
| `username` / `password` / `databaseName: String` | The configured credentials/database (default `test`/`test`/`test`) |
| `withUsername(username: String): PostgreSQLContainer` | Overrides `POSTGRES_USER` |
| `withPassword(password: String): PostgreSQLContainer` | Overrides `POSTGRES_PASSWORD` |
| `withDatabase(database: String): PostgreSQLContainer` | Overrides `POSTGRES_DB` |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.PostgreSQLContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class PostgreSQLContainerTest {
    @Test
    fun `creates a table and reads it back`() {
        val postgres = PostgreSQLContainer()
        postgres.start()
        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
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
            postgres.stop()
        }
    }
}
```

## Backend notes

### Two things that had to be figured out empirically, not guessed from the docs

**Readiness needs `times = 2`, not `times = 1`.** The official `postgres` entrypoint
starts the server *twice*: once as a throwaway instance to run init scripts, then a
second time for real — and it prints
`"database system is ready to accept connections"` on **both** boots. Waiting for the
first occurrence races the restart: a client can connect to the init-time instance in
the brief window just before it's torn down. Waiting for the *second* occurrence
(`times = 2`) waits for the real, durable listener instead.

**A control-character env var crashes microsandbox outright.** The official
`postgres:*-alpine` image bakes an env var, `DOCKER_PG_LLVM_DEPS`, whose value contains
a literal tab character (from a package list built with `\t\t` continuation in the
Dockerfile). microsandbox 0.6.2's krun VMM panics with `InvalidAscii` on that value
before the guest ever boots — reproduced with zero rightsize-set env vars, so this is
purely an artifact of the image, not anything this library added. Docker is
unaffected. This module overrides the variable to an empty string
(`withEnv("DOCKER_PG_LLVM_DEPS", "")`), which is a no-op on Docker and the fix on
microsandbox. This override was verified against the alpine variant specifically;
whether the Debian-based `latest` this module now defaults to bakes the same
tab-containing value has not been re-verified, so the override stays in place
unconditionally rather than being gated on the image chosen. If you hit a similar
`InvalidAscii` panic with an image this module doesn't cover, look for a baked env var
with an unusual byte in it — see [Troubleshooting](../troubleshooting.md) for the
general pattern.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`postgres`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
PostgreSQLContainer(
    DockerImageName.parse("mycorp/pg-hardened:18")
        .asCompatibleSubstituteFor("postgres"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
