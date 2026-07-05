rootProject.name = "rightsize"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "backend-microsandbox", "backend-docker", "modules", "bom", "examples")
