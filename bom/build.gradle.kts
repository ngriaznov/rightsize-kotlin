plugins { `java-platform` }
dependencies {
    constraints {
        api(project(":core")); api(project(":backend-microsandbox"))
        api(project(":backend-docker")); api(project(":modules"))
    }
}
