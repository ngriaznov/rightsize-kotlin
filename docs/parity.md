# Cross-Language Parity

rightsize ships as three independent libraries — Kotlin, Rust, and TypeScript — each with
its own idiomatic API. The claim this page documents: **the same container spec produces the
same observable behavior in all three, on both backends.** A `GenericContainer` built the
same way behaves the same way whether your test suite is written in Kotlin, Rust, or Node,
and whether it runs on the microsandbox or Docker backend. This isn't a promise taken on
faith — every language's contract suite verifies it directly, tagged and run in CI on both
backends.

## Verified behavior areas

| Area | What's verified |
|---|---|
| Lifecycle | `start()`/`stop()` are idempotent — a second `stop()` on an already-stopped container is a safe no-op, and a half-started container tears itself down before its exception propagates. |
| Host port mapping | An exposed guest port is published on `127.0.0.1` at a real, reachable host port, resolvable via `getMappedPort`. |
| Env / command propagation | `withEnv(...)` values are visible to the workload process; `withCommand(...)` overrides the image's default command/entrypoint args. |
| File copy-in | `withCopyFileToContainer` round-trips a classpath resource and a host-path file into the guest at the given path; the default read-only mount rejects an in-guest write (where the backend enforces it — see [Backends](backends.md#backend-differences)). This is a start-time mount, configured before `start()` — see [Copying Files](copy.md) for the runtime equivalent below. |
| Runtime file copy | `copyFileToContainer` / `copyContentToContainer` / `copyFileFromContainer` round-trip files, in-memory content, and directories against a RUNNING container on both backends; destination parents are created automatically; both operations require a running container and fail with a typed error otherwise. |
| Exec | `execInContainer` returns the workload's real exit code, stdout, and stderr. |
| Logs + follow | `logs` captures stdout the workload has already produced; `Wait.forLogMessage` blocks until a pattern appears; `followOutput` streams lines in arrival order with no duplicates, delivers a final unterminated line once the workload exits, and stops delivering once closed — including on the platform-specific follow channels each backend actually has to use (see [Backends](backends.md#backend-differences)). |
| Wait strategies | `Wait.forHttp`, `Wait.forListeningPort`, and `Wait.forLogMessage` all gate `start()` correctly, each respecting `withStartupTimeout`. |
| Networks and aliases | Two containers on the same `Network` reach each other by alias at the exposed guest port, on both backends' actual connectivity mechanism (native bridge networking on Docker, exec-tunnel emulation on microsandbox). |
| Boot-failure retries | A boot that fails with a backend's own known-transient signature — microsandbox's state-database migration race, its image-cache corruption — is retried automatically (self-heal on the image-cache case) rather than failing the whole `start()` on a race the backend itself will resolve. |
| Reaping ledger + sweep | Starting a container appends one line to the current process's ownership ledger; a clean `stop()` removes it; a sweep against a fabricated dead-process record actually reaps the sandbox it names. |
| Reuse gating + identity hash | `withReuse()` alone does nothing without the matching environment opt-in; a second, independently constructed container with an equivalent configuration adopts the first's still-running sandbox instead of creating a new one. The identity hash is pinned to one exact vector, identical byte-for-byte across all three languages: the spec `{image: "redis:7-alpine", env: {A: "1", B: "2"}, command: [], exposedPorts: [6379], memoryLimitMb: null, copies: []}` hashes to `799aad5a3338ce3d36999c7ff2733d4673c0592d417563f334544693ec1907a5`. |
| Capabilities | `capabilities.hardwareIsolated` and `capabilities.checkpoint` report each backend's actual, fixed values (microsandbox: isolated, and checkpoint via disk snapshot; Docker: not isolated, checkpoint via image commit) — never a guess, never negotiated at runtime. |
| `requireIsolation` gating | A container marked to require hardware isolation fails `start()` immediately, before any network/port/create work, when the active backend doesn't provide it. |
| Diagnostics report format | `Diagnostics.report()`'s output — container name, image, state, host, mapped ports, last 50 log lines — is pinned line-for-line against a fixed fixture, so the same inputs render byte-identical text regardless of which language produced them. |
| Checkpoint gating | `checkpoint()` succeeds on both real backends and throws a typed, backend-naming error on a backend without the capability, before any backend call; restoring a checkpoint under a different active backend than the one that created it fails with a typed mismatch error before any backend work. |
| Named checkpoints | A checkpoint created with a name persists a registry entry (one JSON file per name under the rightsize cache directory, pinned field names) and is rediscoverable in any later process via find/list/remove; re-checkpointing a name replaces its artifact and entry; a stale entry whose artifact is gone resolves to absent and is cleaned up. |

## The sibling repos

rightsize is published once per language, sharing this contract:

- **Kotlin** (this repo) — [ngriaznov.github.io/rightsize-kotlin](https://ngriaznov.github.io/rightsize-kotlin/)
- **Rust** — [ngriaznov.github.io/rightsize-rust](https://ngriaznov.github.io/rightsize-rust/)
- **TypeScript / Node** — [ngriaznov.github.io/rightsize-node](https://ngriaznov.github.io/rightsize-node/)

Each library is independently idiomatic — builder-style in Kotlin, a fluent async API in
Rust, an ESM TypeScript API in Node — but a container spec expressed the same way in any of
them produces the same observable behavior described above.

## How the contract is enforced

Every behavior area in the table is a real, executable test, not a design intention. In this
repo, the shared behavioral contract lives in
[`core/src/testFixtures/kotlin/dev/rightsize/contract/BackendContractTest.kt`](https://github.com/ngriaznov/rightsize-kotlin/blob/main/core/src/testFixtures/kotlin/dev/rightsize/contract/BackendContractTest.kt) —
one abstract JUnit 5 class, subclassed once per backend module
(`backend-docker`, `backend-microsandbox`), so every test in it runs against both backends
unchanged. Networking and boot-failure-retry coverage that needs a real multi-container or
real-backend setup lives alongside it as further `@Tag("sandbox-it")` integration tests in
`modules` and `backend-microsandbox`. All of it runs via
`RIGHTSIZE_BACKEND=<backend> ./gradlew integrationTest` (see [Backends](backends.md) and the
[testing section of CONTRIBUTING.md](https://github.com/ngriaznov/rightsize-kotlin/blob/main/.github/CONTRIBUTING.md)),
and CI runs the full matrix on every push. The Rust and Node repos verify the identical
behavior areas through their own equivalent contract suites, run the same way against their
own two backends.
