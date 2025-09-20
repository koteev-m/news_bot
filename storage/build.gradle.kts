import org.flywaydb.gradle.FlywayExtension

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.flywaydb:flyway-gradle-plugin:10.17.2")
        classpath("org.flywaydb:flyway-database-postgresql:10.17.2")
        classpath("org.postgresql:postgresql:42.7.4")
    }
}

apply(plugin = "org.flywaydb.flyway")

repositories {
    mavenCentral()
}

configurations {
    create("flyway")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.micrometer.core)
    implementation(libs.serialization.json)
    implementation(libs.typesafe.config)
    implementation(libs.flyway.core)

    // Dependencies required for Flyway Gradle task
    add("flyway", libs.postgresql)
    add("flyway", libs.flyway.postgresql)
    add("flyway", libs.flyway.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.coroutines.core)
    testRuntimeOnly(libs.logback.classic)
}

configure<FlywayExtension> {
    url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/newsbot"
    user = System.getenv("DATABASE_USER") ?: "app"
    password = System.getenv("DATABASE_PASS") ?: "app_pass"
    schemas = arrayOf(System.getenv("DATABASE_SCHEMA") ?: "public")
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    configurations = arrayOf("flyway")
}

tasks.test {
    useJUnitPlatform()
}
