import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.ktor)
}

dependencies {
    val ktorVersion = libs.versions.ktor.get()
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-encoding:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.java.jwt)
    implementation(libs.logback.logstash)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.context)
    implementation(libs.sentry.core)
    implementation(libs.sentry.logback)
    implementation(libs.lettuce.core)
    implementation(project(":core"))
    implementation(project(":storage"))
    implementation(project(":integrations"))
    implementation(project(":alerts"))
    implementation(project(":news"))
    implementation(libs.hikaricp)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.pengrad.bot)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.pengrad.bot)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation("org.junit.platform:junit-platform-suite:1.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation(testFixtures(project(":storage")))
}

application {
    mainClass.set("app.ApplicationKt")
}

tasks.register<JavaExec>("runRecon") {
    group = "application"
    description = "Runs billing reconciliation job"
    mainClass.set("billing.recon.ReconciliationRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    dependsOn(tasks.named("classes"))
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
    useJUnitPlatform()
    filter {
        includeTestsMatching("app.AllP05RoutesTestSuite")
        includeTestsMatching("*IT")
    }
}
