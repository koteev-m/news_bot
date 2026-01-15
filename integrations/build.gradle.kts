import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-progressive")
    }
}

val ktorVersion = libs.versions.ktor.get()
val coroutinesVersion = libs.versions.coroutines.get()
val micrometerVersion = libs.versions.micrometer.get()

dependencies {
    implementation(project(":core"))
    api("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-encoding:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation(libs.serialization.json)
    implementation(libs.kafka.clients)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.context)
    api(libs.micrometer.core)
    api("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    testImplementation(libs.kotlin.test)
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation(libs.junit.jupiter.api)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation(libs.micrometer.core)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
