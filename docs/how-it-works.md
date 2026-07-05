# How It Works

## Architecture

```
rightsize/
├── core/                    # public API + SandboxBackend SPI; JUnit 5 extension
├── backend-microsandbox/    # msb CLI driver + runtime provisioner
├── backend-docker/          # docker-java adapter (fallback / correctness oracle)
├── modules/                 # preconfigured containers
└── bom/                     # version-alignment platform
```

`core` has no backend dependency. Both backends depend on `core` and implement its
`SandboxBackend` interface; `modules` depends on `core` and (at test time) pulls in
both backends via `ServiceLoader` discovery. Nothing outside `core` knows which
backend is active — that's the whole point of the SPI boundary.

### `SandboxBackend` — the one interface both backends satisfy

```kotlin
interface SandboxBackend : AutoCloseable {
    val name: String
    val supportsNativeNetworks: Boolean
    fun create(spec: ContainerSpec): SandboxHandle
    fun start(handle: SandboxHandle)
    fun stop(handle: SandboxHandle)
    fun remove(handle: SandboxHandle)
    fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult
    fun logs(handle: SandboxHandle): String
    fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit): AutoCloseable
    fun ensureNetwork(networkId: String)
    fun removeNetwork(networkId: String)
    fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>)  // default no-op
}
```

