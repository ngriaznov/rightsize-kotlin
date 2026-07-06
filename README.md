# rightsize

[![CI](https://github.com/ngriaznov/rightsize-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/ngriaznov/rightsize-kotlin/actions/workflows/ci.yml)

**Testcontainers-style integration testing on microVMs. No Docker required.**

rightsize runs your integration-test containers as hardware-isolated
[microsandbox](https://github.com/superradcompany/microsandbox) microVMs — one microVM
per container — behind a Testcontainers-shaped Kotlin API. The runtime self-provisions
on first use (one Gradle dependency, zero install steps), and a Docker backend covers
the platforms microVMs can't reach. If you know Testcontainers, you already know this
API — that's deliberate.

```kotlin
@Sandboxed
class OrderServiceTest {
    companion object {
        @JvmStatic @Container
        val redis = RedisContainer()          // boots a real microVM

        @JvmStatic @Container
        val kafka = RedpandaContainer()
    }

    @Test
    fun `orders flow end to end`() {
        val client = RedisClient.create(redis.uri)   // redis://127.0.0.1:<mapped port>
        val producer = KafkaProducer<String, String>(props(kafka.bootstrapServers))
        // ... your test
    }
}
```

## Why microVMs

| | Docker + Testcontainers | rightsize |
|---|---|---|
| Isolation | shared kernel (containers) | **hardware-level (microVM per container)** |
| Runtime install | Docker Desktop / daemon required | **none — self-provisions on first use** |
| Licensing | Docker Desktop licensing in orgs | Apache-2.0 all the way down |
| Image pulls | multi-arch (ArangoDB ≈ 600 MB) | single-arch layers (ArangoDB ≈ 203 MB) |
| Wall-clock (real 14-class Spring Boot suite) | 165 s | **164 s** |

The benchmark row is a real measured suite — fourteen `@SpringBootTest` classes booting
ArangoDB, clustered Redis, Redpanda, and WireMock per class — run back-to-back on the
same machine. MicroVM isolation costs nothing measurable end-to-end: lightweight
containers boot in well under a second, heavyweight databases a couple of seconds slower
than Docker, image pulls and container-to-container tests faster.

## Quickstart

```kotlin
// build.gradle.kts
testImplementation(platform("dev.rightsize:bom:0.1.0"))
testImplementation("dev.rightsize:core")
testImplementation("dev.rightsize:modules")
testRuntimeOnly("dev.rightsize:backend-microsandbox")
testRuntimeOnly("dev.rightsize:backend-docker")      // optional fallback
```

That's the whole setup. On first test run, rightsize downloads the pinned microsandbox
runtime (SHA-256-verified, from GitHub releases) into `~/.cache/rightsize/` and boots
your containers as microVMs. No daemon, no root, no pre-installed anything.

Use the JUnit 5 extension as in the example above (`@Sandboxed` on the class,
`@Container` on the fields), or drive containers by hand:

```kotlin
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

## Modules

Preconfigured containers with sensible waits and connection helpers. Each is a
`GenericContainer` subclass, so the fluent builders (`withEnv`, `withExposedPorts`,
`waitingFor`, …) are available on every one.

| Module | Helpers |
|---|---|
| `RedisContainer` | `uri` |
| `MemcachedContainer` | `address` |
| `ArangoContainer` | `endpoint`; `withRootPassword(…)` to enable auth (default: no-auth) |
| `MongoDBContainer` | `connectionString`, `replicaSetUrl` (alias) — single-node replica set, auto-initiated |
| `PostgreSQLContainer` | `jdbcUrl`, `username`, `password`, `databaseName`; `withUsername/withPassword/withDatabase(…)` |
| `MySQLContainer` | `jdbcUrl`, `username`, `password`, `databaseName`; `withUsername/withPassword/withDatabase(…)` |
| `MariaDBContainer` | `jdbcUrl`, `username`, `password`, `databaseName`; `withUsername/withPassword/withDatabase(…)` |
| `ClickHouseContainer` | `httpUrl`, `username`, `password`, `databaseName`; `withUsername/withPassword/withDatabase(…)` — HTTP interface (port 8123) |
| `Neo4jContainer` | `httpUrl`, `boltUrl`, `username`, `password`; `withPassword(…)` — HTTP Cypher endpoint (username fixed at `neo4j`) |
| `RedpandaContainer` | `bootstrapServers`, `schemaRegistryUrl` |
| `KafkaContainer` | `bootstrapServers` — KRaft single node |
| `RabbitMQContainer` | `amqpUrl`, `managementUrl`, `username`, `password`; `withUsername/withPassword(…)` — management plugin enabled |
| `PinotContainer` | `controllerUrl`, `brokerUrl` — single-container QuickStart cluster |
| `WireMockContainer` | `baseUrl`, `adminUrl` — stub via the `/__admin` API |
| `KeycloakContainer` | `authServerUrl`, `managementUrl`, `adminUsername`, `adminPassword`; `withAdminUsername/withAdminPassword(…)` |
| `SpringCloudConfigContainer` | `uri` |
| `FlociContainer` | `FlociContainer.aws()`/`.azure()`/`.gcp()` factories, `endpointUrl` — [floci.io](https://floci.io) cloud emulators (unsigned REST, no SDK needed) |
| `FlinkContainer` | `restUrl`; `withTaskManager()` for a full session cluster — **Docker only**¹ |

Some modules raise a memory floor for their image (`withMemoryLimit`): heavyweight JVM
images — SpringCloudConfig, Keycloak, Neo4j, Flink (1024 MB) and Pinot's multi-JVM
QuickStart cluster (4096 MB) — need more than the microVM default. That's baked into
the module; you don't set it. Each module's KDoc documents its exact image tag, wait
strategy, and the measured reasoning behind these choices.

¹ `withTaskManager()` throws `UnsupportedByBackendException` on microsandbox (the Flink
image carries no `nc`/busybox for network-link emulation — see [Networking](#networking));
a bare JobManager still runs on microsandbox. Run TaskManager topologies under
`RIGHTSIZE_BACKEND=docker`.

## Backends

rightsize picks a backend automatically; override with
`RIGHTSIZE_BACKEND=microsandbox|docker`.

| Platform | Backend used |
|---|---|
| macOS (Apple Silicon) | microsandbox (microVMs) |
| Linux x86_64 / arm64 with `/dev/kvm` | microsandbox (microVMs) |
| Windows x86_64 / arm64 with Windows Hypervisor Platform | microsandbox (microVMs)ᵃ |
| Intel Mac | Docker (auto-fallback) |
| Windows without WHP | Docker (auto-fallback)ᵇ |
| Linux without KVM | Docker (auto-fallback) |

ᵃ Windows msb support is upstream beta (microsandbox 0.6.3). rightsize detects a Windows
build is available and attempts it; if WHP turns out not to be usable, msb's own `msb doctor`
names the exact precondition (see ᵇ) instead of a generic failure.
ᵇ Force with `RIGHTSIZE_BACKEND=docker`, or enable WHP: run `msb doctor --fix` in an
elevated terminal (may require a reboot).

Both backends satisfy one behavioral contract, verified by a shared test suite — the
tests you write run unchanged on either. A few edges are backend-specific rather than
behavioral divergences:

- **Network-alias tunnels on microsandbox have real limits** versus Docker's native
  bridge networking — see [Networking](#networking).
- **Read-only file mounts aren't enforced in-guest on microsandbox 0.6.2.**
  `FileMount.readOnly` is honored by Docker; on microsandbox the guest currently gets a
  writable mount regardless. Don't rely on guest-side write protection under
  `RIGHTSIZE_BACKEND=microsandbox`.
- **`followOutput` delivers the same ordered, no-duplicate log stream on both backends**,
  but on microsandbox the final tail can arrive shortly after the sandbox reports stopped,
  rather than exactly at stream EOF (`msb logs -f` doesn't close on sandbox stop in 0.6.2,
  so the backend replays the not-yet-delivered tail once stop is confirmed).

## Networking

`Network` gives containers alias-based connectivity on both backends:

```kotlin
val net = Network.newNetwork()
val config = GenericContainer("hyness/spring-cloud-config-server:latest")
    .withNetwork(net).withNetworkAliases("configuration-stub")
    .withExposedPorts(8888)
val app = GenericContainer("my-service:latest")
    .withNetwork(net)
    .withEnv("CONFIG_URI", "http://${net.resolve("configuration-stub", 8888)}")
```

`Network.resolve(alias, port)` returns `alias:port` on **both** backends. On Docker that's
a native network alias. On microsandbox — where microVMs are fully isolated from each
other — rightsize transparently installs an `/etc/hosts` entry plus a TCP relay tunneled
over the sandbox's exec channel.

The microVM emulation has limits worth knowing: start dependencies before their consumers,
one connection at a time per tunnel (fine for config fetches; not for a cross-container
Kafka consumer), and the consumer image needs `nc`/busybox. Violations fail fast with an
actionable error.

## How it works

- **Self-provisioning runtime.** A pinned `msb` release (binary + libkrunfw) is downloaded
  once, SHA-256-verified against the release manifest, and installed atomically under
  `~/.cache/rightsize/` (`%LOCALAPPDATA%\rightsize` on Windows) — the binary lands last,
  so a crashed install is detected and repaired, never half-trusted. A cross-process file
  lock keeps parallel Gradle workers from racing.
- **Attached-mode supervision.** Each container is a held child process supervising its
  microVM; the image's ENTRYPOINT runs exactly as it would under Docker.
- **Pre-allocated ports.** Host ports are chosen before boot, so brokers like
  Redpanda/Kafka get their advertised listeners baked in — no restart dance.
- **One SPI, two backends.** `SandboxBackend` is a small interface; the shared contract
  suite is the referee, with the Docker backend doubling as the correctness oracle for the
  microVM backend.

## Configuration

| Env var | Effect |
|---|---|
| `RIGHTSIZE_BACKEND` | Force `microsandbox` or `docker` |
| `MSB_PATH` | Use a pre-installed `msb` binary; skip downloads |
| `RIGHTSIZE_CACHE_DIR` | Relocate the runtime cache (default `~/.cache/rightsize`; `%LOCALAPPDATA%\rightsize` on Windows) |
| `RIGHTSIZE_MSB_SKIP_DOWNLOAD` | `true` = fail instead of downloading (air-gapped CI) |

## Examples

Runnable, minimally-commented examples live under [`examples/`](examples), a Gradle
subproject (not published — see its `build.gradle.kts`) covering the plain API, the JUnit
`@Sandboxed` extension, and multi-container networking:

```bash
./gradlew :examples:runRedis     # plain API: start -> SET/GET via lettuce -> stop
./gradlew :examples:test         # @Sandboxed JUnit test: PostgreSQL over plain JDBC
./gradlew :examples:runNetwork   # two containers on a Network, reached by alias
```

Like everything else in this repo, they run on either backend — force one with
`RIGHTSIZE_BACKEND=microsandbox|docker` prefixed on any of the commands above.

## Development

```bash
./gradlew build                                            # unit tests + coverage floor
RIGHTSIZE_BACKEND=microsandbox ./gradlew integrationTest   # needs Apple Silicon or Linux+KVM
RIGHTSIZE_BACKEND=docker ./gradlew integrationTest         # needs any Docker-compatible daemon
```

CI runs the matrix on Linux (KVM), macOS (Apple Silicon), and a Docker-only job.

## Documentation

Full documentation (concepts, backends, module reference, troubleshooting) lives under
[`docs/`](docs) and is published from `mkdocs.yml`.

## License

[Apache-2.0](LICENSE)
</content>
</invoke>
