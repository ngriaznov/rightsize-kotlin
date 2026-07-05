# Networking

`Network` gives containers alias-based connectivity that works identically on both
backends — from your test code's point of view, at least. What happens underneath
differs a lot, and the microsandbox side has real limits worth understanding before
you hit them.

## `Network`, aliases, and `resolve`

```kotlin
val net = Network.newNetwork()

val config = GenericContainer("hyness/spring-cloud-config-server:latest")
    .withNetwork(net).withNetworkAliases("configuration-stub")
    .withExposedPorts(8888)

val app = GenericContainer("my-service:latest")
    .withNetwork(net)
    .withEnv("CONFIG_URI", "http://${net.resolve("configuration-stub", 8888)}")
```

`Network.resolve(alias, guestPort)` returns the plain string `"alias:guestPort"` on
**both** backends — there's no backend-specific address format to branch on in your
test code. Start `config` before `app`; more on why in
[Limits on the microsandbox backend](#limits-on-the-microsandbox-backend) below.

A full worked example, taken directly from rightsize's own test suite
(`CrossSandboxNetworkIT`) — one container serves HTTP, another fetches from it purely
by alias:

```kotlin
Network.newNetwork().use { net ->
    val server = GenericContainer("python:3.12-alpine")
        .withCommand("python", "-m", "http.server", "8888")
        .withExposedPorts(8888)
        .withNetwork(net).withNetworkAliases("configuration-stub")
        .waitingFor(Wait.forHttp("/").forPort(8888))
    server.start()

    val client = GenericContainer("alpine:3.19")
        .withNetwork(net)
        // retry loop: on msb the exec-tunnel is installed moments AFTER the workload boots
        .withCommand("sh", "-c",
            "for i in $(seq 1 30); do wget -qO- -T 5 http://${net.resolve("configuration-stub", 8888)}/ " +
                "&& echo FETCH-OK && break; sleep 2; done; sleep 60")
        .waitingFor(Wait.forLogMessage(".*FETCH-OK.*"))
    client.start()

    try {
        assertTrue(client.logs.contains("FETCH-OK"))
    } finally {
        client.stop(); server.stop()
    }
}
```

Notice the retry loop in the client's shell command — that's not incidental, it's the
pattern you should copy. See why below.

## What's actually happening underneath

- **On the Docker backend**, `Network` maps to a real Docker network and
  `withNetworkAliases` to native Docker DNS aliases. This is exactly what
  Testcontainers itself does; there's nothing new to learn here if you've used it
  before.
- **On the microsandbox backend**, there is no such thing as a virtual network between
  microVMs — each one is fully isolated by design. rightsize emulates `Network`
  instead: it installs an `/etc/hosts` entry mapping the alias to `127.0.0.1` inside
  the consumer's guest, plus a TCP relay tunneled over the sandbox's `exec` channel
  that forwards guest-side connections to the sibling's real host-published port.

This emulation is a genuine engineering achievement (there is no supported
sandbox-to-sandbox or sandbox-to-host networking path in microsandbox 0.6.2 at all —
see [How It Works](../how-it-works.md) for the tunnel design) but it comes with real,
documented limits. rightsize is honest about them rather than papering over them with
a "mostly works" story.

## Limits on the microsandbox backend

- **Start dependencies before their consumers.** Links are installed for a starting
  container based on which siblings are *already running* at that moment — there's no
  retroactive wiring if a dependency starts after its consumer. In the example above,
  `server.start()` happens before `client.start()` for exactly this reason.
- **One connection at a time, per tunnel.** The exec-tunnel relay serves a single TCP
  connection, then respawns the in-guest listener for the next one. This is fine for
  request/response patterns like a config-server fetch (the pattern above), but it
  will not work for something that expects to hold a long-lived connection to a
  sibling — a cross-container Kafka consumer, for instance.
- **The consumer image needs a raw-socket tool.** The in-guest side of the tunnel uses
  `nc` (or busybox's equivalent) to relay bytes. If the consumer's image doesn't have
  it, rightsize fails fast with an `UnsupportedByBackendException` naming the gap
  rather than hanging or silently degrading — check for it with `command -v nc` in
  your own image if you're unsure.
- **The tunnel needs a moment to come up after boot.** Network links are installed
  right after the container starts, moments after the workload itself boots — a
  consumer that dials a sibling immediately on startup can race this. That's why the
  worked example above retries the fetch in a loop rather than trying it once; copy
  that shape for anything that talks to a sibling early in its own startup.

None of this affects the Docker backend, which uses real Docker networking with no
such constraints. If your test genuinely needs sustained bidirectional
container-to-container traffic, running it under `RIGHTSIZE_BACKEND=docker` is the
straightforward answer — see [Backends](../backends.md) for how to force a backend.

## Cleanup

`Network` implements `AutoCloseable` — `use { }` (as in the example above) or an
explicit `.close()` releases the network on whichever backend last used it. It's safe
to close a `Network` that was never actually used by any started container.
