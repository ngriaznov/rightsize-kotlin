# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to adhere to [Semantic Versioning](https://semver.org/) once it
reaches its first tagged release.

## [Unreleased]

Initial public groundwork — not yet released. Everything below exists on `main`
but no `0.1.0` tag has been cut.

### Added

- **Examples** (`examples`): a Gradle subproject (not published) with three runnable
  examples — a plain-API Redis quickstart (`./gradlew :examples:runRedis`), a JUnit
  `@Sandboxed` PostgreSQL test over plain JDBC (`./gradlew :examples:test`), and a
  two-container `Network` example with a consumer reaching a WireMock stub by alias
  (`./gradlew :examples:runNetwork`). All three run on either backend via
  `RIGHTSIZE_BACKEND`.
- **Core engine** (`core`): a Testcontainers-shaped API — `GenericContainer`,
  `Network`, `Wait` (with `forListeningPort`/`forHttp`/`forLogMessage`),
  `MountableFile`, `Startables`, and the `@Sandboxed`/`@Container` JUnit 5
  extension — plus the `SandboxBackend`/`BackendProvider` SPI that lets
  alternative runtimes plug in via `ServiceLoader`.
- **Docker backend** (`backend-docker`): a `SandboxBackend` implementation on
  `docker-java`, serving as the correctness oracle for the microVM backend and
  the fallback runtime on platforms microVMs can't reach.
- **microsandbox backend** (`backend-microsandbox`): a `SandboxBackend`
  implementation on [microsandbox](https://github.com/superradcompany/microsandbox)
  (`msb`), including self-provisioning runtime download/install
  (SHA-256-verified, cached under `~/.cache/rightsize/`), attached-mode
  container supervision, pre-allocated ports for brokers that bake in their
  advertised listeners, and exec-tunnel network-alias emulation for
  cross-container connectivity.
- **Modules** (`modules`): preconfigured containers with sensible waits and
  connection helpers — `RedisContainer`, `ArangoContainer`, `MemcachedContainer`,
  `MongoDBContainer` (single-node replica set, auto-initiated),
  `PostgreSQLContainer` (`jdbcUrl`; `withUsername`/`withPassword`/`withDatabase`),
  `MySQLContainer` (`jdbcUrl`; `withUsername`/`withPassword`/`withDatabase`;
  readiness pinned to the real server's `port: 3306` log line, not the temp-boot
  or X-Plugin lines), `PinotContainer` (`controllerUrl`/`brokerUrl`; single-container
  QuickStart cluster; `withMemoryLimit(4096)` default, measured against the image's
  own `-Xmx4G` heap request), `RedpandaContainer`, `KafkaContainer` (KRaft single node),
  `SpringCloudConfigContainer`, `RabbitMQContainer` (`amqpUrl`/`managementUrl`; management
  plugin enabled), `MariaDBContainer` (`jdbcUrl`; readiness pinned to the real server's
  `port: 3306` log line, following `MySQLContainer`'s precedent), `WireMockContainer`
  (`baseUrl`/`adminUrl`; health-checked on `/__admin/health`), `ClickHouseContainer`
  (`httpUrl`; HTTP-interface query helpers, health-checked on `/ping`), and
  `KeycloakContainer` (`authServerUrl`/`managementUrl`; `KC_BOOTSTRAP_ADMIN_USERNAME`/
  `PASSWORD` — the 26.x env names, not the legacy `KEYCLOAK_ADMIN`; health-checked on
  `/health` on the **management port 9000**, not 8080; `withMemoryLimit(1024)` default), and
  `Neo4jContainer` (`httpUrl`/`boltUrl`; HTTP Cypher transaction endpoint, no bolt driver
  dependency needed; readiness pinned to the real server's `Started.` log line;
  `withMemoryLimit(1024)` default — the image refuses to start under msb's default microVM
  RAM budget with an explicit memory-configuration error, not an OOM kill), and
  `FlociContainer` ([floci.io](https://floci.io) cloud emulators; `aws()`/`azure()`/`gcp()`
  factory functions presetting image and port; `endpointUrl`; health-checked on `/health` —
  the AWS variant's LocalStack-compatible `/_localstack/health` does not carry over to the
  Azure/GCP variants, but plain `/health` answers `200` on all three; unsigned REST, no AWS
  SDK dependency needed for the S3-shaped surface; tiny native-Quarkus images, no memory
  override needed on either backend), and `FlinkContainer` (`restUrl`; `withTaskManager()`
  adds a companion TaskManager on a shared network for a real session cluster with task
  slots — **docker only**: throws `UnsupportedByBackendException` on the microsandbox
  backend, because msb's network-link emulation requires `nc`/busybox inside the consumer
  image and the official Flink image has neither; a bare JobManager, `REST /overview` only,
  is fully supported on both backends; `withMemoryLimit(1024)` default on both roles).
- **BOM** (`bom`): a `java-platform` module aligning versions across the four
  published artifacts.
- **Automatic backend selection**: `RIGHTSIZE_BACKEND` env var to force a
  backend; otherwise microsandbox on macOS Apple Silicon or Linux with
  `/dev/kvm`, falling back to Docker.
- **CI**: a GitHub Actions matrix covering the unit suite plus integration tests
  on Linux (KVM), macOS (Apple Silicon), and a Docker-only fallback job.
- **JaCoCo coverage floor** on `core` (80% line / 70% branch), gating `check`.
- Packaging/OSS groundwork: Apache-2.0 `LICENSE` + `NOTICE`, Maven publishing POM
  metadata, `.editorconfig`, `.github/CONTRIBUTING.md`, `.github/RELEASING.md`.
- **Memory limit knob**: `GenericContainer.withMemoryLimit(megabytes)`, mapped to msb's
  `-m ${mb}M` and Docker's `HostConfig.memory`; `SpringCloudConfigContainer` now defaults to
  1024M so its Paketo-buildpack JVM image boots under microsandbox's default microVM sizing.

### Changed

- **Documentation moved from `docs-site/` to `docs/`.** `mkdocs.yml`'s `docs_dir`
  and `edit_uri` were updated to match; no doc content or URL paths changed.
- **Pinned microsandbox runtime bumped from 0.6.2 to 0.6.3.** The provisioner
  downloads and SHA-256-verifies the new release on first use (existing `0.6.2`
  caches are left in place and simply stop being used). The full integration
  matrix passes unchanged on both backends against 0.6.3, and the backend
  behaviors the msb backend compensates for were re-verified as still present:
  detached `msb run` still never starts the image ENTRYPOINT, and `msb logs -f`
  still never exits after its sandbox stops.

[Unreleased]: https://github.com/ngriaznov/rightsize-kotlin/commits/main
