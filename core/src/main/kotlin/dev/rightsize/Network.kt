package dev.rightsize

import dev.rightsize.core.NetworkLink
import dev.rightsize.core.PortProtocol
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.reaper.Reaper
import java.util.UUID

/**
 * Alias-based connectivity between containers, on either backend. Attach containers with
 * [GenericContainer.withNetwork] and [GenericContainer.withNetworkAliases], then reach a sibling
 * via [resolve]. On Docker this is a native network alias; on microsandbox — where microVMs are
 * fully isolated from each other — rightsize emulates it with an `/etc/hosts` entry plus an
 * exec-tunneled TCP relay (see the backend's `installNetworkLinks`).
 */
class Network private constructor(val id: String) : AutoCloseable {
    private data class Member(val container: GenericContainer<*>, val aliases: List<String>)
    private val members = mutableListOf<Member>()
    @Volatile private var backendUsed: SandboxBackend? = null

    internal fun register(container: GenericContainer<*>, aliases: List<String>, backend: SandboxBackend) {
        synchronized(members) { members += Member(container, aliases); backendUsed = backend }
    }

    /** One link per (alias, exposed guest port) of a single already-running sibling — both
     * protocols: a TCP link per [dev.rightsize.GenericContainer.mappedPortsView] entry, plus a
     * UDP-tagged one per [dev.rightsize.GenericContainer.mappedUdpPortsView] entry. Docker's
     * native networks carry either transport with no change of behavior; msb's exec-tunnel
     * emulation rejects any UDP-tagged link up front (see `MsbCliBackend.installNetworkLinks`),
     * so a UDP one reaching a starting member there fails that member's own `start()` fast. */
    private fun linksFor(member: Member): List<NetworkLink> =
        member.aliases.flatMap { alias ->
            member.container.mappedPortsView().map { (guest, host) -> NetworkLink(alias, guest, host) } +
                member.container.mappedUdpPortsView()
                    .map { (guest, host) -> NetworkLink(alias, guest, host, PortProtocol.UDP) }
        }

    /**
     * Links a newly starting member needs: one per (alias, exposed guest port) of every RUNNING
     * sibling. Invariant: this is called *before* the new member itself is added via [register],
     * so it can never produce a self-link.
     */
    internal fun linksForNewMember(): List<NetworkLink> = synchronized(members) {
        val runningMembers = members.filter { it.container.isRunning }
        runningMembers.flatMap { linksFor(it) }
    }

    /**
     * The `alias:guestPort` address a sibling uses to reach this member — identical on both
     * backends (native DNS on docker, /etc/hosts alias on msb).
     */
    fun resolve(alias: String, guestPort: Int): String {
        synchronized(members) {
            check(members.any { alias in it.aliases }) {
                "No container with alias '$alias' registered on this network — check withNetworkAliases('$alias')"
            }
        }
        return "$alias:$guestPort"
    }

    /** Releases the network on whichever backend last used it. Safe to call even if never used. */
    override fun close() {
        backendUsed?.let { b -> if (runCatching { b.removeNetwork(id) }.isSuccess) Reaper.afterNetworkRemoved(id) }
    }

    companion object {
        /** Creates a new, empty network with a random id. Attach containers before starting them. */
        fun newNetwork() = Network("rz-net-${UUID.randomUUID().toString().take(8)}")
    }
}
