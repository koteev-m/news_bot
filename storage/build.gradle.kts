import org.flywaydb.gradle.FlywayExtension
import org.flywaydb.gradle.task.AbstractFlywayTask
import org.gradle.api.tasks.testing.Test

plugins {
    id("java-test-fixtures")
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.flywaydb:flyway-gradle-plugin:11.14.0")
        classpath("org.flywaydb:flyway-database-postgresql:11.14.0")
        classpath("org.postgresql:postgresql:42.7.4")
    }
}

apply(plugin = "org.flywaydb.flyway")

val withDb = project.findProperty("withDb")?.toString()?.toBoolean() == true || System.getenv("DATABASE_URL")?.isNotBlank() == true

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
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly(libs.logback.classic)

    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
    testFixturesImplementation(platform(libs.testcontainers.bom))
    testFixturesImplementation(libs.testcontainers.core)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.testcontainers.junit)
    testFixturesImplementation(libs.flyway.postgresql)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.exposed.core)
    testFixturesImplementation(libs.exposed.dao)
    testFixturesImplementation(libs.exposed.jdbc)
    testFixturesImplementation(libs.exposed.java.time)
    testFixturesImplementation(libs.exposed.json)
    testFixturesImplementation(libs.hikaricp)
    testFixturesImplementation(libs.coroutines.core)
}

if (withDb) {
    val flywayUrl = (project.findProperty("flyway.url") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("DATABASE_URL")
        ?: "jdbc:postgresql://localhost:5432/newsbot"
    val flywayUser = (project.findProperty("flyway.user") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("DATABASE_USER")
        ?: "app"
    val flywayPassword = (project.findProperty("flyway.password") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("DATABASE_PASS")
        ?: "app_pass"
    val flywaySchemasRaw = (project.findProperty("flyway.schemas") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("DATABASE_SCHEMA")
        ?: "public"
    val flywaySchemas = flywaySchemasRaw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toTypedArray()

    configure<FlywayExtension> {
        url = flywayUrl
        user = flywayUser
        password = flywayPassword
        schemas = flywaySchemas
        locations = arrayOf("filesystem:src/main/resources/db/migration")
        configurations = arrayOf("flyway")
    }

    tasks.register("flywayMigrateIfDb") {
        dependsOn("flywayMigrate")
    }
} else {
    tasks.register("flywayMigrateIfDb") {
        doLast { println("Flyway skipped: no DB (use -PwithDb=true or set DATABASE_URL)") }
    }
}

tasks.withType<AbstractFlywayTask>().configureEach {
    onlyIf { withDb }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}
