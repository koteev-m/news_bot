plugins {
    alias(libs.plugins.ktor)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation("io.ktor:ktor-client-core-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-cio:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-logging:${libs.versions.ktor.get()}")
    implementation("com.github.pengrad:java-telegram-bot-api:9.2.0")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
