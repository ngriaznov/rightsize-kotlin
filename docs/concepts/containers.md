# Containers

## `GenericContainer` builder tour

`GenericContainer<SELF>` is the base class every module container (`RedisContainer`,
`PostgreSQLContainer`, ...) extends, and it's also usable directly for anything
rightsize doesn't ship a preconfigured module for.

```kotlin
val container = GenericContainer("your-image:tag")
    .withEnv("KEY", "value")              // repeatable — call once per var
    .withExposedPorts(8080, 9090)         // guest ports to publish
    .withCommand("your-entrypoint", "arg") // overrides the image's default CMD
    .withNetwork(net)                      // joins a Network (see Networking)
    .withNetworkAliases("my-service")      // name siblings resolve this container as
    .withCopyFileToContainer(file, "/etc/app/config.yml")
    .waitingFor(Wait.forHttp("/health"))   // readiness check; defaults to forListeningPort()
    .withMemoryLimit(1024)                 // guest memory cap in MB; see Files & Memory
```

Every `withX` builder returns `SELF`, so they chain freely and in any order — nothing
here is positional. Call `.start()` when you're ready to boot it, and `.stop()` (or
just let the JUnit extension do it) to tear it down.

### What you get once it's running

| Member | What it gives you |
|---|---|
| `isRunning: Boolean` | `true` from a successful `start()` until `stop()` |
| `host: String` | Always `"127.0.0.1"` — both backends publish to loopback |
| `getMappedPort(guestPort: Int): Int` | The host port a declared guest port landed on |
| `logs: String` | Full captured logs so far |
| `followOutput(consumer: (String) -> Unit): AutoCloseable` | Streams new log lines as they arrive; close the returned handle to stop delivery |
| `execInContainer(vararg cmd: String): ExecResult` | Runs a command inside the running container; returns `exitCode`/`stdout`/`stderr` as **properties**, not Java-style getters |
| `copyFileToContainer`/`copyContentToContainer`/`copyFileFromContainer` | Copies a file, directory, or in-memory content into or out of the running container — see [Copying Files](../copy.md) |
| `checkpoint(name: String? = null): Checkpoint` | Captures the running container's filesystem for `GenericContainer.fromCheckpoint` to restore later — see [Checkpoint / Restore](../checkpoints.md) |

`getMappedPort` fails with a message telling you exactly what to check — whether you
forgot `start()`, forgot `withExposedPorts(...)` for that port, or the container
stopped after starting. You shouldn't need to guess.

### `GenericContainer("image")` without a type parameter

`GenericContainer` is generic (`GenericContainer<SELF : GenericContainer<SELF>>`) so
that the fluent builders on a *subclass* keep returning that subclass's type — this is
what lets `RedisContainer().withEnv(...)` still return a `RedisContainer`, not a bare
`GenericContainer`. When you don't need a subclass, a companion `operator fun invoke`
lets you construct it directly with no type argument:

```kotlin
val c = GenericContainer("alpine:3.19")   // GenericContainer<*> — no <SELF> to specify
```

### Boot sequence and failure handling

`start()` does, in order: allocate host ports, create and start the container on the
active backend (retrying up to 5 times with fresh ports if it loses a port-bind race —
see [Troubleshooting](../troubleshooting.md#two-containers-occasionally-fail-to-start-with-a-port-bind-error)),
install any network links to
already-running siblings, register itself on its `Network` (after linking, so it never
links to itself), then block on the configured wait strategy.

If **any** of those steps fails — including the network-link install, not just the
wait strategy — `stop()` runs before the exception propagates. A half-started
container never leaks a running process or a held port; you don't need your own
`try/finally` around a failed `start()` to avoid a leak (though you still want one
around `start()`/`stop()` for symmetry with your own test logic — the plain-API
example in [Getting Started](../getting-started.md#plain-api-no-junit-extension) shows
the pattern).

`stop()` itself is idempotent: safe to call on a container that never started, safe to
call twice, safe to call unconditionally in a `finally` block.

## Lifecycle: `start()`/`stop()` are yours to call, or let the extension do it

You've already seen both styles in [Getting Started](../getting-started.md). The
JUnit 5 extension (below) exists purely as lifecycle glue around `start()`/`stop()` —
it doesn't change what a container does, only when those two methods get called.

## JUnit 5 extension semantics

`@Sandboxed` (class-level) plus `@Container` (field-level) is the rightsize equivalent
of Testcontainers' `@Testcontainers`/`@Container`. The rules, precisely:

- **Field discovery** walks the test class and every superclass, looking for
  `@Container`-annotated fields whose declared type is assignable to
  `GenericContainer<*>`. A field of any other type annotated `@Container` is silently
  ignored rather than treated as an error.
- **Static fields** (`@JvmStatic @Container` on a companion object) are started once
  in `beforeAll` and stopped once in `afterAll` — shared across every test method in
  the class.
- **Instance fields** (a plain `@Container` field, no `@JvmStatic`) are started fresh
  in `beforeEach` and stopped in `afterEach` — one container per test method.
- **Pre-started containers are left alone.** If a field is already running by the time
    the extension inspects it (for example, you started it yourself in an `init {}`
  block), the extension does not restart it, and — this is the important half — it
  will **not** stop it either. A container the extension didn't start is entirely your
  responsibility to stop. This mirrors Testcontainers' own behavior exactly.

```kotlin
@Sandboxed
class OrderServiceTest {
    companion object {
        @JvmStatic @Container
        val redis = RedisContainer()          // per-class: one instance for all tests

        @JvmStatic @Container
        val kafka = RedpandaContainer()
    }

    @Container
    val perTestStub = WireMockContainer()     // per-test: fresh instance every method

    @Test
    fun `orders flow end to end`() {
        // redis and kafka are already running; perTestStub started just for this test
    }
}
```

## Backend override for tests

`GenericContainer` exposes an `internal` backend-override seam
(`withBackend(SandboxBackend)`) used by rightsize's own contract test suite to run the
same test logic against a fake or a specific backend implementation. It's not part of
the public API — application code always goes through
`Backends.active()` implicitly, resolved once per JVM per the rules in
[Backends](../backends.md#backend-selection).
