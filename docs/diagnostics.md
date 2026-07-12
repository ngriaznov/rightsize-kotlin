# Failure Diagnostics

When a test using rightsize fails, the useful evidence is usually sitting in whatever container
was running at the time — its state, mapped ports, and recent logs — but by the time you go
looking, the container is already gone (or you have to go add `println(container.logs)` calls
and re-run). `Diagnostics.report()` prints that evidence in one call, and the `@Sandboxed`
extension prints it automatically on test failure so you never have to remember to add it.

## The report

```kotlin
import dev.rightsize.core.diagnostics.Diagnostics

println(Diagnostics.report())
```

`Diagnostics.report()` describes every container this process currently considers live — every
`GenericContainer` between a successful `start()` and its `stop()` — as a plain string:

```text
== rightsize diagnostics: 2 running container(s) ==
-- rz-ab12cd34-redis (redis:7-alpine) --
state: running   host: 127.0.0.1   ports: 6379->49213
last 50 log lines:
  1:M 11 Jul 2026 12:00:00.000 * Ready to accept connections tcp
-- rz-ab12cd34-postgres (postgres:16-alpine) --
state: running   host: 127.0.0.1   ports: 5432->49214
last 50 log lines:
  2026-07-11 12:00:00.000 UTC [1] LOG:  database system is ready to accept connections
```

With nothing running, it's a single line: `== rightsize diagnostics: no running containers ==`.

Each section shows the container's name, image, mapped ports (`guestPort->hostPort`), and the
last 50 lines of its logs, each indented two spaces. If the logs call itself fails (the sandbox
died mid-test, the backend's connection dropped, ...) the section degrades to a one-line
`logs: unavailable (<reason>)` instead of throwing — a diagnostics call must never itself
become the reason your test run blows up.

This exact format — line for line — is a cross-language contract: rightsize's Kotlin, Rust, and
Node libraries all render byte-identical output for the same inputs, so a diagnostics dump reads
the same regardless of which language wrote the test. See [Cross-Language
Parity](parity.md) for the full list of behaviors verified this way.

## Automatic printing on test failure

The [`@Sandboxed`](how-it-works.md) JUnit 5 extension already manages container lifecycle; it
also watches for test failure and prints `Diagnostics.report()` to `System.err` exactly once
per failed test, right alongside the stack trace JUnit itself prints — no extra wiring beyond
the `@Sandboxed` annotation you already have:

```kotlin
@Sandboxed
class OrderServiceTest {
    @Container val redis = RedisContainer()

    @Test fun `order total includes tax`() {
        // ... on failure, the diagnostics report lands in stderr next to the stack trace
    }
}
```

A passing test prints nothing extra. A diagnostics failure (e.g. the report itself throws for
some unforeseen reason) is swallowed rather than masking the real test failure that triggered
the hook.

## Manual use

Nothing about the report requires the `@Sandboxed` extension — call `Diagnostics.report()`
directly wherever it's useful: a custom test listener, a CLI debug command, or just a `println`
you drop in while chasing down a flaky test.
