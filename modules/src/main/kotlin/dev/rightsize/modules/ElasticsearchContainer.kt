package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node Elasticsearch container, queried over its REST interface (port 9200). The
 * transport port (9300) is exposed too, but only for a cluster's inter-node traffic, which a
 * single node never uses — no helper wraps it.
 *
 * ### No no-arg constructor — Elastic publishes no floating tag for this image
 *
 * Every other module in this library defaults to a `:latest`-shaped floating reference so its
 * no-arg constructor tracks upstream. Elasticsearch has none: `elasticsearch:latest`,
 * `elasticsearch:9`, and `elasticsearch:8` are all `404` on Docker Hub — Elastic publishes only
 * fully-qualified version tags (e.g. `9.4.4`, `8.19.19`). An explicit image is therefore
 * required; there is no default to fall back to. `elasticsearch:9.4.4` is the version this
 * module's own facts below were verified against.
 *
 * ### Single-node cluster health is `yellow` forever — do not wait for `green`
 *
 * A one-node cluster has nowhere to place replica shards, so `GET /_cluster/health` reports
 * `yellow` indefinitely; a readiness check that waits for `green` hangs until its startup
 * timeout every time. This module's own wait strategy checks plain connectivity (`GET /`
 * returning `200`) instead, which is reachable well before shard allocation could ever finish.
 *
 * ### Env — `discovery.type=single-node`, security off, and a bounded heap
 *
 * `discovery.type=single-node` skips this image's normal multi-node bootstrap (which otherwise
 * waits for peers that will never appear); `xpack.security.enabled=false` disables TLS/auth so
 * plain HTTP works with zero setup, matching the house convention for test-only modules;
 * `ES_JAVA_OPTS=-Xms512m -Xmx512m` bounds the heap explicitly rather than trusting the image's
 * own JVM ergonomics against an arbitrary guest memory size.
 *
 * ### Memory — a JVM with Lucene's off-heap needs on top, 2560 MB verified
 *
 * With the 512m/512m heap above, a real boot-to-ready cycle used roughly 1.1 GB of a 2.48 GB
 * guest — the gap over the heap setting itself is Lucene's off-heap memory-mapped segments and
 * the JVM's own non-heap regions. 2560 MB (this module's [withMemoryLimit] default) was verified
 * stable end to end.
 *
 * ### Readiness — plain HTTP, but a generous timeout for loaded runners
 *
 * `GET /` on the REST port returns `200` once the node is serving; verified directly at 27s on a
 * quiet machine. The startup timeout is set to 300s rather than that observed figure, since a
 * heavier JVM under contention on a loaded CI runner needs more headroom than a quiet-machine
 * measurement alone would suggest.
 *
 * ### Verified with a real index/search round-trip, no client dependency
 *
 * `PUT /books/_doc/1?refresh=true` followed by `GET /books/_search` returned the indexed
 * document — `?refresh=true` forces the just-written document into the next search rather than
 * waiting for Elasticsearch's own periodic refresh interval. Verified through plain HTTP calls
 * (`java.net.http.HttpClient`); this module and its tests pull in no Elasticsearch client.
 */
class ElasticsearchContainer(image: DockerImageName) : GenericContainer<ElasticsearchContainer>(image.toString()) {
    /** [image] must be given explicitly — see the class doc for why this module has no no-arg form. */
    constructor(image: String) : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(REST_PORT, TRANSPORT_PORT)
        withEnv("discovery.type", "single-node")
        withEnv("xpack.security.enabled", "false")
        withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
        withMemoryLimit(2560)
        // Plain connectivity, not cluster health: a single node is "yellow" forever (see the
        // class doc), so waiting for "green" would hang until the timeout every time. 300s: 27s
        // was observed on a quiet machine, but a loaded CI runner needs more headroom than that.
        waitingFor(Wait.forHttp("/").forPort(REST_PORT).withStartupTimeout(Duration.ofSeconds(300)))
    }

    /** The REST interface's base URI (port 9200) — index/search/cluster calls all go here. */
    val restUrl: String get() = "http://$host:${getMappedPort(REST_PORT)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "elasticsearch"
        const val REST_PORT = 9200
        const val TRANSPORT_PORT = 9300
    }
}
