package dev.rightsize.msb

import dev.rightsize.GenericContainer
import dev.rightsize.Network
import dev.rightsize.core.UnsupportedByBackendException
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@Tag("sandbox-it")
class MsbNetworkLinksIT {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            Assumptions.assumeTrue(MsbBackendProvider().isSupported())
            Assumptions.assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }

    @Test fun `consumer reaches sibling via alias over exec tunnel`() {
        Network.newNetwork().use { net ->
            val server = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8888")
                .withExposedPorts(8888)
                .withNetwork(net).withNetworkAliases("configuration-stub")
                .waitingFor(Wait.forHttp("/").forPort(8888))
            server.start()
            val client = GenericContainer("alpine:3.19")
                .withNetwork(net)
                .withCommand("sleep", "300")
                .waitingFor(Wait.forLogMessage(".*", 0))
            client.start()
            try {
                val r = client.execInContainer("wget", "-qO-", "-T", "10",
                    "http://${net.resolve("configuration-stub", 8888)}/")
                assertEquals(0, r.exitCode, "wget failed: ${r.stderr}")
                assertTrue(r.stdout.contains("Directory listing"), "unexpected body: ${r.stdout}")
            } finally { client.stop(); server.stop() }
        }
    }

    // msb 0.6.2's port-publish proxy never propagates the target's own TCP close back to the
    // tunnel's host-side socket - without the idle-timeout fix, the
    // target->guest pump blocks forever after the FIRST exchange, serveOneConnection never
    // returns, the in-guest `nc -l` listener is never respawned, and every connection after the
    // first wedges. Proving this needs a SECOND sequential connection through the SAME tunnel
    // link: two separate wget execs, back to back, against the same alias.
    @Test fun `second sequential connection through the same tunnel link succeeds`() {
        Network.newNetwork().use { net ->
            val server = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8888")
                .withExposedPorts(8888)
                .withNetwork(net).withNetworkAliases("configuration-stub")
                .waitingFor(Wait.forHttp("/").forPort(8888))
            server.start()
            val client = GenericContainer("alpine:3.19")
                .withNetwork(net)
                .withCommand("sleep", "300")
                .waitingFor(Wait.forLogMessage(".*", 0))
            client.start()
            try {
                val url = "http://${net.resolve("configuration-stub", 8888)}/"
                val r1 = client.execInContainer("wget", "-qO-", "-T", "10", url)
                assertEquals(0, r1.exitCode, "first wget failed: ${r1.stderr}")
                assertTrue(r1.stdout.contains("Directory listing"), "unexpected first body: ${r1.stdout}")

                // The respawn-after-exchange path: if the target->guest pump never ended the
                // first exchange, the in-guest listener is never respawned and this second,
                // fully independent exec hangs/times out. Respawning the in-guest listener is
                // itself a fresh `msb exec --stream` process spawn (real VM exec, not instant),
                // so - unlike the first connection, which lands on an already-listening nc - a
                // real consumer racing straight back in immediately after the first response can
                // legitimately see a "connection refused" for the brief respawn window. Retry
                // with backoff rather than a single immediate attempt, exactly as a real client
                // reconnecting to this tunnel's alias would.
                var r2 = client.execInContainer("wget", "-qO-", "-T", "10", url)
                var attempts = 1
                while (r2.exitCode != 0 && attempts < 10) {
                    Thread.sleep(500)
                    r2 = client.execInContainer("wget", "-qO-", "-T", "10", url)
                    attempts++
                }
                assertEquals(0, r2.exitCode, "second wget failed after $attempts attempt(s): ${r2.stderr}")
                assertTrue(r2.stdout.contains("Directory listing"), "unexpected second body: ${r2.stdout}")
            } finally { client.stop(); server.stop() }
        }
    }

    @Test fun `consumer image without nc fails fast with docker hint`() {
        Network.newNetwork().use { net ->
            val server = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8000")
                .withExposedPorts(8000)
                .withNetwork(net).withNetworkAliases("svc")
                .waitingFor(Wait.forHttp("/").forPort(8000))
            server.start()
            // mongo:8.0 boots as a sandbox but has no nc (busybox) in the image.
            // (The brief suggested hello-world, but that image fails to boot as a microVM
            //  on this msb build — "failed to resolve guest uid 0" — so the nc probe never
            //  runs. mongo boots cleanly and genuinely lacks nc, exercising the fail-fast path.)
            val consumer = GenericContainer("mongo:8.0")
                .withCommand("sleep", "300")
                .withNetwork(net).waitingFor(Wait.forLogMessage(".*", 0))
            try {
                val ex = assertThrows(Exception::class.java) { consumer.start() }
                assertTrue(ex.message!!.contains("docker", ignoreCase = true),
                    "error should point at the docker backend: ${ex.message}")
                // No consumer.stop() compensation here: start() must self-clean the
                // half-started container when installNetworkLinks fails fast.
            } finally { server.stop() }
        }
    }

    // Two siblings that expose the same guest port on one network must fail the
    // consumer's start() fast, before any tunnel/hosts work, naming the offending port.
    // Both siblings must be started (and thus registered on the network) before the consumer
    // starts, so Network.linksForNewMember() returns both links and installNetworkLinks sees
    // the duplicate guestPort.
    //
    // server2 declares guestPort 8000 (so the Network's bookkeeping sees a genuine duplicate
    // with server1's alias) but actually listens on 8001: when server2 itself starts, it links
    // to the already-running server1 (one link, guestPort 8000 — not yet a duplicate), and
    // MsbCliBackend installs an ExecTunnel inside server2's OWN guest that runs `nc -l -p 8000`
    // there. If server2's real workload also bound 8000, that would race the tunnel's nc for
    // the same guest port and fail with "Address already in use" — a real, observed race,
    // orthogonal to the fail-fast behavior under test. Binding server2's workload on 8001
    // sidesteps that race while still registering guestPort 8000 for the duplicate check.
    @Test fun `duplicate guest port across siblings fails fast naming the port`() {
        Network.newNetwork().use { net ->
            val server1 = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8000")
                .withExposedPorts(8000)
                .withNetwork(net).withNetworkAliases("svc-a")
                .waitingFor(Wait.forHttp("/").forPort(8000))
            val server2 = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8001")
                .withExposedPorts(8000, 8001)
                .withNetwork(net).withNetworkAliases("svc-b")
                .waitingFor(Wait.forHttp("/").forPort(8001))
            server1.start()
            server2.start()
            val consumer = GenericContainer("alpine:3.19")
                .withCommand("sleep", "300")
                .withNetwork(net).waitingFor(Wait.forLogMessage(".*", 0))
            try {
                val ex = assertThrows(Exception::class.java) { consumer.start() }
                assertTrue(ex is UnsupportedByBackendException,
                    "expected UnsupportedByBackendException, got ${ex::class}: ${ex.message}")
                assertTrue(ex.message!!.contains("8000"),
                    "error should name the duplicated guest port 8000: ${ex.message}")
                // No consumer.stop() compensation here: start() must self-clean the
                // half-started container when installNetworkLinks fails fast.
                assertFalse(consumer.isRunning, "consumer should have self-cleaned after the failed start()")
            } finally { server1.stop(); server2.stop() }
        }
    }

    // A sibling alias that would break out of the `sh -c "echo '127.0.0.1 $alias' >>
    // /etc/hosts"` quoting must be rejected up front by installNetworkLinks' alias-charset
    // validation (UnsupportedByBackendException naming the alias), never reach the hosts-install
    // shell-out, and never silently corrupt the hosts file.
    @Test fun `sibling alias that breaks hosts-file shell quoting is rejected before install`() {
        Network.newNetwork().use { net ->
            // Needs >=1 exposed port: installNetworkLinks builds NetworkLinks from the running
            // sibling's mapped ports (Network.linksForNewMember), and short-circuits to a no-op
            // on an empty link list — an unexposed server would never reach the alias-validation step.
            val server = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8001")
                .withExposedPorts(8001)
                .withNetwork(net).withNetworkAliases("bad'alias")
                .waitingFor(Wait.forHttp("/").forPort(8001))
            server.start()
            val consumer = GenericContainer("alpine:3.19")
                .withCommand("sleep", "300")
                .withNetwork(net).waitingFor(Wait.forLogMessage(".*", 0))
            try {
                val ex = assertThrows(Exception::class.java) { consumer.start() }
                assertTrue(ex is UnsupportedByBackendException,
                    "expected UnsupportedByBackendException, got ${ex::class}: ${ex.message}")
                assertTrue(ex.message!!.contains("bad'alias"),
                    "error should name the offending alias: ${ex.message}")
                // No consumer.stop() compensation here: start() must self-clean the
                // half-started container when installNetworkLinks fails fast.
            } finally { server.stop() }
        }
    }
}
