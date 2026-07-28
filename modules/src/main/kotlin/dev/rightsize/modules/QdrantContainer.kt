package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node Qdrant container, the vector database, queried over its REST interface (port
 * 6333). The gRPC interface (port 6334) is exposed too, but not wrapped by a helper — this
 * module's own verification used the REST API throughout.
 *
 * ### Defaults to `qdrant/qdrant:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with Qdrant's own releases instead of this library's
 * release cycle. The facts below were captured against `qdrant/qdrant:v1.18.3` specifically (note
 * the `v` prefix Qdrant's own version tags carry, unlike `latest`) — pass that image explicitly
 * to pin it: `QdrantContainer("qdrant/qdrant:v1.18.3")`.
 *
 * ### Readiness — answers correctly on the very first poll
 *
 * `GET /readyz` on the REST port returned `200` on the first poll after boot in direct
 * verification — no retry/backoff story to document here, unlike several of this library's
 * JVM-backed modules.
 *
 * ### Memory — no limit needed
 *
 * Verified directly with no [withMemoryLimit] set: a full create-collection/upsert/search
 * round-trip completed in a guest reporting roughly 480 MB total. This module sets no memory
 * limit; callers who hit memory pressure on a constrained host can call [withMemoryLimit]
 * themselves.
 *
 * ### Verified with a real collection/upsert/search round-trip, no client dependency
 *
 * Creating a collection (vector size 4, `Dot` distance), upserting a point, and searching
 * against it with that same point's own vector returned the expected point back with score
 * `1.0` — the self dot-product — verified through plain HTTP calls against the REST API
 * (`java.net.http.HttpClient`); this module and its tests pull in no Qdrant client.
 */
class QdrantContainer(image: DockerImageName) : GenericContainer<QdrantContainer>(image.toString()) {
    /** Defaults to `qdrant/qdrant:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "qdrant/qdrant:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(REST_PORT, GRPC_PORT)
        // Answers 200 on the very first poll after boot in direct verification — no restart/
        // double-boot race to work around, so the default timeout is left as-is.
        waitingFor(Wait.forHttp("/readyz").forPort(REST_PORT))
    }

    /** The REST interface's base URI (port 6333) — collection/point/search calls all go here. */
    val restUrl: String get() = "http://$host:${getMappedPort(REST_PORT)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "qdrant/qdrant"
        const val REST_PORT = 6333
        const val GRPC_PORT = 6334
    }
}
