# Backends

rightsize ships two implementations of a single `SandboxBackend` interface:
`backend-microsandbox` (microVMs, via the `msb` CLI) and `backend-docker`
(conventional containers, via `docker-java`). Both satisfy one behavioral contract,
verified by a shared test suite that runs against each — code you write targets
`GenericContainer` and never the backend directly, so the same test runs unchanged on
either.

## Backend selection

Selection is lazy and happens once per JVM, in this order:

1. **`RIGHTSIZE_BACKEND=microsandbox|docker`**, if set, wins outright — and it must be
   usable, or the run fails immediately naming the exact precondition that wasn't met
   (rather than silently falling through to the other backend).
2. Otherwise, **microsandbox** if the platform supports it: macOS on Apple Silicon, or
   Linux with a readable `/dev/kvm`.
3. Otherwise, **Docker** if a daemon socket is reachable.
4. Otherwise, fail — naming the exact precondition that failed for every backend that
   was considered, not just the first one.

Internally this is priority-based (microsandbox = priority 20, Docker = priority 10;
the higher-priority *supported* backend wins when no explicit override is given), but
you don't need to think about it in those terms day to day — the four-step list above
is the whole story that matters from the outside.

## Environment variables

| Variable | Effect |
|---|---|
| `RIGHTSIZE_BACKEND` | Force `microsandbox` or `docker`, overriding auto-selection. |
| `MSB_PATH` | Use a pre-installed `msb` binary; skips the download/provisioning step entirely. |
| `RIGHTSIZE_CACHE_DIR` | Relocate the runtime cache (default `~/.cache/rightsize`). |
| `RIGHTSIZE_MSB_SKIP_DOWNLOAD` | `true` = fail with guidance instead of downloading — for air-gapped CI; pair with `MSB_PATH` or a pre-seeded cache. |
| `DOCKER_HOST` | Standard docker-java variable. The Docker backend also honors the active docker CLI context (`~/.docker/config.json`) — set this if your daemon isn't at the default `/var/run/docker.sock` (Docker Desktop / Colima / OrbStack on a non-default socket, for instance). |

## `backend-microsandbox` deep-dive

### Provisioning

On first use, if no runtime is already cached (or `MSB_PATH` isn't set), rightsize
downloads a pinned `msb` release (currently `0.6.3`) plus its `libkrunfw` companion
library from GitHub releases, matched to your OS/architecture
(`msb-darwin-aarch64`, `msb-linux-x86_64`, `msb-linux-aarch64`, and the corresponding
`libkrunfw-*` asset).

Every downloaded asset is verified against the release's `checksums.sha256` before
anything trusts it. Installation is atomic and crash-safe: both files download to temp
locations first, then `libkrunfw` moves into place, and the `msb` binary moves into
place **last** — so the binary's mere existence is the "install complete" marker. A
process crashing mid-install can never leave a state where a later run wrongly
believes an incomplete install is usable. A cross-process file lock
(`FileChannel.lock()`) serializes concurrent installs so parallel Gradle test workers
provision exactly once rather than racing each other.

`libkrunfw` lives in a `lib/` directory as a sibling of `bin/msb` — msb's own resolver
expects it at `../lib` relative to the binary, and the installer lays files out to
match.

### Attached-mode supervision

microsandbox's detached mode (`msb run -d`) does **not** start the image's own
ENTRYPOINT — the VM boots with only its init process and the workload inside never
launches. rightsize's msb backend therefore runs every sandbox **attached**: each
container is a held child `Process` supervising its microVM, and the image's
ENTRYPOINT/CMD runs exactly as it would under Docker. Readiness for backend purposes
is "the sandbox name shows `Running` in `msb ls`" — not the attached process's own
exit code or stdout; workload logs come from `msb logs`, a separate channel. See
[How It Works](how-it-works.md) for more on why this shape was necessary.

### The provisioning cache

Everything lands under `~/.cache/rightsize/` (or `RIGHTSIZE_CACHE_DIR` if you've
relocated it) — the pinned msb toolchain, versioned by directory so a future rightsize
release pinning a newer msb doesn't collide with an older cached one.

## `backend-docker` deep-dive: the zerodep story

The Docker backend talks to the daemon through `docker-java`, and specifically through
the `docker-java-transport-zerodep` transport — not `httpclient5`, despite that being
the more commonly reached-for docker-java transport. This wasn't a style preference;
it's a fix for a real, reproducible failure.

**The problem:** `docker-java-transport-httpclient5` depends on Apache HttpClient 5.
If the *consuming* application's own classpath also manages httpclient5 to `>= 5.4`
(true of any Spring Boot 3.4+ application, for instance — exactly the shape of the
`mirage-weather-service` suite this library was built against), docker-java's
transport silently dials TCP `localhost:2375` instead of the daemon's actual
`unix://` socket, and reports the daemon as unreachable. This is a genuinely
surprising failure mode: your test suite worked yesterday, you bumped an unrelated
dependency today, and now every container test fails with a connection error that
has nothing obviously to do with Docker.

**The fix:** `backend-docker` uses `docker-java-transport-zerodep`
(`ZerodepDockerHttpClient`), which bundles its own JNA-based unix-socket client and
pulls in no httpclient5 at all. There is nothing you need to do about this — it's
handled entirely inside `backend-docker` — but if you're debugging a "Docker
unreachable" error that seems to have started after a dependency bump, and you're
using rightsize's Docker backend, this class of bug is now categorically
ruled out; look elsewhere.

## Backend differences

The two backends are contract-equivalent — the same shared test suite passes against
both — but a handful of edges are genuinely backend-specific rather than incidental
timing quirks. Know these before you hit them:

- **Read-only mounts aren't enforced in-guest on microsandbox 0.6.2.**
  `FileMount.readOnly` (under `withCopyFileToContainer`) is honored by the Docker
  backend — the bind mount is genuinely read-only inside the container. On
  microsandbox, the guest currently gets a writable mount regardless of the flag.
  Don't rely on guest-side write protection under `RIGHTSIZE_BACKEND=microsandbox`.
  See [Files & Memory](concepts/files-and-memory.md#read-only-mounts-a-real-backend-difference).
- **`followOutput`'s tail-flush on microsandbox is a watchdog, not a stream close.**
  `msb logs -f` doesn't exit when its sandbox stops (a documented gap in msb 0.6.2), so
  the microsandbox backend polls in the background and replays only the
  not-yet-delivered tail once the sandbox is confirmed stopped. Consumers see the same
  ordered, no-duplicate output either backend produces — this is purely an
  implementation detail, not a behavior difference from the caller's point of view —
  but it does mean a `followOutput` subscriber on microsandbox can see its last line
  arrive slightly *after* the sandbox itself reports stopped, rather than exactly at
  stream EOF the way a Docker log stream closes.
- **Network-alias tunnels on microsandbox serve one connection at a time.** See
  [Networking](concepts/networking.md#limits-on-the-microsandbox-backend) — this is a
  real capability gap versus Docker's native bridge networking, not just a timing
  quirk, and it means sustained bidirectional sibling traffic (a cross-container Kafka
  consumer, say) isn't something the microsandbox backend supports today.
- **Readiness-probe caveats apply to both backends**, not just microsandbox — see
  [Wait Strategies](concepts/wait-strategies.md#readiness-probe-caveats) for why
  `Wait.forListeningPort()` can be satisfied before the in-guest process is actually
  ready on either backend, and when to prefer `Wait.forHttp`/`Wait.forLogMessage`
  instead.
