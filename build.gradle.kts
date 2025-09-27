import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.ktlint.gradle) apply false
    alias(libs.plugins.detekt.gradle) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }

    if (!plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }
    if (!plugins.hasPlugin("org.jetbrains.kotlin.plugin.serialization")) {
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    }

    extensions.configure(KotlinJvmProjectExtension::class.java) {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }

    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        useJUnitPlatform()
    }

    afterEvaluate {
        val hasKotlin = plugins.hasPlugin("org.jetbrains.kotlin.jvm") ||
            plugins.hasPlugin("org.jetbrains.kotlin.android") ||
            plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        if (hasKotlin) {
            apply(plugin = "org.jlleitschuh.gradle.ktlint")
            apply(plugin = "io.gitlab.arturbosch.detekt")

            dependencies.add(
                "detektPlugins",
                "io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}"
            )

            extensions.configure(KtlintExtension::class.java) {
                android.set(false)
                ignoreFailures.set(false)
                reporters {
                    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
                }
                filter {
                    exclude("**/build/**")
                    exclude("**/generated/**")
                }
            }

            extensions.configure(DetektExtension::class.java) {
                buildUponDefaultConfig = true
                allRules = false
                autoCorrect = false
                config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
                source.setFrom(files(projectDir))
                parallel = true
                basePath = rootDir.path
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                    txt.required.set(false)
                    sarif.required.set(false)
                    md.required.set(false)
                }
            }

            tasks.register("lint") {
                group = "verification"
                description = "Run ktlintCheck and detekt"
                dependsOn("ktlintCheck", "detekt")
            }
            tasks.register("format") {
                group = "formatting"
                description = "Run ktlintFormat"
                dependsOn("ktlintFormat")
            }

            tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                testLogging {
                    events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                }
            }
        }
    }
}

// Hook installer: переустановит pre-commit
tasks.register("installGitHooks") {
    group = "git"
    description = "Install pre-commit hook for lint/conflict markers"
    doLast {
        val src = file("tools/git-hooks/pre-commit")
        val dst = file(".git/hooks/pre-commit")
        if (!src.exists()) error("Missing tools/git-hooks/pre-commit")
        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = true)
        dst.setExecutable(true)
        println("Installed .git/hooks/pre-commit")
    }
}
