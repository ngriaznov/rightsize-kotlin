# Getting Started

## 0. Coordinates

rightsize publishes to Maven Central under the group `dev.rightsize` — five
artifacts: `core`, `modules`, `backend-microsandbox`, `backend-docker`, and the
`bom` platform. Plain `mavenCentral()` in `repositories { }` is all the
consuming project needs.

## 1. Add the dependencies

```kotlin
// build.gradle.kts
testImplementation(platform("dev.rightsize:bom:0.3.0"))
testImplementation("dev.rightsize:core")
testImplementation("dev.rightsize:modules")
testRuntimeOnly("dev.rightsize:backend-microsandbox")
testRuntimeOnly("dev.rightsize:backend-docker")      // optional fallback
```

Keep both backends on the `testRuntimeOnly` classpath even if you expect to run on
just one machine type. rightsize discovers backends via `ServiceLoader` and picks the
best one available at runtime — see [Backends](backends.md) for the selection order.
If you only ever run in an environment where one backend applies (say, Docker-only
CI), you can drop the other, but most teams keep both so the same test suite works on
contributors' laptops and CI alike.

That's the whole setup. No Docker Desktop, no daemon, no separate install step.

## 2. Write your first test

rightsize ships a JUnit 5 extension mirroring Testcontainers' own: `@Sandboxed` on the
class, `@Container` on the fields to manage.

```kotlin
import dev.rightsize.junit.Container
import dev.rightsize.junit.Sandboxed
import dev.rightsize.modules.RedisContainer
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Sandboxed
class RedisSmokeTest {
    companion object {
        @JvmStatic
        @Container
        val redis = RedisContainer()
    }

    @Test
    fun `set then get`() {
        RedisClient.create(redis.uri).connect().use { conn ->
            conn.sync().set("k", "v")
            assertEquals("v", conn.sync().get("k"))
        }
    }
}
```

Run it like any other JUnit 5 test:

```bash
./gradlew test
```

### Static vs. instance fields

Just like Testcontainers:

- A **`@JvmStatic @Container`** field on a companion object starts once in
  `beforeAll` and stops once in `afterAll` — shared across every `@Test` in the class.
- An **instance `@Container`** field (no `@JvmStatic`) starts fresh in `beforeEach`
  and stops in `afterEach` — a clean container per test method.

See [Core Concepts → Containers](concepts/containers.md#junit-5-extension-semantics)
for the full lifecycle rules, including what happens to a container you start
yourself in an `init {}` block.

### Plain API (no JUnit extension)

You don't need the extension at all — `GenericContainer` and the module containers
work as plain objects:

```kotlin
import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

val arango = GenericContainer("arangodb:3.11")
    .withEnv("ARANGO_NO_AUTH", "1")
    .withExposedPorts(8529)
    .waitingFor(Wait.forHttp("/_api/version").forPort(8529))

arango.start()
try {
    val port = arango.getMappedPort(8529)   // published on 127.0.0.1
    // ...
} finally {
    arango.stop()
}
```

## 3. What happens on first run

The first time a test in your suite calls `.start()`, rightsize needs an active
backend. Backend selection is lazy and happens once per JVM (see
[Backends](backends.md#backend-selection) for the full precedence order); in the
common case — macOS on Apple Silicon, or Linux with a readable `/dev/kvm` — that means
provisioning the `msb` microsandbox runtime:

1. rightsize downloads the pinned `msb` release (binary + `libkrunfw`) from GitHub
   releases, matched to your OS/architecture.
2. Every downloaded asset is verified against its SHA-256 checksum from the release
   manifest before use.
3. Both files are installed atomically under `~/.cache/rightsize/` (`%LOCALAPPDATA%\rightsize`
   on Windows) — the `msb` binary is moved into place *last*, so a crashed install can
   never look complete; a later run detects and repairs it rather than trusting a
   half-written cache.
4. A cross-process file lock keeps parallel Gradle test workers from racing each
   other during this download.

None of this needs root, a running daemon, or any manual step — it's a normal part of
the first `./gradlew test` run, the same way Gradle itself downloads its wrapper. On
subsequent runs the cache is already populated and this step is skipped entirely.

If you're on a platform where microsandbox doesn't apply (Intel Mac, Windows without
Windows Hypervisor Platform, Linux without KVM), rightsize instead resolves to the
Docker backend and talks to your local Docker daemon over its usual socket — no
separate provisioning step, but you do need a working `docker` install (on Windows,
one reachable via a unix socket, e.g. Docker Engine inside WSL).

### Air-gapped or pre-seeded environments

If you're running somewhere without outbound network access (locked-down CI, for
example), see the environment variables in [Backends](backends.md#environment-variables) —
`MSB_PATH` lets you point at a pre-installed binary, and
`RIGHTSIZE_MSB_SKIP_DOWNLOAD=true` makes a missing runtime fail fast with guidance
instead of trying to reach GitHub.

## Next steps

- [Core Concepts](concepts/containers.md) — the full `GenericContainer` builder tour,
  wait strategies, networking, and file/memory options.
- [Backends](backends.md) — how selection works, what each backend does differently.
- [Modules](modules/index.md) — the preconfigured containers shipped today.