Every module container, and `GenericContainer` itself, is written entirely against
this interface — never against `msb` or `docker-java` directly. `BackendProvider`
(discovered via `java.util.ServiceLoader`) is the factory: each backend registers a
provider with a `name`, a `priority` (microsandbox = 20, Docker = 10), an
`isSupported()` check, and a `create()` that builds the actual `SandboxBackend`. See
[Backends](backends.md#backend-selection) for how `Backends.resolve(...)` — a pure,
independently-tested function — turns a provider list plus an optional
`RIGHTSIZE_BACKEND` override into the one active backend for the JVM's lifetime.

### The contract test suite: Docker as the correctness oracle

A single abstract JUnit test suite (living in `core`'s `testFixtures`) exercises the
`SandboxBackend` contract end to end — start/stop, port mapping, exec, log streaming,
network links — and both backend modules subclass it, so the *same* test logic runs
against both real backends. The Docker backend, being the conventional and
well-understood implementation, serves as the correctness oracle: when a microsandbox
backend behavior looks surprising, the contract suite passing identically against
Docker is what confirms whether it's a genuine microsandbox limitation (documented in
[Backends → Backend differences](backends.md#backend-differences)) or an actual bug.

## Self-provisioning runtime

A pinned `msb` release (binary + `libkrunfw`) is downloaded once per version, matched
to your OS/architecture, from
`github.com/superradcompany/microsandbox/releases/download/v<version>/`. Every asset
is verified against the release's own `checksums.sha256` before anything trusts it.

Installation is atomic and crash-safe by construction: both files land in temp
locations first, `libkrunfw` moves into its final place, and the `msb` binary moves
into place **last** — so the binary's mere presence on disk is the "install is
complete and trustworthy" marker. A process crashing at any point during install
leaves either nothing or a fully-valid install; it can never leave a state a later run
would wrongly accept as complete. A cross-process file lock
(`FileChannel.lock()` on a lock file under the cache root) keeps concurrent Gradle test
workers from racing each other through this download.

`MSB_PATH` bypasses the whole thing (point at a pre-installed binary; useful in
air-gapped environments), and `RIGHTSIZE_MSB_SKIP_DOWNLOAD=true` fails fast with
guidance instead of attempting a download at all. See the full env var table in
[Backends](backends.md#environment-variables).

## Attached-mode supervision

This is the single biggest design pivot the project made: microsandbox's detached mode
(`msb run -d`) boots the VM but does **not** start the image's own ENTRYPOINT/CMD — the
guest comes up with only its init process, and redis/postgres/whatever never actually
launches. This was confirmed empirically against the real `msb` binary before any
library code was written.

The fix is that rightsize's microsandbox backend runs every sandbox **attached**: each
container is a real, held child `Process` that supervises its own microVM for its
entire lifetime, and the image's ENTRYPOINT runs exactly as it would under Docker.
Readiness, from the backend's point of view, means the sandbox's name shows
`"Running"` in `msb ls --format json` — not the attached process's own exit code or
stdout, which is a separate channel from the workload's actual logs (those come from
`msb logs`).

## Pre-allocated ports

microsandbox only supports **static** host:guest port maps — there's no dynamic
"pick a free port and tell me what you picked" API the way Docker's daemon offers.
rightsize works around this by allocating host ports **before** creating the
container at all: bind an ephemeral `ServerSocket(0)`, record the port it got, close
it, and hand that specific port to the backend as part of `ContainerSpec`.

This matters beyond just "how ports get chosen" — brokers like Redpanda and Kafka bake
their own *advertised* listener address into their startup command, and that address
has to already be the real, externally-reachable host port. Because ports are known
before the container is created, `GenericContainer.customizeSpec(spec, mapped)` gives
module authors a hook to rewrite the startup command with the real mapped port the
instant before boot — see [Redpanda](modules/redpanda.md#backend-notes-the-dual-listener-trick)
for the concrete mechanics.

The unavoidable cost of pre-allocation is a narrow allocate-then-bind race (another
process, or a sibling container in the same JVM, can grab the same port in the gap
between "we picked it" and "the backend actually bound it"). `GenericContainer.start()`
absorbs this with a bounded retry (5 attempts, fresh ports each time) — see
[Troubleshooting](troubleshooting.md#two-containers-occasionally-fail-to-start-with-a-port-bind-error).

## Networking: exec-tunnel emulation

microsandbox microVMs are fully isolated from each other by design — there is no
sandbox-to-sandbox networking and no sandbox-to-host TCP path on macOS under any
tested `--net-rule` policy (confirmed empirically against the real binary: the
`host.microsandbox.internal` gateway does not forward to host services or sibling
published ports, and upstream SSH `-L`/`-R` forwarding is broken in msb 0.6.2).

rightsize emulates `Network` on top of this constraint rather than giving up on
container-to-container connectivity entirely:

1. For each container about to start on a `Network`, core computes the set of
   `NetworkLink`s it needs — one per `(alias, guestPort)` of every sibling already
   running on that network.
2. After the backend starts the container but before its wait strategy runs, core
   calls `backend.installNetworkLinks(handle, links)`.
3. **On the microsandbox backend**, this does real work: it appends an `/etc/hosts`
   entry mapping the alias to `127.0.0.1` inside the consumer's guest, then wires a
   relay — an in-guest listener (`nc`/busybox) bridged over the sandbox's `exec
   --stream` channel to a host-side byte pump that connects to the sibling's real
   host-published port. The pump reads and writes raw, unbuffered bytes with a
   flush after every read — a buffered reader here would starve the relay, since the
   buffer never fills and bytes never get forwarded.
4. **On the Docker backend**, `installNetworkLinks` is simply a no-op — Docker's
   native networks and DNS aliases already do the job.

`Network.resolve(alias, guestPort)` returns the identical `"alias:guestPort"` string on
both backends, so code written against `Network` never branches on which backend is
active. See [Networking](concepts/networking.md) for the user-facing contract and its
documented limits (start order, one connection per tunnel, the `nc` requirement).

### Why the tunnel serves one connection at a time

msb 0.6.2's port-publish proxy never propagates the target side's TCP close back to
the tunnel's host-side socket — a host client reading past the target's own
`Connection: close` never observes a natural EOF. A pump written to wait for that EOF
would simply hang forever after the first exchange. The tunnel instead infers "this
exchange is over" from a read-timeout heuristic: a generous first-byte deadline before
any target byte has arrived (so a slow-but-real response is never truncated),
tightened to a short idle timeout once the first byte shows up (a gap that short,
once data has started flowing, really does mean nothing more is coming — this
tunnel's own client-speaks-first, single-exchange contract). That's the concrete
reason "one connection at a time per tunnel" is a documented, permanent limit rather
than a bug on the roadmap — see
[Networking](concepts/networking.md#limits-on-the-microsandbox-backend).

## One SPI, two backends, a shared referee

Pulling the threads above together: `SandboxBackend` is a small interface, not a
sprawling one, specifically so that the contract test suite can be the referee for
both implementations. The Docker backend is simple almost by comparison — a
straightforward adapter over `docker-java` — and that simplicity is what makes it
trustworthy as the oracle the harder, more inventive microsandbox backend gets checked
against. Where the two genuinely can't be made equivalent (single-connection tunnels,
advisory read-only mounts), rightsize documents the gap explicitly rather than hiding
it — see [Backends → Backend differences](backends.md#backend-differences) for the
complete, current list.
