# Contributing to rightsize

Thanks for considering a contribution. rightsize is a Testcontainers-shaped Kotlin
library that runs containers as microsandbox microVMs (with a Docker fallback), so
most contributions touch either `core` (backend-agnostic engine/SPI), one of the two
backend modules, or `modules` (preconfigured containers). Read on for how to build,
test, and submit changes.

## Prerequisites

- JDK 17 (the project builds and runs tests on Java 17; `jvmToolchain(17)` is set for
  every module, so Gradle will provision it if you don't have a matching JDK).
- Git.
- To run integration tests against a real backend, at least one of:
  - **microsandbox**: macOS on Apple Silicon, Linux (x86_64/arm64) with a readable
    `/dev/kvm`, or Windows (x86_64/arm64) with Windows Hypervisor Platform enabled
    (upstream beta). rightsize self-provisions the `msb` binary on first use — no
    manual install required. See [Backends](README.md#backends) for the full
    compatibility matrix, including Intel Mac and WHP-less-Windows caveats.
  - **Docker**: any Docker-compatible daemon reachable at the default socket, or via
    `DOCKER_HOST` / the active `docker` CLI context. On Windows this means a
    unix-socket-reachable daemon (e.g. Docker Engine inside WSL) — the backend's
    transport does not speak Windows named pipes.

## Building

```bash
./gradlew build
```

This compiles every module, runs the unit test suite (tests **not** tagged
`sandbox-it`), and — for `core` — enforces the JaCoCo coverage floor (80% line /
70% branch). `build` must stay green on every commit; it never touches a sandbox
runtime, so it works offline and in any environment.

## Running integration tests

Tests that need a real backend are tagged `sandbox-it` and excluded from the plain
`test` task. Run them explicitly via the `integrationTest` task, forcing a backend
with `RIGHTSIZE_BACKEND`:

```bash
# microsandbox backend (needs Apple Silicon, Linux + /dev/kvm, or Windows + WHP)
RIGHTSIZE_BACKEND=microsandbox ./gradlew integrationTest

# Docker backend (needs a reachable Docker daemon)
RIGHTSIZE_BACKEND=docker ./gradlew integrationTest
```

Both backends satisfy the same behavioral contract (see the shared contract test
suites in `backend-docker` and `backend-microsandbox`), so a change that affects
observable behavior should be exercised on **both** before you open a PR — CI runs
the full matrix (unit, `msb-linux`, `msb-windows`, `docker-fallback`; see
`.github/workflows/ci.yml`), but a local run catches problems faster.

> **macOS in CI:** there is no `msb-macos` job — GitHub's hosted Apple Silicon
> runners are themselves VMs without nested virtualization, so microVMs cannot
> boot there (Hypervisor.framework rejects VM creation). macOS support is
> verified on real Apple Silicon hardware before release.


Useful env vars while developing (full reference in
[Configuration](README.md#configuration)):

| Variable | Purpose |
| --- | --- |
| `RIGHTSIZE_BACKEND` | Force `microsandbox` or `docker`. Required for `integrationTest`. |
| `MSB_PATH` | Point at an already-installed `msb` binary, skipping auto-provisioning. |
| `RIGHTSIZE_CACHE_DIR` | Relocate the provisioned-runtime cache (default `~/.cache/rightsize`; `%LOCALAPPDATA%\rightsize` on Windows). |
| `DOCKER_HOST` | Non-default Docker socket (Colima, OrbStack, remote daemon, etc.). |

### The `sandbox-it` tag convention

Any test that starts a real container (directly, via `@Sandboxed`/`@Container`, or
transitively) must be annotated `@Tag("sandbox-it")`. This is how `test` and
`integrationTest` partition the suite — a test without the tag that touches a
runtime will either fail unpredictably in plain `./gradlew build` (no backend
guaranteed available) or, worse, pass by accident in one environment and not
another. When adding a new IT, follow the existing pattern in the module you're
touching (e.g. `MsbBackendIT`, `DockerBackendIT`, the shared contract suites) rather
than inventing a new structure.

## Making changes

- **No public renames or signature breaks.** rightsize's public classes deliberately
  mirror Testcontainers' simple names exactly (`GenericContainer`, `Network`, `Wait`,
  `@Container`, `MountableFile`, `Startables`, the module container names, etc.), so
  users familiar with Testcontainers can read rightsize code at a glance. If your
  change would rename or remove a public declaration, discuss it first — it's a
  deliberate constraint, not an oversight.
- Match the existing KDoc voice: terse, one-clause docs on straightforward
  members; fuller multi-paragraph KDoc reserved for behavior that isn't obvious from
  the code (retry loops, backend quirks, SPI contracts) — document intent,
  invariants, and gotchas, not what the next line of code does.
- Keep the codebase's dense, comment-where-it-earns-it style — this is not a
  license to reformat files you're touching for unrelated reasons.
- Follow the `.editorconfig` in the repo root (Kotlin: 4-space indent, no tabs,
  final newline, trimmed trailing whitespace).

## Pull request expectations

- `./gradlew build` must pass. If your change affects backend behavior, also run
  `integrationTest` against both backends (see above) and mention the results in
  the PR description.
- Keep changes focused — prefer several small, reviewable commits over one large
  diff, especially when a change spans `core` and multiple backend/module
  implementations.
- Describe *why*, not just *what*, in commit messages and the PR description,
  especially for anything touching timing, retries, or backend-specific
  workarounds — these are exactly the kind of decisions that are expensive to
  re-derive later.
- New public API needs KDoc (see the voice guidance above) — this project aims to
  keep 100% KDoc coverage on its public surface.

## Release process

rightsize is not yet published to a shared Maven repository — see
[RELEASING.md](RELEASING.md) for the release checklist, including the steps that
only make sense once the repository has a real GitHub remote (badges, CI status
links, etc.).

## Questions

Open an issue for anything not covered here — including if you think one of the
constraints above is wrong.
