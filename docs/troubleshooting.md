# Troubleshooting

Every entry here was a real failure hit while building rightsize itself, not a
hypothetical. Where a fix already lives inside rightsize (most of them), the entry
tells you that so you know it's not something you need to work around yourself —
these are included so you recognize the symptom instantly if you ever see it (e.g.
while debugging your own image against the msb CLI directly) rather than
re-discovering the cause from scratch.

## Container never starts / image ENTRYPOINT seems to do nothing

**Symptom:** A container reports as created but the workload inside never actually
runs — `msb status` shows the image's default command as metadata, but nothing is
listening, nothing is logging.

**Cause:** microsandbox's detached mode (`msb run -d`) boots the VM with only its init
process — it does not start the image's own ENTRYPOINT/CMD at all on msb 0.6.2.

**Fix:** Not something you need to fix yourself — rightsize's microsandbox backend
runs every sandbox in **attached** mode instead, holding a supervising child process
per container so the ENTRYPOINT runs exactly as it would under Docker. If you're
driving `msb` directly outside of rightsize and hit this, that's the fix: drop `-d`.

## `msb exec` hangs forever

**Symptom:** Any `execInContainer(...)` call (or a raw `msb exec` invocation) never
returns.

**Cause:** `msb exec` blocks until its stdin hits EOF — a subprocess pipe left open
(the default for most process-spawning APIs) keeps it waiting forever.

**Fix:** Handled inside rightsize — the backend closes the child's stdin immediately
after spawning. If you're shelling out to `msb exec` yourself, make sure you close or
redirect-from-`/dev/null` the child's stdin rather than leaving a pipe open.

## `followOutput`'s last line arrives after the container reports stopped

**Symptom:** A `followOutput` subscriber on the microsandbox backend sees its final
log line delivered slightly *after* the container itself reports `stop()` complete,
rather than exactly at stream close.

**Cause:** `msb logs -f` never exits once its sandbox stops (a documented gap in msb
0.6.2) — it blocks on read forever rather than hitting EOF.

