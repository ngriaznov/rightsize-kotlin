plugins { `java-test-fixtures` }
dependencies {
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.11.3") // JUnit 5 extension is optional at runtime
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testImplementation("org.junit.platform:junit-platform-launcher") // Launcher-based test drives the extension

}

// The `Reaper` singleton (unlike `ReaperEngine`) reads the real cache dir / real env at first
// touch, with no injectable overload — by design, it's production-only wiring (see its doc
// comment). Pin it to a build-local dir here so a test that deliberately names its fake backend
// "docker"/"microsandbox" (to exercise the real ledger through `GenericContainer`/`Network`) can
// observe ledger file contents without ever touching the developer's real `~/.cache/rightsize`.
// SWEEP (not the ON default) keeps ledger writes but skips the watchdog's detached-process spawn,
// which a unit test must never trigger.
tasks.named<Test>("test") {
    environment("RIGHTSIZE_CACHE_DIR", layout.buildDirectory.dir("test-cache/rightsize").get().asFile.absolutePath)
    environment("RIGHTSIZE_REAPER", "sweep")
}
