# Cassandra

`dev.rightsize.modules.CassandraContainer` — a single-node Cassandra container.

## Defaults

| | |
|---|---|
| Default image | `cassandra:5.0.8` |
| Exposed port | `9042` |
| Env | `GPG_KEYS=` (empty — see below), `MAX_HEAP_SIZE=512M`, `HEAP_NEWSIZE=128M` |
| Memory limit | `withMemoryLimit(2560)` |
| Wait strategy | `Wait.forLogMessage(".*Starting listening for CQL clients.*", 1).withStartupTimeout(Duration.ofSeconds(300))` |

## Helpers

| Member | Returns |
|---|---|
| `contactPoint: String` | The CQL native protocol contact point, `host:port` |
| `cqlPort: Int` | The mapped CQL native protocol port |
| `localDatacenter: String` | The single node's datacenter name (`datacenter1`) |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.CassandraContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CassandraContainerTest {
    @Test
    fun `create, insert, and select round-trips through cqlsh`() {
        val cassandra = CassandraContainer()
        cassandra.start()
        try {
            val cql = "CREATE KEYSPACE roundtrip WITH replication = " +
                "{'class': 'SimpleStrategy', 'replication_factor': 1}; " +
                "CREATE TABLE roundtrip.t (x int PRIMARY KEY); " +
                "INSERT INTO roundtrip.t (x) VALUES (1); " +
                "SELECT x FROM roundtrip.t;"
            val result = cassandra.execInContainer("cqlsh", "-e", cql)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("1"))
        } finally {
            cassandra.stop()
        }
    }
}
```

## Backend notes

**`GPG_KEYS` must be overridden to a tab-free value — this is the difference
between the module booting and aborting.** `cassandra:5.0.8`'s baked env includes a
`GPG_KEYS` value that contains a literal TAB character. Under msb 0.6.6, booting any
image whose baked env contains a TAB aborts before the guest is even reachable:

```
sandbox process exited (signal: 6 (SIGABRT)) before agent relay became available
```

with `msb logs --source system` showing the root cause:

```
panicked at msb_krun_vmm-0.1.25/src/builder.rs:1154: ... Err value: InvalidAscii
```

This is msb's env-encoding step rejecting a TAB anywhere in the image's baked env,
before Cassandra itself ever runs — not specific to anything Cassandra does.
`withEnv("GPG_KEYS", "")` overrides the baked value with an empty, tab-free one.
`GPG_KEYS` is consumed only at image build time (verifying the Apache download's
signing keys), so overriding it here has no effect on anything Cassandra does at
runtime. Verified directly: an otherwise identical `msb run` aborts with the
signature above without this override and boots cleanly with it.

**Memory: a heap-bounded JVM, ladder verified at 2560 MB.** `MAX_HEAP_SIZE=512M`/
`HEAP_NEWSIZE=128M` keep the JVM heap itself small, but the container's total
footprint still needs headroom above that for the JVM's non-heap regions and the
rest of the process. 2560 MB was verified stable for a full boot-to-ready cycle.

**Readiness is a log line, with a longer startup timeout than the house default.**
`Starting listening for CQL clients` is logged once the CQL native protocol server
is actually serving; observed at 58s on a quiet local machine. The house precedent
for a generous ceiling is 180s ([Keycloak](keycloak.md), [MySQL](mysql.md)), and a
single-node Cassandra JVM is heavier than either, so this module's startup timeout
is 300s.

**Round-trip verified with the bundled `cqlsh` — no Cassandra driver in this repo.**
A `cqlsh -e "..."` round-trip — `CREATE KEYSPACE` → `CREATE TABLE` → `INSERT` →
`SELECT` — returned the inserted row, run through `exec` on the started container
using the `cqlsh` binary the image already bundles.
