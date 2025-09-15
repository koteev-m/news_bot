rootProject.name = "news_bot"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    ":app",
    ":core",
    ":integrations",
    ":bot",
    ":news",
    ":alerts",
    ":storage",
    ":tests"
)
