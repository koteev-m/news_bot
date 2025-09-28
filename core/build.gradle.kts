dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation("org.slf4j:slf4j-api:2.0.13")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.coroutines.core)
}
