import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Task
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.ktlint.gradle) apply false
    alias(libs.plugins.detekt.gradle) apply false
    alias(libs.plugins.kover) apply false
}

val strictLint: Boolean by lazy {
    (project.findProperty("strictLint")?.toString()?.toBoolean() == true) ||
        (System.getenv("STRICT_LINT")?.toBoolean() == true)
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
            apply(plugin = "org.jetbrains.kotlinx.kover")

            dependencies.add(
                "detektPlugins",
                "io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}"
            )

            extensions.configure(KtlintExtension::class.java) {
                android.set(false)
                ignoreFailures.set(!strictLint)
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
                ignoreFailures = !strictLint
                buildUponDefaultConfig = true
                allRules = false
                autoCorrect = !strictLint
                config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
                baseline = file("$rootDir/config/detekt/baseline.xml")
                source.setFrom(
                    files(
                        "$projectDir/src/main/kotlin",
                        "$projectDir/src/test/kotlin",
                    )
                )
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

            val koverAgentTasks = tasks.matching { it.name == "koverAgentJar" }

            tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                testLogging {
                    events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                }
                filter {
                    isFailOnNoMatchingTests = false
                }
                dependsOn(koverAgentTasks)
                doFirst {
                    val argsFile = project.layout.buildDirectory.file("tmp/${name}/kover-agent.args").get().asFile
                    argsFile.parentFile.mkdirs()
                    if (!argsFile.exists()) {
                        argsFile.writeText("")
                    }
                }
            }

            tasks.named("check").configure {
                dependsOn("ktlintCheck", "detekt", "koverXmlReport", "test")
            }
        }
    }
}

tasks.register("installGitHooks") {
    group = "git"
    description = "Install pre-commit and pre-push hooks"
    doLast {
        val hooksDir = file(".git/hooks")
        hooksDir.mkdirs()

        val hooks = mapOf(
            "pre-commit" to file("tools/git-hooks/pre-commit"),
            "pre-push" to file("tools/git-hooks/pre-push")
        )

        hooks.forEach { (name, source) ->
            if (!source.exists()) {
                println("WARN: missing ${source.toRelativeString(projectDir)}")
                return@forEach
            }

            val destination = hooksDir.resolve(name)
            source.copyTo(destination, overwrite = true)
            if (!destination.setExecutable(true)) {
                println("WARN: failed to mark ${destination} as executable")
            }
            println("Installed ${destination.toPath().normalize()}")
        }
    }
}
