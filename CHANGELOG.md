# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to adhere to [Semantic Versioning](https://semver.org/) once it
reaches its first tagged release.

## [Unreleased]

### Changed

- **The pinned microsandbox release is unified back to a single version, 0.6.14, on
  every platform.** The per-platform split introduced in 0.7.2 is retired: upstream's
  msb_krun/msb_krun_devices 0.1.32 bump (microsandbox issue #1426) removes the
  Windows-only code path that started console-port delivery at `PORT_READY` — kernel
  probe time, before any guest process has the port open, so the guest driver
  discarded the bootstrap frame — and ports now start delivery at `PORT_OPEN` instead,
  matching unix. 0.6.14 was channel-verified on a windows-2025 GitHub runner with the
  official binary: sandboxes boot, exec works through the agent relay, the sandbox
  survives well past the old 60-second death window, and `msb logs` streams guest
  output. 0.6.12 through 0.6.14 change nothing else of substance for the CLI surface
  this library drives, so unifying both platforms on 0.6.14 does not change behavior
  on macOS or Linux.

  **If you point `MSB_PATH` at your own msb binary on Windows, avoid 0.6.10 through
  0.6.13** — those releases still hit the bootstrap regression on every container
  start. Use 0.6.9 or 0.6.14+.

## [0.7.3] - 2026-08-21

### Changed

- **The pinned microsandbox release is now 0.6.12 on macOS and Linux; Windows stays
  on 0.6.9.** The Windows-only bootstrap regression introduced in 0.6.10 is still
  present in 0.6.11 and 0.6.12: since 0.6.10, guest bootstrap moved off the kernel
  command line onto a one-shot pre-boot console frame, and on Windows hosts that
  frame never reaches agentd (the guest's PID 1), so agentd times out after 60
  seconds and the guest dies. The sandbox can briefly report Running, but the agent
  relay endpoint is never created, so exec/logs/ping can never connect. There is no
  environment variable, CLI flag, or other client-side workaround. macOS and Linux
  are unaffected. The CLI surface this library drives is identical from 0.6.9
  through 0.6.12 (no core source changes landed in that span), so the per-platform
  pin does not change behavior — the provisioner simply keeps routing Windows around
  the broken releases until upstream fixes bootstrap delivery.

  **If you point `MSB_PATH` at your own msb binary on Windows, keep it at 0.6.9** —
  a 0.6.10, 0.6.11, or 0.6.12 binary there will hit the regression on every
  container start.

## [0.7.2] - 2026-08-19

### Changed

- **The pinned microsandbox release is now 0.6.10 on macOS and Linux; Windows stays
  on 0.6.9.** msb 0.6.10 has a Windows-only regression: its pre-boot guest bootstrap
  message never reaches the guest agent on Windows hosts, so every sandbox exits
  about 70 seconds after spawn without the agent ever coming up. macOS and Linux are
  unaffected. The two releases are identical across every CLI surface this library
  drives, so the per-platform pin does not change behavior — the provisioner simply
  routes Windows around the broken release until upstream fixes it.

  **If you point `MSB_PATH` at your own msb binary on Windows, keep it at 0.6.9** —
  a 0.6.10 binary there will hit the regression on every container start.

## [0.7.1] - 2026-08-16

### Changed

- **The pinned microsandbox release is now 0.6.9** (was 0.6.8). No CLI surface this
  library drives changed, so no action is needed — the provisioner downloads and
  checksum-verifies the new release automatically, and `MSB_PATH` setups validated
  against 0.6.8 keep working. 0.6.9 also fixes two upstream issues this library
  carried defenses for: the Windows snapshot-save flush failure (the salvage path
  stays in place and now simply never fires) and the concurrent-pull image-cache
  race (the heal path likewise remains as a safety net).

## [0.7.0] - 2026-08-04

### Added

- **`withDiskLimit(megabytes)`** caps a container's writable root disk — msb-only
  (`--root-disk <mb>M`); Docker runs without a ceiling and ignores it. On an msb reboot
  the cap can only grow, never shrink. Mutually exclusive with `withTmpfsRoot` — setting
  both throws `RootDiskConflictException` at `start()`.
- **`withTmpfsRoot(megabytes)`** backs the root disk with RAM instead of storage —
  faster ephemeral containers, no disk residue — msb-only; Docker ignores it. Must fit
  inside the guest's memory: msb defaults to 512M when `withMemoryLimit` is unset, and
  setting both with a tmpfs size larger than the memory limit throws
  `TmpfsRootExceedsMemoryException` at `start()`. A tmpfs root cannot be checkpointed —
  `checkpoint()` throws `TmpfsRootCheckpointException` before touching anything, and a
  refused named re-checkpoint leaves the existing checkpoint intact. msb also rejects
  any root-disk setting on a `fromCheckpoint` restore before boot, since the snapshot
  already pins the root disk.
- **`withNetworkDisabled()`** blocks public-internet access — msb-only, emitted as
  `--net private`: published ports keep serving and private-range network links keep
  working, but outbound connections to the public internet fail. Docker ignores this
  flag entirely — there's no portable way to block egress there while keeping published
  ports. Cannot be combined with `withNetwork()` — throws `NetworkDisabledConflictException`
  at `start()`.

### Changed

- **msb checkpoint artifacts now live under `<cache-dir>/checkpoints/`** (the same
  rightsize cache directory the named-checkpoint registry already uses —
  `~/.cache/rightsize` on macOS/Linux, `%LOCALAPPDATA%\rightsize` on Windows), written
  via msb's own `--dest-dir` instead of its default `~/.microsandbox/snapshots/`
  location. `Checkpoint.ref` for the microsandbox backend is now the absolute artifact
  path — `ref` stays an opaque string in the public API, and a bare-name ref from an
  earlier release still restores. The snapshot still shows up in `msb snapshot list`
  (msb keeps its own global index); removing it through the library cleans both the
  index entry and the artifact. `exportTo`/`importFrom` are unaffected.

### Fixed

- **The boot-retry classifier now also recognizes msb's second install-lock phrasing** —
  `another microsandbox install operation is in progress until <timestamp>`, not just the
  original `microsandbox install operation in progress until <timestamp>`. A boot that hit
  the second wording used to fail outright instead of being retried like the first.

## [0.6.2] - 2026-08-01

### Fixed

- A failed container create no longer leaks its pre-allocated host ports or its
  reaper ledger entry: the create-error path now returns the ports to the
  allocator and removes the just-appended ledger line, as the reuse path and the
  start-error path already did. Without this, every failed create attempt
  permanently retired its ports from the pool for the life of the process.

## [0.6.1] - 2026-08-01

### Changed

- **The pinned microsandbox release is now 0.6.8** (was 0.6.6). The provisioner
  downloads and checksum-verifies it automatically, so no action is needed for the
  usual setup.

  **If you point `MSB_PATH` at your own msb binary, it must be 0.6.8 or newer.**
  0.6.8 renamed three CLI surfaces this library drives, and the calls it now emits do
  not exist in 0.6.6:

  | 0.6.6 | 0.6.8 |
  |---|---|
  | `run --snapshot <ref>` | `run --from-snapshot <PATH_OR_NAME>` |
  | `snapshot export <ref> <dest>` | `snapshot save <SNAPSHOT> <OUT>` |
  | `snapshot import <archive>` | `snapshot load <ARCHIVE> [DEST]` |

  Checkpoint restore and checkpoint archives are the affected features; both fail
  outright against an older binary rather than degrading quietly.

- **A loaded snapshot's effective ref is now a bare 64-character digest**, where 0.6.6
  produced a `sha256-<16hex>` directory name. Nothing in the public API changes — the
  ref was always opaque and content-addressed — but code that pattern-matched the old
  shape will need updating.

- **`FileMount.readOnly` now defaults to `false`, and the flag is genuinely enforced on
  the microsandbox backend.** It previously never reached msb, so every mount there was
  writable regardless of the flag; the Docker backend enforced it all along. What a
  caller observes: a default `withCopyFileToContainer` mount on the Docker backend was
  read-only before and is writable now — pass `readOnly = true` to get the old Docker
  behavior, which both backends now honor as a guest-side write block. The mount is a
  view of the host file, not a copy, so a guest write reaches the host file itself.

### Fixed

- **File mounts work on Windows.** msb 0.6.7 broke every start-time file mount there:
  its mount-spec parsing splits a token-less spec at the drive letter's colon, both on
  the CLI spec and again on an internally rebuilt one. Every mount spec this backend
  emits now carries an explicit `ro`/`rw` access token plus `nodev`, which keeps both
  layers parseable. `nodev` is meaningless for a single-file mount — there are no
  device nodes to block.

- **Checkpoint archives export on Windows again.** msb 0.6.7/0.6.8 fail every
  `snapshot save` there with `Access is denied. (os error 5)`: the finished archive is
  fsynced through a read-only handle one step before the final rename. When exactly
  that failure occurs with exactly one finished staging file beside the destination,
  the backend completes the rename itself. Transparent, Windows-only, and
  self-disabling once msb fixes the fsync.

- **Container boot rides out msb's transient `install operation in progress` refusal**
  by polling for up to 30 seconds instead of failing on the first attempt.

- The Cassandra module's `GPG_KEYS` override remains required: 0.6.8 still aborts
  before the VM starts on any image whose baked environment contains a tab, verified
  directly against this release.

## [0.6.0] - 2026-07-28

### Upgrading from 0.5.0

Two changes affect existing code.

**Modules no longer pin an image version.** `RedisContainer()` previously booted
`redis:8.6-alpine`; it now boots `redis:latest`. Your tests will run whatever version
upstream currently publishes, which is the point — the version tracks the image's own
releases rather than this library's. To keep a specific version, name it:
`RedisContainer("redis:8.6-alpine")`. Redis, Valkey, PostgreSQL, and Memcached
additionally move from an Alpine variant to the Debian-based `latest`: functionally
equivalent, noticeably larger to pull.

**`ElasticsearchContainer` has no no-arg constructor.** Elastic publishes no floating
tag — `elasticsearch:latest`, `:9`, and `:8` are all `404` on Docker Hub — so an
explicit version is required and there is nothing this module could pick on your
behalf: `ElasticsearchContainer("elasticsearch:9.4.4")`.

An explicitly supplied image is also now checked against the repository the module
understands, so passing an unrelated image fails immediately with
`IncompatibleImageException` instead of timing out against the wrong server. If the
image really is a drop-in replacement, say so:
`DockerImageName.parse("mycorp/pg-hardened:16").asCompatibleSubstituteFor("postgres")`.

### Added

- **`DockerImageName`** (`dev.rightsize.core.image`) — a parsed Docker image reference
  (registry, repository, tag, digest), built via `DockerImageName.parse(String)`. Module
  constructors that accept an explicit image now check its repository against the one the
  module understands before any port, wait-strategy, or backend work runs, failing fast with
  the new typed `IncompatibleImageException` on a mismatch rather than degrading into a bare
  wait-strategy timeout. `DockerImageName.asCompatibleSubstituteFor(String)` is the escape
  hatch for a private mirror, a hardened rebuild, or a rename. Registry-host stripping follows
  the Docker convention: the first path segment is a registry only if it contains a `.` or a
  `:`, or is exactly `localhost`.
- **`ElasticsearchContainer`** — a single-node Elasticsearch container. Elastic publishes no
  floating tag for this image (`elasticsearch:latest`/`:9`/`:8` are all `404` on Docker Hub),
  so this module has no no-arg constructor — an explicit image is required. Readiness checks
  plain connectivity rather than cluster health, since a single node's health stays `yellow`
  forever (no peer to place replica shards on).
- **`QdrantContainer`** — a single-node Qdrant vector database container, defaulting to
  `qdrant/qdrant:latest`. Readiness is Qdrant's own `/readyz` probe, which answered on the
  first poll in direct verification; no memory limit is needed.

### Changed

- **Every one of the 21 pre-existing modules now defaults to a floating image reference**
  instead of a pinned version, and checks any explicitly supplied image against the
  repository it understands via `DockerImageName` (see above). Most float to
  `<repository>:latest`; `RabbitMQContainer` floats to `rabbitmq:management` instead, since
  plain `latest` lacks the management plugin the module is built around. Redis, Valkey,
  PostgreSQL, and Memcached move from a pinned Alpine variant to the Debian-based `latest`.
  No env var, port, wait strategy, memory limit, or command changed — each module's own
  KDoc/docs page states which pinned version its readiness signal, memory floor, and timing
  facts were verified against.

### Fixed

- **An `exec` issued immediately after `start()` could fail to reach the guest.** A
  sandbox reports `Running` before the in-guest agent has created the endpoint `exec`
  connects to; the gap is invisible whenever a wait strategy runs first, which is every
  module, but a caller that starts and execs at once could lose the race — reliably so on
  Windows, where the endpoint is a named pipe. `exec` now retries on that one signature.
  A guest command's own non-zero exit, and any agent error raised after connecting, still
  return on the first attempt.
- **`MongoDBContainer`'s replica-set budget is now 180s**, up from 60s. `rs.initiate`
  was observed failing at exactly the 60s mark on a loaded Windows CI runner against the
  floating default, matching the budget MySQL and ClickHouse already carry.

## [0.5.0] - 2026-07-25

### Added

- **`ValkeyContainer`** — a single-node Valkey container, the Redis-protocol-compatible
  fork. Readiness is anchored on Valkey's own `Ready to accept connections` log line, and
  `uri` returns a `redis://` URI because that is the scheme every Redis-protocol client
  parses.
- **`MinIOContainer`** — a single-node MinIO server, S3-compatible object storage. The
  image needs an explicit `server /data --console-address :9001` command, which this module
  always sets; readiness is MinIO's own `/minio/health/live` probe on the S3 API port.
  Defaults to a `testuser`/`testpassword` root pair, since MinIO rejects a root password
  shorter than eight characters.
- **`CassandraContainer`** — a single-node Apache Cassandra, ready-checked on its
  `Starting listening for CQL clients` log line. The module overrides the image's baked
  `GPG_KEYS` value, which contains a tab: the microsandbox backend aborts before the VM
  starts on any image whose baked environment carries one. `GPG_KEYS` is consumed only at
  image-build time, so the override has no effect on the running server.

## [0.4.0] - 2026-07-18

### Added

- **Checkpoint export/import (portable archives).** `Checkpoint.exportTo(path)`/`Checkpoint.importFrom(path)`
  package a checkpoint into a single portable tar (pinned `checkpoint.json` metadata plus the
  backend's own artifact — a docker `save` tar or an msb `snapshot export` `.tar.zst`) so it can
  ride along in a CI cache or move between machines running the same backend, instead of being
  re-seeded from scratch on every runner. `exportTo` requires the active backend to match the
  checkpoint's own and the artifact to still exist, both checked before any backend or filesystem
  work; `importFrom` validates the archive (format version, name grammar, backend match) before
  any backend call, then re-registers a NAMED archive with the same replace semantics
  `checkpoint(name)` uses. The image itself is never bundled — the destination pulls it on the
  restored container's first boot. `SandboxBackend.exportCheckpoint`/`importCheckpoint` are the
  new backend-side SPI primitives; on microsandbox, import mints a fresh digest-shaped effective
  ref (the original snapshot name is never preserved), while docker's `load` round-trips the
  original tag unchanged. See [Checkpoint / Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/#moving-checkpoints-between-machines).

## [0.3.0] - 2026-07-16

### Added

- **Runtime file copy.** `GenericContainer.copyFileToContainer`/`copyContentToContainer`/
  `copyFileFromContainer` move a file, a directory, or in-memory content into or out of an
  already-**running** container — distinct from the existing start-time
  `withCopyFileToContainer` mount. Both directions require the container to be running and the
  container-side path to be absolute (typed errors, checked before any backend call), and both
  automatically create the destination's parent directory (in the guest via `exec`, on the host
  via the stdlib) so callers never pre-create it. Directory-vs-file source and "copy into a
  nonexistent destination" naming follow `docker cp`/`msb copy`'s own `cp -r`-style semantics on
  both backends; docker shells out to the `docker` CLI's own `cp` (already a hard dependency via
  the reaper's watchdog commands) rather than hand-rolling tar encode/decode against the daemon
  API, and microsandbox uses `msb copy`. See [Copying Files](https://ngriaznov.github.io/rightsize-kotlin/copy/).
- **Checkpoint / restore, including named/persistent checkpoints.** `checkpoint()`/`fromCheckpoint`
  work on both backends: docker commits the running container to an image, microsandbox drives a
  disk snapshot (`msb stop` → `msb snapshot create` → `msb rm` → a fresh attached
  `msb run --snapshot <ref>` under the same name/ports/env/memory limit) — so
  `capabilities.checkpoint` is `true` on both, and `capabilities.checkpointRestartsWorkload`
  (`true` on microsandbox, whose snapshot cycle restarts the workload; `false` on docker, which
  leaves the container undisturbed) governs whether `checkpoint()` re-applies the container's own
  wait strategy before returning. `Checkpoint` carries `ref` (backend-shaped: a docker image tag
  or an msb snapshot name), `backend` (the backend that created it), and `spec` (the source
  container's env/command/exposed ports/memory limit) — restoring under a different active
  backend than the creator throws `CheckpointBackendMismatchException` before any backend call,
  naming both backends and the `RIGHTSIZE_BACKEND=<creator>` remedy. `SandboxBackend.createCheckpoint`/
  `removeCheckpoint` are the backend-side primitives (docker `rmi`/msb `snapshot rm` for removal);
  checkpoints are not auto-reaped.
  Passing a name to `checkpoint("seeded-db")` instead makes it durable and rediscoverable from any
  later process: the ref is derived from the name (replacing the random hex), a registry entry is
  written under the rightsize cache directory only after the backend checkpoint succeeds, and
  `Checkpoint.find(name)`/`list()`/`remove(name)` are the cross-process lookup/cleanup API —
  `find` probes the artifact via the new `SandboxBackend.hasCheckpoint` SPI method and resolves a
  stale entry (backend-side artifact gone) to absent, cleaning it up along the way. Re-checkpointing
  an existing name replaces it: the old artifact is best-effort removed before the new one is
  captured, and the registry entry is rewritten only once that capture succeeds. Names must match
  `^[a-z0-9][a-z0-9-]{0,40}$`, checked before any backend call. See
  [Checkpoint / Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/).

### Fixed

- **`MySQLContainer` readiness gets a 180-second budget** (was 120), the same treatment
  `ClickHouseContainer` already got. A loaded Windows CI runner was observed still short of
  ready at 123 seconds — past the previous ceiling. The budget is a deadline, not a wait —
  readiness returns the moment the real server's `ready for connections` line appears.
## [0.2.0] - 2026-07-12

### Added

- **Orphan reaping.** A crashed or `SIGKILL`ed process (no clean `stop()`, no shutdown hook)
  used to leak its sandboxes; now every process writes a small ownership ledger
  (`runs/<run-id>.{json,sandboxes,networks}`) under the rightsize cache dir before its first
  sandbox, and two layers reap a dead run's leftovers: an always-on init-time sweep (runs once
  per process, right after a backend resolves) and an optional per-run watchdog process that
  reacts within seconds of the crash instead of waiting for the next run. Controlled by the new
  `RIGHTSIZE_REAPER` variable (`on` default / `sweep` / `off`). See [Orphan
  Reaping](https://ngriaznov.github.io/rightsize-kotlin/reaping/) for the full mechanism.
- **`SandboxBackend.removeByName`**, a new SPI method reaping uses to remove a sandbox known
  only by name (not a live handle) — both backends implement it; the docker backend gets a
  cross-run orphan sweep for the first time (previously msb-only).
- **`ContainerSpec.keepAlive`**, a new field marking a sandbox as a reuse container. Every
  own-run cleanup path a backend runs consults it: msb never adds a `keepAlive` sandbox to its
  `startedNames` set (so neither the constructor shutdown hook nor `close()` reaps it), and
  docker labels a `keepAlive` container `dev.rightsize.reuse=<12hex>` instead of the run-id
  label at create time (so `close()`'s run-id label filter never matches it). The reaping
  ledger already excluded it.
- **Container reuse.** `GenericContainer.withReuse()` marks a container to survive `stop()`
  and process exit instead of being torn down — the next equivalent container (this process
  or a later one) adopts the still-running sandbox instead of booting fresh. Gated by a double
  opt-in: `withReuse()` alone does nothing unless `RIGHTSIZE_REUSE=true`/`1` is also set, so a
  fixture written with `withReuse()` never silently leaks a sandbox in an environment that
  didn't opt in. Identity is a SHA-256 over a canonical, cross-language-stable rendering of the
  container's image/env/command/exposed ports/memory limit/mounted file contents, named
  `rz-reuse-<12hex>` and tracked in a `<cache-dir>/reuse/<hash>.json` registry. Reuse and a
  custom `withNetwork()` are mutually exclusive (a typed error at `start()`) — reuse identity
  doesn't cover cross-container topology. See [Container
  Reuse](https://ngriaznov.github.io/rightsize-kotlin/reuse/) for the full mechanism.
- **`SandboxBackend.findRunning`**, a new SPI method reuse's adopt path uses to verify a
  registry-recorded sandbox is actually running before trusting it, given only its name.
- **Failure diagnostics.** `Diagnostics.report()` describes every currently-live container —
  name, image, state, host, mapped ports, and its last 50 log lines — as a plain string; a
  failing `logs` call degrades to `logs: unavailable (<reason>)` instead of throwing. The
  `@Sandboxed` extension now prints the report to `System.err` automatically, exactly once per
  failed test. See [Failure
  Diagnostics](https://ngriaznov.github.io/rightsize-kotlin/diagnostics/).
- **Isolation requirement.** `SandboxBackend.capabilities` exposes `hardwareIsolated` per
  backend (microsandbox: `true`, each sandbox its own microVM; docker: `false`, shared host
  kernel). `GenericContainer.withRequireIsolation()` makes `start()` throw
  `IsolationRequiredException` — before any create/network work, so no sandbox is created — when
  the active backend doesn't provide it. See [Isolation
  Requirement](https://ngriaznov.github.io/rightsize-kotlin/isolation/).
- **Checkpoint / restore.** `GenericContainer.checkpoint()` captures a running container's
  filesystem as a new image (`rightsize/checkpoint:<12-hex>`, random per call) via the new
  `SandboxBackend.commitToImage` SPI method; `GenericContainer.fromCheckpoint(cp)` boots a
  normal container from it, its env/command/exposed ports/memory limit defaulted from the
  checkpoint's spec and overridable with the usual `withX` builders. A restored container is
  ordinary in every respect — fresh host ports, normal reaping ledger entry, normal `stop()`.
  Implemented on the docker backend via the engine's commit endpoint
  (`capabilities.checkpoint = true`); microsandbox has no upstream snapshot primitive yet
  (`capabilities.checkpoint = false`) — `checkpoint()` throws `CheckpointUnsupportedException`
  before any backend call. This is a filesystem capture, not a memory snapshot: a restored
  container's processes restart from scratch. See [Checkpoint /
  Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/).
- **Cross-language parity, published.** The claim behind the five features above — that the
  same container spec produces the same observable behavior in rightsize's Kotlin, Rust, and
  Node libraries, on both backends — is now a documented artifact with a full behavior-area
  table (lifecycle, port mapping, env/command, copy-in, exec, logs/follow, wait strategies,
  networks/aliases, boot-failure retries, reaping, reuse identity, capabilities, isolation and
  checkpoint gating, diagnostics format) and a pinned cross-language identity-hash vector. See
  [Cross-Language Parity](https://ngriaznov.github.io/rightsize-kotlin/parity/).

### Changed

- **The old msb-only orphan sweep is gone.** `MsbBackendProvider.create()` used to scan `msb ls`
  once per process and best-effort-remove any `rz-*` name not belonging to the current run —
  liveness-blind (it could remove a sandbox from a *concurrent*, still-running process), msb-only,
  and only ever caught leftovers already visible in `msb ls` at process start. The new
  ledger-based sweep above replaces it, judges liveness properly (PID + start-time match), and
  works identically on both backends.

### Fixed

- **Reuse's fresh-create path retries on a host-port bind conflict**, same as the ordinary
  create path (`PORT_BIND_ATTEMPTS = 5`). It previously gave up after a single attempt, so a
  reuse container could fail to start on the same transient host-port race an ordinary
  container already retries through.

## [0.1.2] - 2026-07-09

### Changed

- **Pinned microsandbox runtime bumped from 0.6.3 to 0.6.6.** The provisioner
  downloads and SHA-256-verifies the new release on first use (existing
  `0.6.3` caches are left in place and simply stop being used). The full
  integration matrix passes unchanged on both backends against 0.6.6, and the
  backend behaviors the msb backend compensates for were re-verified as still
  present: detached `msb run` still never starts the image ENTRYPOINT, and
  `msb logs -f` still never exits after its sandbox stops.

## [0.1.1] - 2026-07-06

### Fixed

- **The default readiness budget is 120 seconds** (was 60). Three modules in a
  row (MySQL, ClickHouse, Redpanda) were observed overrunning a 60-second
  ceiling on loaded CI runners while booting normally. The budget is a
  deadline, not a wait — `start()` still returns the moment the readiness
  signal fires — so the larger default costs nothing on the happy path and
  only delays the failure verdict when a container is genuinely broken.
  `withStartupTimeout` overrides it as before.
- **`ClickHouseContainer` readiness gets a 180-second budget.** The entrypoint
  runs a second server pass for user/database provisioning before the HTTP
  interface opens, and a loaded Windows CI runner was observed still in early
  config processing at the previous 120-second ceiling. The budget is a
  deadline, not a wait — readiness returns the moment `/ping` answers.
- **The microsandbox backend retries a boot that hit msb's state-database
  error** (`error: database error: ...`). Every msb invocation runs schema
  migrations against its shared SQLite state database on startup, and two
  concurrent invocations can race them — the loser exits before doing any
  work, with whatever wording matches the statement it lost on (three shapes
  observed: `index ... already exists`, `duplicate column name: ...`, and
  `UNIQUE constraint failed: seaql_migrations.version`). A boot is never
  inherently alone (the attached `msb run` races the backend's own state
  polling), so this can fire even under fully serialized tests. The race is
  transient by construction — the winner's migration commits and later
  invocations find the schema in place — so a boot failing with msb's
  state-database framing is retried exactly once after a short delay; a
  second failure propagates with both attempts' output.

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

[Unreleased]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.3...HEAD
[0.7.3]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.2...v0.7.3
[0.7.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.1...v0.7.2
[0.7.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.2...v0.7.0
[0.6.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.1...v0.6.2
[0.6.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.0...v0.6.1
[0.6.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ngriaznov/rightsize-kotlin/releases/tag/v0.1.0
