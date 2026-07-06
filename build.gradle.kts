plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

// Publishing metadata shared by every module's POM.
val projectUrl = "https://github.com/ngriaznov/rightsize-kotlin"
val moduleDescriptions = mapOf(
    "core" to "Testcontainers-shaped SPI, GenericContainer engine, and backend-agnostic wait strategies.",
    "backend-docker" to "Docker daemon backend for rightsize (docker-java, fallback runtime).",
    "backend-microsandbox" to "microsandbox microVM backend for rightsize (self-provisioning, no daemon).",
    "modules" to "Preconfigured containers (Redis, Kafka, MongoDB, ArangoDB, Memcached, and more).",
    "bom" to "Version-aligned platform BOM for rightsize's published modules.",
)

// Signing is release-only: the key arrives ASCII-armored via environment, so regular
// builds and publishToMavenLocal never require GPG material to be present. Maven
// Central rejects unsigned artifacts, so a real publish without the key set fails
// there, loudly, rather than silently uploading something unreleasable.
fun Project.signPublicationsWhenKeyPresent() {
    val key = providers.environmentVariable("RIGHTSIZE_GPG_KEY").orNull ?: return
    val passphrase = providers.environmentVariable("RIGHTSIZE_GPG_PASSPHRASE").orNull
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(key, passphrase)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}

fun MavenPublication.applyRightsizePom(moduleName: String) = pom {
    name = "rightsize-$moduleName"
    description = moduleDescriptions.getValue(moduleName)
    url = projectUrl
    licenses {
        license {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        }
    }
    developers {
        developer {
            name = "Mykyta Hriaznov"
            url = "https://github.com/ngriaznov"
        }
    }
    scm {
        url = projectUrl
        connection = "scm:git:$projectUrl.git"
        developerConnection = "scm:git:$projectUrl.git"
    }
}

subprojects {
    group = "dev.rightsize"
    version = "0.1.0-SNAPSHOT"

    // examples/ is consumer-facing showcase code, not a published artifact: it's excluded from
    // maven-publish (and thus from the BOM's constraints) entirely, unlike every other subproject.
    if (name == "examples") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
        tasks.named<Test>("test") { useJUnitPlatform { includeTags("sandbox-it") } }
        return@subprojects
    }

    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    if (name == "bom") {
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["javaPlatform"])
                        applyRightsizePom(project.name)
                    }
                }
            }
            signPublicationsWhenKeyPresent()
        }
        return@subprojects
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }
    // Maven Central requires a sources and a javadoc jar next to every library jar. The
    // javadoc jar is produced from the (empty) Java source set — the accepted convention
    // for Kotlin libraries; Central's validation checks presence, not content.
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                applyRightsizePom(project.name)
            }
        }
    }
    signPublicationsWhenKeyPresent()
    tasks.named<Test>("test") { useJUnitPlatform { excludeTags("sandbox-it") } }
    val testSourceSet = extensions.getByType<SourceSetContainer>()["test"]
    tasks.register<Test>("integrationTest") {
        description = "Tests requiring a sandbox runtime (msb or docker)"
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("sandbox-it") }
        outputs.upToDateWhen { false }
        // Full exception detail in the console: these failures carry runtime diagnostics
        // (msb/daemon output tails) that are otherwise only in the HTML report, which CI
        // logs never show.
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("failed")
        }
    }

    // Coverage floor on `core` only: it's the pure-logic module (GenericContainer, Backends,
    // Wait, Network, FreePorts, Sandboxed). The backend modules' real logic is exercised only by
    // sandbox-it (excluded from `test`), so a test-scoped gate there would be misleadingly low.
    if (name == "core") {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.12" }
        tasks.named<Test>("test") { finalizedBy(tasks.named("jacocoTestReport")) }
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports { xml.required.set(true); html.required.set(true) }
        }
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("jacocoTestReport"))
            violationRules {
                rule {
                    limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
                    // Intentionally tight per the R2a audit: measured branch coverage sits only
                    // ~1 branch above this floor, so a dropped assertion can trip it quickly.
                    limit { counter = "BRANCH"; minimum = "0.70".toBigDecimal() }
                }
            }
        }
        tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
    }
}
