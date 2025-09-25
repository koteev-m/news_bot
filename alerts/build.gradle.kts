plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
