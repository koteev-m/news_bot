plugins {
    alias(libs.plugins.ktor)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.java.jwt)
    implementation(project(":core"))
    implementation(project(":storage"))
    implementation(project(":integrations"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation("io.micrometer:micrometer-registry-prometheus:${libs.versions.micrometer.get()}")
    testImplementation(libs.kotlin.test)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("app.ApplicationKt")
}
