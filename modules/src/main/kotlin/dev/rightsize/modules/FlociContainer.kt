package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/**
 * A [floci.io](https://floci.io) cloud emulator — one native Quarkus image per cloud provider,
 * each speaking that provider's REST APIs against an in-memory backing store. One module class
 * covers all three variants; pick one with the [aws]/[azure]/[gcp] factory functions rather than
 * the constructor directly (each factory pins the provider's own image and guest port).
 *
 * ### Readiness — `/health` works uniformly, unlike the AWS-flavored `/_localstack/health`
 *
 * The AWS variant ships a LocalStack-compatible `/_localstack/health` endpoint (spec hypothesis
 * confirmed), but the Azure and GCP variants do not carry that path (Azure: `501`; GCP: `404`).
 * All three, however, answer a plain **`GET /health`** with `200` and a small JSON status body
 * the moment the embedded Quarkus HTTP listener is up — verified directly against real boots of
 * `floci/floci:1.5.30`, `floci/floci-az:0.8.0`, and `floci/floci-gcp:0.4.0`. `/health` is pinned
 * as the one wait path that works across all three variants; no log-wait fallback was needed.
 *
 * ### No signing needed — verified against the AWS variant's S3 surface
 *
 * The AWS variant's S3-shaped REST endpoints accept unsigned requests with no `Authorization`
 * header at all: `PUT /<bucket>`, `PUT /<bucket>/<key>`, and `GET /<bucket>/<key>` all round-trip
 * successfully with a bare `HttpClient`/`ureq` call — no SigV4, no AWS SDK dependency required.
 * This module's IT exercises that plain-REST path rather than pulling in the AWS SDK.
 *
 * ### Memory — tiny, no ladder needed
 *
 * All three images are native (GraalVM) Quarkus binaries; a real boot settles at roughly
 * 11–27 MiB RSS (`docker stats`), and each variant boots and answers `/health` under msb's
 * default microVM RAM with no `withMemoryLimit` override.
 *
 * No control characters were found in any of the three images' baked env (checked via
 * `docker image inspect`).
 */
class FlociContainer private constructor(image: String, private val port: Int) :
    GenericContainer<FlociContainer>(image) {
    init {
        withExposedPorts(port)
        waitingFor(Wait.forHttp("/health").forPort(port))
    }

    /** This variant's REST endpoint (`http://<host>:<mapped port>`), the base URI for every emulated API call. */
    val endpointUrl: String get() = "http://$host:${getMappedPort(port)}"

    companion object {
        /** The AWS emulator (`floci/floci:1.5.30`), guest port 4566 — S3, DynamoDB, SQS, etc. */
        fun aws(image: String = "floci/floci:1.5.30"): FlociContainer = FlociContainer(image, AWS_PORT)
        /** The Azure emulator (`floci/floci-az:0.8.0`), guest port 4577. */
        fun azure(image: String = "floci/floci-az:0.8.0"): FlociContainer = FlociContainer(image, AZURE_PORT)
        /** The GCP emulator (`floci/floci-gcp:0.4.0`), guest port 4588. */
        fun gcp(image: String = "floci/floci-gcp:0.4.0"): FlociContainer = FlociContainer(image, GCP_PORT)

        private const val AWS_PORT = 4566
        private const val AZURE_PORT = 4577
        private const val GCP_PORT = 4588
    }
}
