package io.ktor.server.config

fun ApplicationConfig.configOrNull(path: String): ApplicationConfig? =
    try {
        config(path)
    } catch (_: ApplicationConfigurationException) {
        null
    }
