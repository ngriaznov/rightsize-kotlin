# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to adhere to [Semantic Versioning](https://semver.org/) once it
reaches its first tagged release.

## [Unreleased]

### Fixed

- **The microsandbox backend retries a boot that lost msb's startup-migration
  race.** Every msb invocation runs schema migrations against its shared SQLite
  state database on startup, and two concurrent invocations can race them — the
  loser exits with `database error: ... index ... already exists` (or the
  `UNIQUE constraint failed: seaql_migrations.version` shape) before doing any
  work. A boot is never inherently alone (the attached `msb run` races the
  backend's own state polling), so this can fire even under fully serialized
  tests. Transient by construction — the winner's migration commits and later
  invocations find the schema in place — so a boot failing with that signature
  is retried exactly once after a short delay; a second loss propagates with
  both attempts' output.

## [0.1.0] - 2026-07-06

Initial public release.

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
  backend; otherwise microsandbox on macOS Apple Silicon, Linux with
  `/dev/kvm`, or Windows with Windows Hypervisor Platform, falling back to
  Docker.
- **CI**: a GitHub Actions matrix covering the unit suite plus integration tests
  on Linux (KVM) and Windows (`windows-2025`, WHP), and a Docker-only fallback
  job; macOS (Apple Silicon) has no hosted-runner lane (GitHub's Apple Silicon
  runners don't support nested virtualization) and is verified on real
  hardware instead.
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
- **Native Windows support for the microsandbox backend** (x86_64/arm64), gated
  on Windows Hypervisor Platform: two new `Platform` rows carrying the
  `msb-windows-*.exe`/`libkrunfw-windows-*.dll` release assets; the provisioner
  installs a platform-derived binary name (`msb.exe` on Windows, suffixless
  `msb` elsewhere) and gates install-completeness on a plain file-exists check
  on Windows rather than the POSIX executable bit; the default cache root on
  Windows is `%LOCALAPPDATA%\rightsize` (`RIGHTSIZE_CACHE_DIR` still overrides
  it everywhere). `MsbBackendProvider` attempts microsandbox on any detected
  Windows platform and surfaces a WHP-specific `unsupportedReason` (pointing at
  `msb doctor --fix`) if boot fails, rather than probing before the fact — CI
  (`msb-windows`, `windows-2025`) confirms this is safe because GitHub's hosted
  Windows runners ship with WHP already enabled. Attached-mode `msb run` on
  Windows does not relay the guest's stdout to the parent process (confirmed
  empirically); the backend already sources workload logs from `msb logs`
  exclusively, which was the only channel that ever worked correctly for that
  purpose on Windows. Process teardown documented, not changed: the JVM has no
  POSIX signals on Windows, so `destroy()`/`destroyForcibly()` both resolve to
  `TerminateProcess`, which is safe here because the actual graceful shutdown
  is the `msb stop`/`msb rm` invocation that already precedes killing the
  attached child on every platform. One more msb-Windows gap is compensated
  rather than surfaced: `msb logs -f` on Windows stays alive but never relays
  lines while the sandbox runs, so `followOutput` there polls fresh `msb logs`
  snapshots instead of holding a `logs -f` pipe — a failed msb invocation
  reads as no-signal, and the terminal tail is delivered exactly once after
  the sandbox stops, including a final line with no trailing newline. The
  full contract suite runs un-gated on Windows.

### Fixed

- **The microsandbox backend self-heals msb's image-cache race.** Concurrent
  pulls of images sharing base layers can corrupt msb's image cache — the
  losing pull reads a layer tarball the winner's cleanup already deleted, and
  every later boot of that image fails with `cache error at
  .../layers/<sha>.tar.gz: No such file or directory`. A boot failing with
  that signature now removes the affected image from msb's cache
  (`msb image remove`, scoped to the one reference) and retries the boot
  exactly once; any other failure, or a second failure after the heal,
  propagates unchanged.

[Unreleased]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ngriaznov/rightsize-kotlin/releases/tag/v0.1.0
