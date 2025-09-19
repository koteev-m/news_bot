dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.coroutines.core)
}
