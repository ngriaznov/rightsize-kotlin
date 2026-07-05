dependencies {
    api(project(":core"))
    testRuntimeOnly(project(":backend-microsandbox"))
    testRuntimeOnly(project(":backend-docker"))
    testImplementation("io.lettuce:lettuce-core:6.5.1.RELEASE")
    testImplementation("org.mongodb:mongodb-driver-sync:5.2.1")
    testImplementation("org.apache.kafka:kafka-clients:3.8.0")
    testImplementation("com.arangodb:arangodb-java-driver:7.9.0")
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("com.mysql:mysql-connector-j:9.3.0")
    testImplementation("com.rabbitmq:amqp-client:5.25.0")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
}