**Fix:** Already handled — the microsandbox backend runs a watchdog that, once the
sandbox is confirmed stopped, quiesces the stuck follow process and replays only the
not-yet-delivered tail exactly once. The output itself is still correctly ordered with
no duplicates on either backend; this is a timing nuance in *when* the last line
arrives relative to stop, not a correctness issue. See
[Backends](backends.md#backend-differences) for the summary.

## Guest-side write to a "read-only" mount succeeds when it shouldn't

**Symptom:** A test asserts that a write to a file mounted with `readOnly = true`
fails inside the container, and the assertion fails — the write went through.

**Cause:** `FileMount.readOnly` is not enforced in-guest on the microsandbox backend
(msb 0.6.2). Docker enforces it correctly.

**Fix:** Don't write a test that depends on guest-side write protection when running
under `RIGHTSIZE_BACKEND=microsandbox`. If your test's correctness genuinely depends
on this, either verify it only under `RIGHTSIZE_BACKEND=docker`, or add your own
in-test guard that doesn't rely on the guest OS enforcing the flag. See
[Files & Memory](concepts/files-and-memory.md#read-only-mounts-a-real-backend-difference).

## `msb ls` output looks different than you expected

**Symptom:** Code (or a script) parsing `msb ls` output breaks, or `--json` isn't
recognized.

**Cause:** `msb ls --format json` is the correct flag — there is **no** `--json` flag
on `ls` itself (that spelling exists on `msb logs`, not `ls`). The JSON shape is a flat
array of objects with keys `created_at, image, name, status`, and `status` is
capitalized (e.g. `"Running"`).

**Fix:** Use `msb ls --format json`, not `msb ls --json`. rightsize's own parsing is
tolerant of key order (a brace-scanner, not a fixed-order deserializer) for exactly
this kind of drift-proofing; if you're parsing `msb ls` output yourself, don't assume
key order either.

## A JVM-based image fails to launch only on the microsandbox backend

**Symptom:** A container boots fine under `RIGHTSIZE_BACKEND=docker` but fails to
launch (or the JVM aborts with an insufficient-memory error) under
`RIGHTSIZE_BACKEND=microsandbox`.

**Cause:** microsandbox's default microVM has roughly ~450 MB of guest-available RAM.
Images that compute fixed memory regions above this at startup — Paketo-buildpack JVM
images in particular, which size heap/metaspace/thread-stack regions ahead of time
from image metadata — fail to launch when that computed size exceeds the default.

**Fix:** Call `.withMemoryLimit(megabytes)` on the container, sized with real headroom
above the image's actual requirement — don't just raise it until it barely stops
OOM-killing; see [Files & Memory](concepts/files-and-memory.md#when-you-actually-need-this)
for the full story, including a case ([Pinot](modules/pinot.md)) where "just enough to
avoid the OOM killer" (3072 MB) still caused intermittent RPC timeouts under memory
pressure, and 4096 MB was the number that actually left headroom.

## An image pull is slow, rate-limited, or fails intermittently

**Symptom:** Pulling a particular image (Redpanda's in particular) is slow, gets
rate-limited, or fails in CI specifically.

**Cause:** Registries rate-limit
anonymous pulls. Separately, microsandbox pulls **single-arch** image layers (matching
your host architecture) rather than a multi-arch manifest — this is generally a
*benefit* (smaller pulls; an ArangoDB pull was measured at ~203 MB single-arch vs. an
estimated ~600 MB multi-arch), but it doesn't help if the registry itself is
rate-limiting you.

**Fix:** For a rate-limited or otherwise hard-to-pull image, seed it into the msb
cache ahead of time: `docker save <image> -o /tmp/img.tar && msb load -i /tmp/img.tar -t <image>` (this is exactly how
Redpanda-on-msb was finally verified during rightsize's own development). In CI, cache
`~/.microsandbox/cache/` between runs the same way you'd cache any other dependency
directory.

## A control-character panic (`InvalidAscii`) on container boot

**Symptom:** A container fails to boot under the microsandbox backend with an
`InvalidAscii`-style panic from the krun VMM, before the guest workload ever starts —
reproduced even with zero rightsize-set environment variables.

**Cause:** microsandbox 0.6.2's krun VMM rejects environment variable values
containing control characters. The official `postgres:*-alpine` image is a known
example: it bakes `DOCKER_PG_LLVM_DEPS` with a literal tab character (from a
Dockerfile-internal package list built with `\t\t` continuation).

**Fix:** Already handled for `PostgreSQLContainer` — it overrides the offending
variable to an empty string, which is a no-op on Docker and the fix on microsandbox.
If you hit this with an image rightsize doesn't ship a module for, look for a baked env
var with an unusual byte in it (check via `docker inspect <image>`) and override it the
same way; there's no general-purpose sanitizer built into the backend yet, so this is
handled per-image.

## Docker backend reports the daemon unreachable, but Docker is clearly running

**Symptom:** `RIGHTSIZE_BACKEND=docker` (or auto-selection resolving to Docker) fails
claiming the daemon is unreachable, even though `docker ps` works fine from a shell.

**Cause:** If you're on an older or custom-built `backend-docker` configuration using
the `httpclient5` docker-java transport, and your own classpath separately manages
Apache HttpClient 5 to `>= 5.4` (true of any Spring Boot 3.4+ application), docker-java
silently dials TCP `localhost:2375` instead of the daemon's real `unix://` socket.

**Fix:** rightsize's shipped `backend-docker` already uses the `zerodep` transport
specifically to rule this class of bug out — see
[Backends](backends.md#backend-docker-deep-dive-the-zerodep-story) for the full story.
If you're still seeing an unreachable-daemon error with the shipped backend, check
`DOCKER_HOST` and your Docker CLI context instead (Colima/OrbStack/Docker Desktop on a
non-default socket) — see the [environment variables table](backends.md#environment-variables).

## Log lines appear truncated, duplicated, or split mid-line (Docker backend)

**Symptom:** `.logs` or `followOutput` occasionally shows a line broken across two
deliveries, or a partial line at the end.

**Cause:** `docker-java`'s log-streaming API delivers frames whose boundaries are raw
chunking artifacts — a single logical log line can straddle two frames.

**Fix:** Already handled — the Docker backend reassembles frames with a line buffer
and flushes the trailing fragment exactly once at stream end. If you're bypassing
rightsize and reading docker-java's log stream directly, you'll need the same
reassembly logic yourself.

## Two containers occasionally fail to start with a port-bind error

**Symptom:** An occasional (not consistent) failure on `start()` mentioning a port
already in use or already allocated, especially in test suites that start several
containers concurrently.

**Cause:** Host ports are pre-allocated JVM-side (an ephemeral `ServerSocket(0)`
bind-then-release) before being handed to the backend, because msb only supports
static host:guest port maps. There's an unavoidable window between "we picked this
port" and "the backend actually bound it" where a sibling container in the same JVM
can grab the same port first.

**Fix:** Already handled — `GenericContainer.start()` retries the whole create+start
sequence up to 5 times with freshly allocated ports whenever it detects this specific
race (both a typed exception and a message-based fallback classifier catch it). You
shouldn't need to add your own retry logic around `start()` for this; if you're
seeing it persist past the built-in retries, that suggests something external
(another process, a port scanner) is racing the allocator continuously on that host,
not a rightsize bug.

## Cross-container connection reachable once, then hangs on the second request (microsandbox backend)

**Symptom:** A container-to-container call over a [`Network`](concepts/networking.md)
alias works exactly once, then every subsequent connection from the same consumer
hangs indefinitely.

**Cause:** msb 0.6.2's port-publish proxy never propagates the target's own TCP close
back to the tunnel's host-side socket — a host client reading a published port never
observes EOF even after the guest workload closes its end. Naively pumping until a
natural `read() == 0` therefore blocks forever after the first exchange, so the
in-guest listener is never respawned for a second connection.

**Fix:** Already handled — the exec-tunnel relay infers "this exchange is over" from a
read-timeout heuristic instead of waiting for a natural close (a generous first-byte
deadline before any data has arrived, tightened to a short idle timeout once data
starts flowing). This is exactly why
[Networking](concepts/networking.md#limits-on-the-microsandbox-backend) documents
"one connection at a time per tunnel" as a hard limit rather than a bug to be fixed —
it's the tunnel's designed contract, not an oversight. If your test needs a sustained,
multi-request connection to a sibling, that's the case to run under
`RIGHTSIZE_BACKEND=docker` instead.

## Readiness passes but the server isn't really answering yet (either backend)

**Symptom:** `Wait.forListeningPort()` reports ready, but the very first real request
against the container fails or hangs.

**Cause:** Both backends have a layer between the host socket and the in-guest server
that can accept a TCP connection before the server itself is listening — Docker's
userland proxy on one side, microsandbox's loopback forwarder on the other. A bare
connect-and-declare-victory check can be satisfied by that layer alone.

**Fix:** `Wait.forListeningPort()` already does a best-effort read-probe past this (see
[Wait Strategies](concepts/wait-strategies.md#readiness-probe-caveats)), which closes
the worst of the gap — but for a server that doesn't clearly fit "accepts a connection
and either speaks first or waits silently," prefer `Wait.forHttp(...)`,
`Wait.forLogMessage(...)`, or a protocol-aware custom wait strategy (see
[`MemcachedContainer`](modules/memcached.md) for a worked example). This is exactly
the class of bug the shipped modules were built to avoid — check whether a module
already exists for your image before writing your own wait strategy from scratch.
