# Wait Strategies

A `WaitStrategy` is the readiness check `GenericContainer.start()` blocks on after
booting a container and before returning. Pass one to `.waitingFor(...)`; if you never
call it, the default is `Wait.forListeningPort()`.

## The three built-ins

All four are reached through the `Wait` factory object (`dev.rightsize.core.wait.Wait`),
and every one of them supports `.withStartupTimeout(Duration)` (default 60 seconds).

### `Wait.forListeningPort()`

Ready when every guest port declared with `withExposedPorts(...)` accepts a TCP
connection. This is the default, and it's a reasonable one for anything that doesn't
need a smarter check — but read
[Readiness caveats](#readiness-probe-caveats) below before leaning on it for a server
that logs nothing informative on startup.

```kotlin
GenericContainer("redis:8.6-alpine")
    .withExposedPorts(6379)
    .waitingFor(Wait.forListeningPort())
```

### `Wait.forHttp(path)`

Polls an HTTP endpoint until it returns the expected status. Chainable with
`.forPort(guestPort)` (defaults to the first exposed port if omitted) and
`.forStatusCode(code)` (defaults to `200`).

```kotlin
GenericContainer("arangodb:3.11")
    .withExposedPorts(8529)
    .waitingFor(Wait.forHttp("/_api/version").forPort(8529).forStatusCode(200))
```

### `Wait.forLogMessage(regex, times = 1)`

Ready when a log line matching `regex` has appeared at least `times` times. Matching
uses `Regex.containsMatchIn` — a substring match, not a whole-line anchor — so you
don't need to `.*`-wrap your pattern on both ends, though the shipped modules do it
anyway for clarity.

```kotlin
GenericContainer("docker.redpanda.com/redpandadata/redpanda:latest")
    .waitingFor(Wait.forLogMessage(".*Successfully started Redpanda.*"))
```

`times` matters more than it looks. Several database entrypoints (Postgres, MySQL,
MariaDB) print their "ready" line **twice** — once for a throwaway init-script server,
once for the real one — and a few (MySQL's X Plugin) print a line that superficially
looks like the real one but isn't. The shipped module wait strategies already handle
this correctly per-database; see each module's page under
[Modules](../modules/index.md) for the exact regex and the boot-log evidence behind it,
and reuse that pattern if you write your own `GenericContainer` wait for a similar
image.

## Writing a custom wait strategy: `AbstractWaitStrategy`

When none of the three built-ins fit — for example, a protocol that needs an actual
handshake rather than a bare connect or an HTTP request — extend
`dev.rightsize.core.wait.AbstractWaitStrategy` instead of implementing `WaitStrategy`
from scratch. It owns the deadline/poll-interval/log-tail plumbing (250ms polling
interval, last-50-lines-on-timeout diagnostics) so you only supply the actual probe:

```kotlin
protected abstract fun isReady(target: WaitTarget): Boolean
protected abstract fun what(): String   // short description, used in the timeout message
```

This is exactly what `MemcachedContainer` does — Memcached logs nothing useful at
startup, and a bare TCP connect can succeed before the server is really accepting
requests (see the caveat below), so its wait strategy speaks the protocol directly:

```kotlin
private class MemcachedResponds : AbstractWaitStrategy() {
    override fun what() = "a VERSION reply"
    override fun isReady(target: WaitTarget): Boolean {
        val port = target.mappedPort(target.exposedGuestPorts.first())
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(target.host, port), 1000)
                s.soTimeout = 1000
                s.getOutputStream().write("version\r\n".toByteArray())
                val line = s.getInputStream().bufferedReader().readLine()
                line != null && line.startsWith("VERSION")
            }
        } catch (_: Exception) { false }
    }
}
```

`WaitTarget` — the interface your `isReady` receives — is deliberately minimal and
backend-agnostic: `host`, `mappedPort(guestPort)`, `exposedGuestPorts`, `currentLogs()`,
and `describe()`. You never need to know which backend is active to write a wait
strategy.

## Readiness-probe caveats

This applies to **both backends**, not just the microVM one, and it's worth
understanding even if you never leave Docker.

`Wait.forListeningPort()` checks that *something* accepts a TCP connection on the
mapped host port — but on both backends there's a layer between the host socket and
the in-guest server that can start accepting connections before the server itself is
actually listening:

- **Docker:** the userland proxy (`docker-proxy`) binds the published host port as
  soon as the container is created and accepts connections immediately — before the
  process inside the container has bound its own port.
- **microsandbox:** the loopback port-forwarder into the guest has an analogous
  window — it accepts on the host side before the guest process is listening.

rightsize's `forListeningPort()` already mitigates the naive version of this problem
with a best-effort read probe (connect, then attempt a short zero-byte read with a
200ms timeout: an immediate EOF means "a proxy accepted but nobody's home yet, not
ready"; a timeout or actual data means "a real peer is on the other end, ready"). That
closes the worst of the gap for servers that accept a raw TCP connection and then
either speak first or wait silently for the client — which is most things.

It does **not** substitute for an actual protocol check on a server whose behavior
doesn't fit that shape. For servers that don't log a clear readiness line and whose
protocol needs more than "accepts a connection" to prove liveness, prefer:

- `Wait.forHttp(path)` for anything with an HTTP health endpoint, or
- `Wait.forLogMessage(...)` for a server with an unambiguous startup log line, or
- a protocol-level handshake via a custom `AbstractWaitStrategy`, the way
  `MemcachedContainer` does.

The shipped modules already made this call for you in each case — see
[Modules](../modules/index.md) for which wait strategy each one uses and why.
