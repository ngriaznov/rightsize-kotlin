# Cassandra

`dev.rightsize.modules.CassandraContainer` — a single-node Cassandra container.

## Defaults

| | |
|---|---|
| Default image | `cassandra:latest` — this image's floating reference (see below) |
| Exposed port | `9042` |
| Env | `GPG_KEYS=` (empty — see below), `MAX_HEAP_SIZE=512M`, `HEAP_NEWSIZE=128M` |
| Memory limit | `withMemoryLimit(2560)` |
| Wait strategy | `Wait.forLogMessage(".*Starting listening for CQL clients.*", 1).withStartupTimeout(Duration.ofSeconds(300))` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins, so the version moves with Cassandra's own releases instead of this
library's release cycle. Every fact below — the `GPG_KEYS` bug, the memory ladder, the
readiness log line and its timing — was verified against `cassandra:5.0.8` specifically;
pass that image explicitly to pin it:

```kotlin
CassandraContainer("cassandra:5.0.8")
```

The `GPG_KEYS` override below is kept unconditionally on the floating default too —
whether a future Cassandra release still bakes a tab into that value has not been
re-verified, so this module does not gate the override on the image chosen.

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

**`GPG_KEYS` carries a literal TAB in this image's baked env — harmless on the pinned
msb, still overridden as a guard.** `cassandra:5.0.8`'s baked env includes a
`GPG_KEYS` value that contains a literal TAB character. On older msb releases (0.6.x),
booting any image whose baked env contained a TAB aborted before the guest was even
reachable:

```
sandbox process exited (signal: 6 (SIGABRT)) before agent relay became available
```

with `msb logs --source system` showing the root cause:

```
panicked at msb_krun_vmm-0.1.25/src/builder.rs:1154: ... Err value: InvalidAscii
```

That was msb's env-encoding step rejecting a TAB anywhere in the image's baked env,
before Cassandra itself ever ran — not specific to anything Cassandra does. It's fixed
as of msb 0.7.1: `cassandra:5.0.8` boots with its baked `GPG_KEYS`
unmodified. `withEnv("GPG_KEYS", "")` still overrides the baked value with an empty,
tab-free one unconditionally, as a harmless guard for anyone pointing `MSB_PATH` at an
older msb — `GPG_KEYS` is consumed only at image build time (verifying the Apache
download's signing keys), so the override has no effect on anything Cassandra does at
runtime either way.

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

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`cassandra`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
CassandraContainer(
    DockerImageName.parse("mycorp/cassandra-hardened:5.0.8")
        .asCompatibleSubstituteFor("cassandra"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
