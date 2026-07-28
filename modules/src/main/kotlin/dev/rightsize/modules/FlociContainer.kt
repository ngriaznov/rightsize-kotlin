package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A [floci.io](https://floci.io) cloud emulator — one native Quarkus image per cloud provider,
 * each speaking that provider's REST APIs against an in-memory backing store. One module class
 * covers all three variants; pick one with the [aws]/[azure]/[gcp] factory functions rather than
 * the constructor directly (each factory pins the provider's own image and guest port).
 *
 * ### Each variant defaults to its own `:latest` — three distinct floating references
 *
 * With no image given, each factory tracks its own image's `latest` tag rather than a version
 * this library pins, so the version moves with that variant's own releases instead of this
 * library's release cycle: [aws] defaults to `floci/floci:latest`, [azure] to
 * `floci/floci-az:latest`, [gcp] to `floci/floci-gcp:latest`. Every fact below was verified
 * against `floci/floci:1.5.30`, `floci/floci-az:0.8.0`, and `floci/floci-gcp:0.4.0` specifically —
 * pass one of those explicitly to pin the measured version, e.g. `FlociContainer.aws("floci/floci:1.5.30")`.
 * Each factory checks the image passed to it against its own repository — passing an AWS-shaped
 * image to [azure] (or vice versa) fails fast rather than booting the wrong emulator.
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
 * successfully with a bare `HttpClient` call — no SigV4, no AWS SDK dependency required.
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
class FlociContainer private constructor(image: DockerImageName, expectedRepository: String, private val port: Int) :
    GenericContainer<FlociContainer>(image.toString()) {
    init {
        image.assertCompatibleWith(expectedRepository)
        withExposedPorts(port)
        waitingFor(Wait.forHttp("/health").forPort(port))
    }

    /** This variant's REST endpoint (`http://<host>:<mapped port>`), the base URI for every emulated API call. */
    val endpointUrl: String get() = "http://$host:${getMappedPort(port)}"

    companion object {
        /** The AWS emulator, guest port 4566 — S3, DynamoDB, SQS, etc. Defaults to `floci/floci:latest`. */
        fun aws(image: DockerImageName): FlociContainer = FlociContainer(image, AWS_REPOSITORY, AWS_PORT)
        /** Defaults to `floci/floci:latest` — this image's floating reference (see the class doc). */
        fun aws(image: String = "floci/floci:latest"): FlociContainer = aws(DockerImageName.parse(image))

        /** The Azure emulator, guest port 4577. Defaults to `floci/floci-az:latest`. */
        fun azure(image: DockerImageName): FlociContainer = FlociContainer(image, AZURE_REPOSITORY, AZURE_PORT)
        /** Defaults to `floci/floci-az:latest` — this image's floating reference (see the class doc). */
        fun azure(image: String = "floci/floci-az:latest"): FlociContainer = azure(DockerImageName.parse(image))

        /** The GCP emulator, guest port 4588. Defaults to `floci/floci-gcp:latest`. */
        fun gcp(image: DockerImageName): FlociContainer = FlociContainer(image, GCP_REPOSITORY, GCP_PORT)
        /** Defaults to `floci/floci-gcp:latest` — this image's floating reference (see the class doc). */
        fun gcp(image: String = "floci/floci-gcp:latest"): FlociContainer = gcp(DockerImageName.parse(image))

        private const val AWS_PORT = 4566
        private const val AZURE_PORT = 4577
        private const val GCP_PORT = 4588

        private const val AWS_REPOSITORY = "floci/floci"
        private const val AZURE_REPOSITORY = "floci/floci-az"
        private const val GCP_REPOSITORY = "floci/floci-gcp"
    }
}
