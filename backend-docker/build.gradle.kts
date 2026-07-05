dependencies {
    api(project(":core"))
    implementation("com.github.docker-java:docker-java-core:3.4.0")
    // Zerodep transport bundles its own unix-socket client (JNA) and pulls in no
    // httpclient5, so the docker backend keeps working on consumer classpaths that
    // manage httpclient5 to >=5.4 (e.g. Spring Boot). docker-java's httpclient5
    // transport misroutes the unix:// socket to TCP localhost:2375 under httpclient5 5.4.
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.0")
    testImplementation(testFixtures(project(":core")))
}
