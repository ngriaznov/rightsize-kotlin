rootProject.name = "rightsize"

plugins {
    // Publishes the maven-publish publications to Maven Central through the Central
    // Portal publisher API (`./gradlew publishAggregationToCentralPortal`). Credentials
    // are portal tokens supplied via environment; when they're absent only the publish
    // tasks themselves fail, never a regular build.
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_TOKEN_USERNAME") ?: ""
        password = System.getenv("CENTRAL_TOKEN_PASSWORD") ?: ""
        // Uploads only stage a deployment in the portal UI; releasing it to Maven
        // Central stays a deliberate click there, so a bad upload can be dropped.
        publishingType = "USER_MANAGED"
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "backend-microsandbox", "backend-docker", "modules", "bom", "examples")
