# Flink

`dev.rightsize.modules.FlinkContainer` — an Apache Flink **JobManager**, optionally
paired with a companion **TaskManager** via `withTaskManager()` for a real session
cluster that can actually run jobs (a bare JobManager has zero task slots and can
only accept/reject submissions, never execute them).

## Defaults

| | |
|---|---|
| Default image | `flink:1.20.5` |
| Exposed ports | `8081` (REST), `6123` (RPC — only meaningful once a TaskManager joins) |
| Command | `jobmanager` |
| Memory limit | `withMemoryLimit(1024)` — see below |
| Wait strategy | `Wait.forHttp("/overview").forPort(8081).withStartupTimeout(Duration.ofSeconds(120))` |

## Helpers

| Member | Returns |
|---|---|
| `restUrl: String` | The JobManager REST base URI (`/overview`, `/taskmanagers`, job submission, etc.) |
| `withTaskManager(): FlinkContainer` | Adds a companion TaskManager on a shared network for a real session cluster with task slots — **docker only**, see below |

Call `withTaskManager()` before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.FlinkContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FlinkContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `bare JobManager answers REST overview`() {
        val flink = FlinkContainer()
        flink.start()
        try {
            val resp = http.send(
                HttpRequest.newBuilder(URI("${flink.restUrl}/overview")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode(), "overview failed: ${resp.body()}")
            assertTrue(resp.body().contains("\"taskmanagers\""), "unexpected overview body: ${resp.body()}")
        } finally {
            flink.stop()
        }
    }
}
```

A full session-cluster example (docker only):

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.FlinkContainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FlinkSessionClusterTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `withTaskManager registers a slot-bearing TaskManager`() {
        val flink = FlinkContainer().withTaskManager()
        flink.start()
        try {
            val deadline = System.currentTimeMillis() + 60_000
            var body = ""
            while (System.currentTimeMillis() < deadline) {
                val resp = http.send(
                    HttpRequest.newBuilder(URI("${flink.restUrl}/taskmanagers")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                body = resp.body()
                if (resp.statusCode() == 200 && body.contains("\"id\"")) break
                Thread.sleep(1000)
            }
            assertTrue(body.contains("\"id\""), "TaskManager never registered: $body")
        } finally {
            flink.stop()
        }
    }
}
```

## Backend notes

**A real Flink session cluster is two processes bound by a persistent bidirectional
RPC connection**, not a one-shot request/response: the TaskManager dials the
JobManager's RPC port (6123) at boot and *stays connected*, carrying heartbeats and
slot offers/task deployments both ways for the cluster's whole lifetime.
`withTaskManager()` puts both containers on one internally-created network, aliases
the JobManager as `jobmanager`, and sets `FLINK_PROPERTIES=jobmanager.rpc.address:
jobmanager` on **both** containers — not just the TaskManager. Verified directly:
setting it on the TaskManager alone leaves the JobManager's own Pekko actor system
bound under its container hostname rather than the alias, so every registration
attempt from the TaskManager gets silently dropped as a non-local recipient. The
JobManager must be told its own address is the alias too.

**`withTaskManager()` is Docker only — it throws `UnsupportedByBackendException` on
microsandbox, and the reason is more basic than a Pekko/tunnel incompatibility: the
official Flink image is missing a prerequisite the tunnel needs.** On docker, this is
verified end-to-end: the TaskManager registers with the JobManager (`Successful
registration at resource manager ...` in its own log) and `GET /taskmanagers` on the
JobManager's REST port shows one slot-bearing TM within seconds of both containers
starting.

On microsandbox, `withTaskManager()` throws before ever booting anything. msb's
network-link emulation requires `nc`/busybox *inside the consumer image* to serve
the tunnel's in-guest listener, and the official `flink:1.20.5` image is a bare JRE
+ Flink install with neither — the attempt fails immediately with
`UnsupportedByBackendException: network links (no nc/busybox in consumer image
'flink:1.20.5')`, thrown before a single byte of Pekko traffic could be exchanged.
Whether Pekko's persistent-connection registration would *also* work over the
tunnel's single-connection-at-a-time model was never reached or tested — the missing
`nc`/busybox prerequisite stops the attempt before that question is even in play.

**A bare JobManager works fine on both backends.** It needs no network-link
emulation at all — just the ordinary published-port HTTP path against `/overview` —
so this module supports msb for JobManager-only use; only `withTaskManager()` is
gated to docker.

**Memory — JVM, the ladder applies to both roles.** A JobManager settles around
~310 MiB RSS and a TaskManager around ~375 MiB RSS at rest on docker with no cap
(`docker stats`, real boot) — both comfortably over msb's ~450 MB default
*individually*, and this module runs the JobManager on msb too (see above), so
`withMemoryLimit(1024)` is this module's default for both roles, matching the
family's established single-JVM floor ([Keycloak](keycloak.md), [Neo4j](neo4j.md)).
