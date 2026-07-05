plugins { `java-test-fixtures` }
dependencies {
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.11.3") // JUnit 5 extension is optional at runtime
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testImplementation("org.junit.platform:junit-platform-launcher") // Launcher-based test drives the extension

}
