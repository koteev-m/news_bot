plugins {
    alias(libs.plugins.ktor)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(project(":storage"))
    implementation(project(":integrations"))
    implementation("io.micrometer:micrometer-registry-prometheus:${libs.versions.micrometer.get()}")
}

application {
    mainClass.set("app.ApplicationKt")
}
