// Runnable showcase code for rightsize — NOT part of the published BOM/artifacts (see the
// root build.gradle.kts `examples` special-case: no maven-publish applied here at all).
//
// Each example is its own entry point, run with a dedicated Gradle task:
//   ./gradlew :examples:runRedis      — plain-API Redis quickstart (application main)
//   ./gradlew :examples:test          — JUnit @Sandboxed PostgreSQL example
//   ./gradlew :examples:runNetwork    — two-container Network example (application main)
//
// All three resolve their backend the same way the rest of rightsize does: automatically,
// or forced with RIGHTSIZE_BACKEND=microsandbox|docker.

dependencies {
    implementation(project(":core"))
    implementation(project(":modules"))
    runtimeOnly(project(":backend-microsandbox"))
    runtimeOnly(project(":backend-docker"))

    // Redis quickstart: a real client keeps the example honest (a raw socket would only prove
    // "something is listening on 6379"), and lettuce is the smallest well-known Java Redis client.
    implementation("io.lettuce:lettuce-core:6.5.1.RELEASE")

    // PostgreSQL JDBC round-trip example: test-only dependency, matching how modules/ itself
    // depends on the driver only for its own tests.
    testImplementation("org.postgresql:postgresql:42.7.7")
}

/** Shared setup for the two plain `main()` examples: same classpath, same working directory. */
fun JavaExec.configureExample(mainClassName: String) {
    group = "examples"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(mainClassName)
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("runRedis") {
    description = "Runs the Redis quickstart example (start -> SET/GET via lettuce -> stop)."
    configureExample("dev.rightsize.examples.RedisQuickstartKt")
}

tasks.register<JavaExec>("runNetwork") {
    description = "Runs the two-container Network example (WireMock reached by alias from a consumer)."
    configureExample("dev.rightsize.examples.NetworkExampleKt")
}
