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
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-encoding:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
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
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.3")
}

application {
    mainClass.set("app.ApplicationKt")
}
